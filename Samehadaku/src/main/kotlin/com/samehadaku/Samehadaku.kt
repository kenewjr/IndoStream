package com.samehadaku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element

class Samehadaku : MainAPI() {
    override var mainUrl = DOMAIN
    override var name = "Samehadaku"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        // Single point of domain rotation. Change here when the site moves.
        const val DOMAIN = "https://v2.samehadaku.how"

        val baseHeaders =
            mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            )

        fun getType(t: String): TvType = if (t.contains("OVA", true) || t.contains("Special", true)) {
            TvType.OVA
        } else if (t.contains("Movie", true)) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        fun getStatus(t: String): ShowStatus = when (t) {
            "Completed" -> ShowStatus.Completed
            "Ongoing" -> ShowStatus.Ongoing
            else -> ShowStatus.Completed
        }
    }

    override val mainPage =
        mainPageOf(
            "$mainUrl/page/" to "Episode Terbaru",
            "$mainUrl/" to "HomePage",
            "$mainUrl/daftar-anime-2/page/" to "Daftar Anime",
            "$mainUrl/anime-status/ongoing/page/" to "Ongoing",
            "$mainUrl/anime-status/completed/page/" to "Completed",
            "$mainUrl/anime-type/tv/page/" to "TV",
            "$mainUrl/anime-type/movie/page/" to "Movie",
            "$mainUrl/anime-type/ova/page/" to "OVA",
            "$mainUrl/jadwal-rilis/page/" to "Jadwal Rilis",
            "$mainUrl/genre/action/page/" to "Genre: Action",
            "$mainUrl/genre/adventure/page/" to "Genre: Adventure",
            "$mainUrl/genre/comedy/page/" to "Genre: Comedy",
            "$mainUrl/genre/drama/page/" to "Genre: Drama",
            "$mainUrl/genre/fantasy/page/" to "Genre: Fantasy",
            "$mainUrl/genre/isekai/page/" to "Genre: Isekai",
            "$mainUrl/genre/romance/page/" to "Genre: Romance",
            "$mainUrl/genre/school/page/" to "Genre: School",
            "$mainUrl/genre/shounen/page/" to "Genre: Shounen",
            "$mainUrl/genre/supernatural/page/" to "Genre: Supernatural",
            "$mainUrl/anime-year/2026/page/" to "Tahun 2026",
            "$mainUrl/anime-year/2025/page/" to "Tahun 2025",
            "$mainUrl/anime-year/2024/page/" to "Tahun 2024",
        )

    /**
     * Wraps app.get with up to [maxRetries] attempts and a small backoff.
     * Centralizes timeout + headers; returns null instead of throwing to keep
     * callsites null-safe.
     */
    private suspend fun safeGet(
        url: String,
        referer: String? = "$mainUrl/",
        maxRetries: Int = 3,
    ): com.lagradost.nicehttp.NiceResponse? {
        var lastError: Throwable? = null
        repeat(maxRetries) { attempt ->
            try {
                return app.get(
                    url,
                    referer = referer,
                    headers = baseHeaders,
                    timeout = 30L,
                )
            } catch (t: Throwable) {
                lastError = t
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(700L * (attempt + 1))
                }
            }
        }
        com.lagradost.cloudstream3.mvvm.logError(
            (lastError ?: Exception("Samehadaku safeGet failed: $url")),
        )
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        if (request.name != "Episode Terbaru" && page <= 1) {
            val doc = safeGet(request.data)?.document
            doc?.select("div.widget_senction:not(:contains(Baca Komik))")?.forEach { block ->
                val header = block.selectFirst("div.widget-title h3")?.ownText() ?: return@forEach
                val home = block.select("div.animepost").mapNotNull { it.toSearchResult() }
                if (home.isNotEmpty()) items.add(HomePageList(header, home))
            }
        }

        if (request.name == "Episode Terbaru") {
            val home =
                safeGet(request.data + page)
                    ?.document
                    ?.selectFirst("div.post-show")
                    ?.select("ul li")
                    ?.mapNotNull { it.toSearchResult() }
                    .orEmpty()
            items.add(HomePageList(request.name, home, true))
        }

        return newHomePageResponse(items)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title =
            this.selectFirst("div.title, h2.entry-title a, div.lftinfo h2")?.text()?.trim()
                ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.select("img").attr("src"))
        val epNum = this.selectFirst("div.dtla author")?.text()?.toIntOrNull()
        return newAnimeSearchResponse(title, href ?: return null, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenHrefs = mutableSetOf<String>()
        for (page in 1..5) {
            val url =
                if (page == 1) {
                    "$mainUrl/?s=$query"
                } else {
                    "$mainUrl/page/$page/?s=$query"
                }
            val pageResults =
                safeGet(url)
                    ?.document
                    ?.select("main#main div.animepost")
                    ?.mapNotNull { it.toSearchResult() }
                    ?: emptyList()
            val newResults = pageResults.filter { seenHrefs.add(it.url) }
            if (newResults.isEmpty()) break
            results.addAll(newResults)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixUrl =
            if (url.contains("/anime/")) {
                url
            } else {
                safeGet(url)
                    ?.document
                    ?.selectFirst("div.nvs.nvsc a")
                    ?.attr("href")
            }

        val document = safeGet(fixUrl ?: return null)?.document ?: return null
        val title = document.selectFirst("h1.entry-title")?.text()?.removeBloat() ?: return null
        val poster = document.selectFirst("div.thumb > img")?.attr("src")
        val tags = document.select("div.genre-info > a").map { it.text() }
        val year =
            document.selectFirst("div.spe > span:contains(Rilis)")?.ownText()?.let {
                Regex("\\d,\\s(\\d*)")
                    .find(it)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }
        val status =
            getStatus(
                document.selectFirst("div.spe > span:contains(Status)")?.ownText()
                    ?: return null,
            )
        val type =
            getType(
                document
                    .selectFirst("div.spe > span:contains(Type)")
                    ?.ownText()
                    ?.trim()
                    ?.lowercase()
                    ?: "tv",
            )
        val rating = document.selectFirst("span.ratingValue")?.text()?.trim()
        val description = document.select("div.desc p").text().trim()
        val trailer = document.selectFirst("div.trailer-anime iframe")?.attr("src")

        val episodes =
            document
                .select("div.lstepsiode.listeps ul li")
                .mapNotNull {
                    val header = it.selectFirst("span.lchx > a") ?: return@mapNotNull null
                    val episode =
                        Regex("Episode\\s?(\\d+)")
                            .find(header.text())
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    val link = fixUrl(header.attr("href"))
                    newEpisode(link) { this.episode = episode }
                }.reversed()

        val recommendations =
            document.select("aside#sidebar ul li").mapNotNull { it.toSearchResult() }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            addScore(rating)
            plot = description
            addTrailer(trailer)
            this.tags = tags
            this.recommendations = recommendations
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = safeGet(data)?.document ?: return false

        runAllAsync(
            {
                val iframeSrcs =
                    document
                        .select(
                            "div.player-embed iframe, " +
                                "div#pembed iframe, " +
                                "div.iframe-server iframe, " +
                                "div.responsive-embed-container iframe, " +
                                "main iframe[src]",
                        ).mapNotNull {
                            (
                                it.attr("src").takeIf { s -> s.isNotBlank() }
                                    ?: it.attr("data-src").takeIf { s -> s.isNotBlank() }
                                    ?: it.attr("data-litespeed-src").takeIf { s -> s.isNotBlank() }
                                )
                        }.distinct()

                iframeSrcs.amap { src ->
                    runCatching {
                        val resolved =
                            when {
                                src.startsWith("http") -> src
                                src.startsWith("//") -> "https:$src"
                                else -> "$mainUrl$src"
                            }
                        val resolvedCount = java.util.concurrent.atomic.AtomicInteger(0)
                        loadExtractor(resolved, "$mainUrl/", subtitleCallback) { link ->
                            resolvedCount.incrementAndGet()
                            callback.invoke(link)
                        }
                        if (resolvedCount.get() == 0) {
                            val host = runCatching { java.net.URI(resolved).host }
                                .getOrNull()?.removePrefix("www.") ?: name
                            callback.invoke(
                                newExtractorLink(host, host, resolved) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.Unknown.value
                                },
                            )
                        }
                    }
                }
            },
            {
                document.select("div#downloadb li").map { el ->
                    el.select("a").amap {
                        loadFixedExtractor(
                            fixUrl(it.attr("href")),
                            el.select("strong").text(),
                            "$mainUrl/",
                            subtitleCallback,
                            callback,
                        )
                    }
                }
            },
        )

        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        name: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) = coroutineScope {
        loadExtractor(url, referer, subtitleCallback) { link ->
            launch(Dispatchers.IO) {
                callback.invoke(
                    newExtractorLink(
                        link.name,
                        link.name,
                        link.url,
                        link.type,
                    ) {
                        this.referer = link.referer
                        this.quality = name.fixQuality()
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    },
                )
            }
        }
    }

    private fun String.fixQuality(): Int = when (this.uppercase()) {
        "4K" -> Qualities.P2160.value
        "FULLHD" -> Qualities.P1080.value
        "MP4HD" -> Qualities.P720.value
        else -> this.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
    }

    private fun String.removeBloat(): String = this.replace(Regex("(Nonton)|(Anime)|(Subtitle\\sIndonesia)"), "").trim()
}
