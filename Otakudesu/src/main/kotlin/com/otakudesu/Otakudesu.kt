package com.otakudesu

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.extractors.JWPlayer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Otakudesu : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        const val acefile = "https://acefile.co"
        val mirrorBlackList =
                arrayOf(
                        "Mega",
                        "MegaUp",
                        "Otakufiles",
                )

        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime
        }

        fun getStatus(t: String?): ShowStatus {
            return when {
                t == null -> ShowStatus.Completed
                t.contains("Ongoing", true) -> ShowStatus.Ongoing
                t.contains("Completed", true) -> ShowStatus.Completed
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage =
            mainPageOf(
                    "$mainUrl/ongoing-anime/page/" to "Anime Ongoing",
                    "$mainUrl/complete-anime/page/" to "Anime Completed"
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.venz > ul > li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2.jdlflm")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("div.thumbz > img")?.attr("src")
            ?: this.selectFirst("img")?.attr("src")
        val epNum =
                this.selectFirst("div.epz")
                        ?.ownText()
                        ?.replace(Regex("\\D"), "")
                        ?.trim()
                        ?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // [ENHANCED]: load semua hasil pencarian dengan pagination penuh.
        // Sebelumnya hanya halaman pertama saja yang ditampilkan.
        // Kita iterasi sampai halaman tidak lagi mengembalikan hasil baru
        // (hard cap 10 halaman untuk menghindari abuse).
        val results = mutableListOf<SearchResponse>()
        val seenHrefs = mutableSetOf<String>()
        for (page in 1..10) {
            val url = if (page == 1) "$mainUrl/?s=$query&post_type=anime"
            else "$mainUrl/page/$page/?s=$query&post_type=anime"
            val pageResults = runCatching { app.get(url).document }.getOrNull()
                ?.select("ul.chivsrc > li")
                ?.mapNotNull { li ->
                    val title = li.selectFirst("h2 > a")?.ownText()?.trim() ?: return@mapNotNull null
                    val href = li.selectFirst("h2 > a")?.attr("href") ?: return@mapNotNull null
                    val posterUrl = li.selectFirst("img")?.attr("src")
                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = posterUrl
                    }
                } ?: emptyList()
            // Stop kalau halaman ini kosong atau semua hasilnya sudah dilihat
            // (dedupe via href set untuk menangani pagination yang loop).
            val newResults = pageResults.filter { seenHrefs.add(it.url) }
            if (newResults.isEmpty()) break
            results.addAll(newResults)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Title: try multiple strategies
        val title = document.selectFirst("div.infozingle > p > span:contains(Judul)")
                ?.ownText()?.replace(":", "")?.trim()
            ?: document.selectFirst("h1.jdlrx")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()
                ?.removeSuffix("Subtitle Indonesia")?.trim()
            ?: ""

        // Poster: try multiple selectors
        val poster = document.selectFirst("div.fotoanime > img")?.attr("src")
            ?: document.selectFirst("div.fotoanime img")?.attr("src")
            ?: document.selectFirst("img.attachment-post-thumbnail")?.attr("src")

        // Metadata extraction using label-based lookups (robust against reordering)
        val infoElements = document.select("div.infozingle > p")

        fun getInfoByLabel(label: String): String? {
            return infoElements.firstOrNull { el ->
                el.selectFirst("span")?.text()?.contains(label, ignoreCase = true) == true
            }?.selectFirst("span")?.ownText()?.replace(":", "")?.trim()
                ?.takeIf { it.isNotBlank() }
        }

        fun getInfoLinksByLabel(label: String): List<Element> {
            return infoElements.firstOrNull { el ->
                el.selectFirst("span")?.text()?.contains(label, ignoreCase = true) == true
            }?.select("a") ?: emptyList()
        }

        val tags = getInfoLinksByLabel("Genre").map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty {
                // Fallback: try to parse genre text after ":"
                getInfoByLabel("Genre")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
            }

        val typeStr = getInfoByLabel("Tipe")
        val type = getType(typeStr)

        val statusStr = getInfoByLabel("Status")
        val status = getStatus(statusStr)

        // Year extraction from "Tanggal Rilis" field (e.g., "Apr 03, 2026")
        val releaseDate = getInfoByLabel("Tanggal Rilis") ?: ""
        val year = Regex("(\\d{4})").find(releaseDate)?.groupValues?.get(1)?.toIntOrNull()

        // Duration extraction (e.g., "24 min." -> 24)
        val durationStr = getInfoByLabel("Durasi") ?: ""
        val duration = Regex("(\\d+)").find(durationStr)?.groupValues?.get(1)?.toIntOrNull()

        // Score extraction
        val scoreStr = getInfoByLabel("Skor") ?: ""

        val description = document.select("div.sinopc > p").text().trim()
            .takeIf { it.isNotBlank() }
            ?: document.selectFirst("div.sinopc")?.text()?.trim()

        // Episode list: find the episode list section robustly
        val episodes = document.select("div.episodelist")
                .lastOrNull()
                ?.select("ul > li")
                ?.mapNotNull {
                    val epName = it.selectFirst("a")?.text() ?: return@mapNotNull null
                    val epLink = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                    val episodeNumber = Regex("Episode\\s?(\\d+)")
                            .find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    newEpisode(epLink) {
                        this.name = epName
                        this.episode = episodeNumber
                    }
                }
                ?.reversed()
                ?: emptyList()

        // Recommendations
        val recommendations =
                document.select("div.isi-recommend-anime-series > div.isi-konten").mapNotNull {
                    val recName = it.selectFirst("span.judul-anime > a")?.text() ?: return@mapNotNull null
                    val recHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                    val recPosterUrl = it.selectFirst("a > img")?.attr("src")
                    newAnimeSearchResponse(recName, recHref, TvType.Anime) {
                        this.posterUrl = recPosterUrl
                    }
                }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            this.duration = duration
            addScore(scoreStr)
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    data class ResponseSources(
            @JsonProperty("id") val id: String,
            @JsonProperty("i") val i: String,
            @JsonProperty("q") val q: String,
    )

    data class ResponseData(@JsonProperty("data") val data: String)

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document

        runAllAsync(
                {
                    // Streaming: extract mirror sources via AJAX
                    val scriptData =
                        document.select("script:containsData(action:)").lastOrNull()?.data()
                    val token =
                        scriptData
                            ?.substringAfter("{action:\"")
                            ?.substringBefore("\"}")
                            .toString()

                    val nonce = try {
                        app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            data = mapOf("action" to token)
                        ).parsed<ResponseData>().data
                    } catch (e: Exception) { "" }

                    val action =
                        scriptData
                            ?.substringAfter(",action:\"")
                            ?.substringBefore("\"}")
                            .toString()

                    val mirrorData =
                        document.select("div.mirrorstream > ul > li")
                            .mapNotNull {
                                try {
                                    base64Decode(it.select("a").attr("data-content"))
                                } catch (e: Exception) { null }
                            }
                            .toString()

                    tryParseJson<List<ResponseSources>>(mirrorData)?.amap { res ->
                        try {
                            val id = res.id
                            val i = res.i
                            val q = res.q

                            val sources =
                                Jsoup.parse(
                                    base64Decode(
                                        app.post(
                                            "${mainUrl}/wp-admin/admin-ajax.php",
                                            data =
                                                mapOf(
                                                    "id" to id,
                                                    "i" to i,
                                                    "q" to q,
                                                    "nonce" to nonce,
                                                    "action" to action
                                                )
                                        ).parsed<ResponseData>().data
                                    )
                                ).select("iframe").attr("src")

                            if (sources.isNotBlank()) {
                                loadCustomExtractor(
                                    sources,
                                    data,
                                    subtitleCallback,
                                    callback,
                                    getQuality(q)
                                )
                            }
                        } catch (e: Exception) {
                            // Skip failed mirror sources
                        }
                    }
                },
                {
                    // Downloads: extract download links with quality info
                    document.select("div.download li").map { ele ->
                        val quality = getQuality(ele.select("strong").text())
                        ele.select("a")
                            .map { it.attr("href") to it.text() }
                            .filter {
                                it.first.isNotBlank() &&
                                !inBlacklist(it.second) &&
                                quality != Qualities.P360.value
                            }
                            .amap {
                                try {
                                    val link = app.get(it.first, referer = "$mainUrl/").url
                                    loadCustomExtractor(
                                        fixedIframe(link),
                                        data,
                                        subtitleCallback,
                                        callback,
                                        quality
                                    )
                                } catch (e: Exception) {
                                    // Skip failed download links
                                }
                            }
                    }
                }
            )

        return true
    }

    private suspend fun loadCustomExtractor(
            url: String,
            referer: String? = null,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit,
            quality: Int = Qualities.Unknown.value,
    ) = coroutineScope {
        loadExtractor(url, referer, subtitleCallback) { link ->
            launch(Dispatchers.IO) {
                callback.invoke(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type
                    ) {
                        this.referer = link.referer
                        this.quality = quality
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun fixedIframe(url: String): String {
        return when {
            url.startsWith(acefile) -> {
                val id = Regex("""(?:/f/|/file/)(\w+)""").find(url)?.groupValues?.getOrNull(1)
                if (id != null) "${acefile}/player/$id" else url
            }
            else -> fixUrl(url)
        }
    }

    private fun inBlacklist(host: String?): Boolean {
        return mirrorBlackList.any { it.equals(host, true) }
    }

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
    }
}

class Moedesu : JWPlayer() {
    override val name = "Moedesu"
    override val mainUrl = "https://desustream.me/moedesu/"
}

class DesuBeta : JWPlayer() {
    override val name = "DesuBeta"
    override val mainUrl = "https://desustream.me/beta/"
}

class Desudesuhd : JWPlayer() {
    override val name = "Desudesuhd"
    override val mainUrl = "https://desustream.me/desudesuhd/"
}
