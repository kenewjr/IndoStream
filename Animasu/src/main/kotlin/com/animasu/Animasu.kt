package com.animasu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Animasu : MainAPI() {
    // [FIXED]: domain canonical sekarang adalah v1.animasu.work — homepage di
    // v1.animasu.top masih hidup tapi semua link konten dirender pakai .work,
    // sehingga jika kita pakai .top maka URL detail/episode sering 404.
    override var mainUrl = "https://v1.animasu.work"
    override var name = "Animasu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime
            return when {
                t.contains("Tv", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            if (t == null) return ShowStatus.Completed
            return when {
                t.contains("Sedang Tayang", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage =
            mainPageOf(
                    "urutan=update" to "Baru diupdate",
                    "status=&tipe=&urutan=publikasi" to "Baru ditambahkan",
                    "status=&tipe=&urutan=populer" to "Terpopuler",
                    "status=&tipe=&urutan=rating" to "Rating Tertinggi",
                    "status=&tipe=Movie&urutan=update" to "Movie Terbaru",
                    "status=&tipe=Movie&urutan=populer" to "Movie Terpopuler",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/pencarian/?${request.data}&halaman=$page").document
        val home = document.select("div.listupd div.bs").map { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title =
                    when {
                        (title.contains("-episode")) && !(title.contains("-movie")) ->
                                title.substringBefore("-episode")
                        (title.contains("-movie")) -> title.substringBefore("-movie")
                        else -> title
                    }

            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse {
        val href = getProperAnimeLink(fixUrlNull(this.selectFirst("a")?.attr("href")).toString())
        val title = this.select("div.tt").text().trim()
        val posterUrl = this.selectFirst("div.limit img")?.attr("src").toString()
        val epNum = this.selectFirst("span.epx")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.select("div.listupd div.bs").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title =
                document.selectFirst("div.infox h1")
                        ?.text()
                        .toString()
                        .replace("Sub Indo", "")
                        .trim()
        val poster = document.selectFirst("div.bigcontent img")?.attr("src").toString()

        val table = document.selectFirst("div.infox div.spe")
        val type = getType(table?.selectFirst("span:contains(Jenis:)")?.ownText())
        val year =
                table?.selectFirst("span:contains(Rilis:)")
                        ?.ownText()
                        ?.substringAfterLast(",")
                        ?.trim()
                        ?.toIntOrNull()
        val status = table?.selectFirst("span:contains(Status:) font")?.text()
        val trailer = document.selectFirst("div.trailer iframe")?.attr("src")
        val episodes =
            document.select("ul#daftarepisode > li")
                .mapNotNull {
                    val link = fixUrl(it.selectFirst("a")!!.attr("href"))
                    val episodeName = it.selectFirst("a")?.text() ?: return@mapNotNull null
                    val episodeNumber =
                        Regex("Episode\\s?(\\d+)")
                            .find(episodeName)
                            ?.groupValues
                            ?.getOrNull(1) // Corrected group index
                            ?.toIntOrNull()
                    newEpisode(link) { // 'link' is the 'data' argument
                        this.name = episodeName // Set the name property
                        this.episode = episodeNumber // Set the episode property
                    }
                }
                .reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(status)
            plot = document.select("div.sinopsis p").text()
            this.tags = table?.select("span:contains(Genre:) a")?.map { it.text() }
            addTrailer(trailer)
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        // [FIX]: tambah error handling + fallback selector. Sebelumnya jika
        // attr("value") kosong, base64Decode throw exception dan tidak ada
        // link yang dikembalikan.
        return try {
            val document = app.get(data).document
            // [UPDATED SELECTOR]: cover varian markup yang berbeda.
            val mirrors = document.select(
                ".mobius > .mirror > option, " +
                ".mobius .mirror option, " +
                "select.mirror option, " +
                ".mirrorstream li a"
            )

            val streamPairs = mirrors.mapNotNull { opt ->
                val raw = opt.attr("value").takeIf { it.isNotBlank() }
                    ?: opt.attr("data-content").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val iframeSrc = runCatching {
                    Jsoup.parse(base64Decode(raw)).select("iframe").attr("src")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val quality = opt.text().ifBlank { "Default" }
                fixUrl(iframeSrc) to quality
            }

            // [FALLBACK]: jika dropdown mirror kosong, coba ambil iframe langsung.
            val pairs = streamPairs.ifEmpty {
                document.select("iframe[src]").mapNotNull { iframe ->
                    val src = iframe.attr("src").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    fixUrl(src) to "Default"
                }
            }

            pairs.amap { (iframe, quality) ->
                runCatching {
                    loadFixedExtractor(
                        iframe.fixIframe(),
                        quality,
                        "$mainUrl/",
                        subtitleCallback,
                        callback
                    )
                }
            }
            pairs.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun loadFixedExtractor(
        name: String? = null,
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        quality: Int? = null,
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            CoroutineScope(Dispatchers.IO).launch {
                callback.invoke(
                    newExtractorLink(
                        name ?: link.source,
                        name ?: link.name,
                        link.url,
                    ) {
                        this.quality = when {
                            else -> quality ?: link.quality
                        }
                        this.type = link.type
                        this.referer = link.referer
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun String.fixIframe(): String {
        return if (this.startsWith("https://dl.berkasdrive.com")) {
            base64Decode(this.substringAfter("id="))
        } else {
            this
        }
    }    

}
