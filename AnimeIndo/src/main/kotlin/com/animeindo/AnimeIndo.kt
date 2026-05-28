package com.animeindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicInteger

class AnimeIndo : MainAPI() {
    override var mainUrl = DOMAIN
    override var name = "AnimeIndo"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        // Single point of domain rotation. Update here when the site moves.
        const val DOMAIN = "https://gomunime.top"

        val baseHeaders =
            mapOf(
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            )

        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime
            return when {
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("ONA", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        // Kototoro R8 may strip enum values; runCatching + nullable return guards.
        fun getStatus(t: String?): ShowStatus? = runCatching {
            if (t == null) return@runCatching null
            when {
                t.contains("Ongoing", true) || t.contains("Currently", true) ->
                    ShowStatus.Ongoing
                t.contains("Completed", true) || t.contains("Finished", true) ->
                    ShowStatus.Completed
                else -> null
            }
        }.getOrNull()
    }

    override val mainPage =
        mainPageOf(
            "$mainUrl/status/ongoing" to "Anime Ongoing",
            "$mainUrl/status/completed" to "Anime Tamat",
            "$mainUrl/type/movie" to "Movies",
            "$mainUrl/koleksi/anime-skor-mal-tertinggi" to "Rating Tertinggi",
            "$mainUrl/genre/action" to "Genre: Action",
            "$mainUrl/genre/adventure" to "Genre: Adventure",
            "$mainUrl/genre/fantasy" to "Genre: Fantasy",
            "$mainUrl/genre/comedy" to "Genre: Comedy",
            "$mainUrl/genre/romance" to "Genre: Romance",
            "$mainUrl/genre/isekai" to "Genre: Isekai",
            "$mainUrl/genre/shounen" to "Genre: Shounen",
            "$mainUrl/genre/school" to "Genre: School",
            "$mainUrl/genre/drama" to "Genre: Drama",
            "$mainUrl/genre/supernatural" to "Genre: Supernatural",
        )

    /**
     * Wraps app.get with up to [maxRetries] attempts, uniform headers, and a 30s timeout.
     * Returns null on exhaustion so callsites stay null-safe.
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
                )
            } catch (t: Throwable) {
                lastError = t
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(700L * (attempt + 1))
                }
            }
        }
        com.lagradost.cloudstream3.mvvm.logError(
            (lastError ?: Exception("AnimeIndo safeGet failed: $url")),
        )
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document =
            safeGet(url)?.document
                ?: return newHomePageResponse(request.name, emptyList())

        val home = document.select("a.card-netflix").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val link = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val href = if (link.startsWith("http")) link else "$mainUrl${link.removePrefix("/").let { "/$it" }}"
        val title =
            this.selectFirst("h3")?.text()?.trim()
                ?: this.selectFirst("img")?.attr("alt")?.trim()
                ?: return null
        val posterUrl =
            this.selectFirst("img")?.let { img ->
                img.attr("src").takeIf { it.isNotBlank() }
                    ?: img.attr("data-src").takeIf { it.isNotBlank() }
            }

        val epNum =
            this
                .selectFirst("[class*=badge]")
                ?.text()
                ?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = safeGet("$mainUrl/search?q=$encoded")?.document ?: return emptyList()
        return document.select("a.card-netflix").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = safeGet(url)?.document ?: return null

        val title =
            document
                .selectFirst("h1")
                ?.text()
                ?.trim()
                ?.removeSuffix("Subtitle Indonesia")
                ?.trim()
                ?: return null

        val poster =
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img[alt][src*=poster]")?.attr("src")
                ?: document.selectFirst("article img")?.attr("src")

        fun getMetaValue(label: String): String? = document
            .select("dl div")
            .firstOrNull { div ->
                div
                    .selectFirst("dt")
                    ?.text()
                    ?.trim()
                    ?.equals(label, ignoreCase = true) == true
            }?.selectFirst("dd")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags =
            document
                .select("a[href*=/genre/]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() && it.length < 30 }
                .distinct()
                .takeIf { it.isNotEmpty() }

        val typeText =
            document
                .select("span")
                .map { it.text().trim() }
                .firstOrNull { it.uppercase() in listOf("TV", "MOVIE", "ONA", "OVA", "SPECIAL") }
        val type = getType(typeText)

        val statusText =
            document
                .select("span")
                .map { it.text().trim() }
                .firstOrNull { it.uppercase() in listOf("ONGOING", "COMPLETED", "TBA") }
        val status = getStatus(statusText)

        val year =
            document
                .selectFirst("a[href*=/tahun/]")
                ?.text()
                ?.trim()
                ?.toIntOrNull()
                ?: document
                    .select("span")
                    .map { it.text().trim() }
                    .firstOrNull { it.matches(Regex("(19|20)\\d{2}")) }
                    ?.toIntOrNull()

        val duration =
            getMetaValue("Durasi")
                ?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }

        val score =
            document.text().let { full ->
                Regex("★\\s*(\\d+(?:\\.\\d+)?)").find(full)?.groupValues?.get(1)
            }

        val description =
            document.selectFirst("div.prose")?.text()?.trim()
                ?: document.selectFirst("section:has(h2:contains(Sinopsis)) div")?.text()?.trim()
                ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val episodes =
            document
                .select("section#episode-list a[href]")
                .ifEmpty { document.select("a[href]:has(div:matchesOwn(^Episode\\s*\\d+))") }
                .mapNotNull { ep ->
                    val epHref = ep.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val epLink = if (epHref.startsWith("http")) epHref else "$mainUrl${epHref.removePrefix("/").let { "/$it" }}"
                    val epTitle =
                        ep.selectFirst("div")?.text()?.trim()
                            ?: ep
                                .text()
                                .trim()
                                .substringBefore("\n")
                                .trim()
                    val epNum =
                        Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                            .find(epTitle)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                            ?: epTitle.filter { it.isDigit() }.toIntOrNull()
                    newEpisode(epLink) {
                        this.name = epTitle
                        this.episode = epNum
                    }
                }.distinctBy { it.data }
                .reversed()

        val recommendations =
            document
                .select("section:has(h2:contains(Anime Mirip)) a.card-netflix")
                .ifEmpty { document.select("section:has(h2:contains(Mirip)) a[href]") }
                .mapNotNull { it.toSearchResult() }
                .take(20)

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            this.duration = duration
            // addScore is an extension on LoadResponse.Companion; Kototoro's R8 may strip it.
            score?.let { runCatching { addScore(it) } }
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
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

        val iframes =
            document
                .select("iframe[src]")
                .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
                .distinct()

        if (iframes.isEmpty()) return false

        coroutineScope {
            iframes.forEach { rawSrc ->
                launch {
                    val src = httpsify(rawSrc)
                    runCatching {
                        when {
                            src.contains("pixeldrain.com", ignoreCase = true) -> {
                                val id =
                                    Regex("pixeldrain\\.com/u/([\\w-]+)")
                                        .find(src)
                                        ?.groupValues
                                        ?.get(1)
                                if (id != null) {
                                    callback.invoke(
                                        newExtractorLink(
                                            "Pixeldrain",
                                            "Pixeldrain",
                                            "https://pixeldrain.com/api/file/$id?download",
                                        ) {
                                            this.referer = "$mainUrl/"
                                            this.quality = Qualities.Unknown.value
                                        },
                                    )
                                }
                            }

                            else -> {
                                val resolvedCount = AtomicInteger(0)
                                loadExtractor(src, "$mainUrl/", subtitleCallback) { link ->
                                    resolvedCount.incrementAndGet()
                                    callback.invoke(link)
                                }
                                if (resolvedCount.get() == 0) {
                                    val host =
                                        runCatching { java.net.URI(src).host }
                                            .getOrNull()
                                            ?.removePrefix("www.") ?: "Embed"
                                    callback.invoke(
                                        newExtractorLink(host, host, src) {
                                            this.referer = "$mainUrl/"
                                            this.quality = Qualities.Unknown.value
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        return true
    }
}
