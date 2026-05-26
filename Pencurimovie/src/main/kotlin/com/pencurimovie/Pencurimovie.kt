package com.pencurimovie

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Pencurimovie : MainAPI() {
    companion object {
        // Single point of domain rotation. Update here when the site moves.
        const val DOMAIN = "https://ww11.pencurimovie.sbs"

        val baseHeaders =
            mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            )
    }

    override var mainUrl = DOMAIN
    override var name = "Pencurimovie"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime, TvType.Cartoon)

    override val mainPage =
        mainPageOf(
            "movies" to "Latest Movies",
            "series" to "TV Series",
            "most-rating" to "Most Rating Movies",
            "top-imdb" to "Top IMDB Movies",
            "country/malaysia" to "Malaysia Movies",
            "country/indonesia" to "Indonesia Movies",
            "country/india" to "India Movies",
            "country/japan" to "Japan Movies",
            "country/thailand" to "Thailand Movies",
            "country/china" to "China Movies",
        )

    /**
     * Wraps app.get with up to [maxRetries] attempts, uniform headers, and a 30s timeout.
     * Returns null on exhaustion so callsites stay null-safe instead of throwing.
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
            (lastError ?: Exception("Pencurimovie safeGet failed: $url")),
        )
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val document =
            safeGet("$mainUrl/${request.data}/page/$page")?.document
                ?: return newHomePageResponse(
                    list = HomePageList(name = request.name, list = emptyList(), isHorizontalImages = false),
                    hasNext = false,
                )
        val home = document.select("div.ml-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = false),
            hasNext = true,
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("a").attr("oldtitle").substringBefore("(")
        val href = fixUrl(this.select("a").attr("href"))
        val posterUrl = fixUrlNull(this.select("a img").attr("data-original"))
        val quality = getQualityFromString(this.select("span.mli-quality").text())
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = quality
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = safeGet("$mainUrl/?s=$encoded")?.document ?: return emptyList()
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = safeGet(url)?.document
            ?: throw ErrorLoadingException("Pencurimovie page unreachable: $url")
        val title =
            document
                .selectFirst("div.mvic-desc h3")
                ?.text()
                ?.trim()
                .toString()
                .substringBefore("(")
        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.selectFirst("div.desc p.f-desc")?.text()?.trim()
        val tvtag = if (url.contains("series")) TvType.TvSeries else TvType.Movie
        val trailer = document.select("meta[itemprop=embedUrl]").attr("content")
        val genre = document.select("div.mvic-info p:contains(Genre)").select("a").map { it.text() }
        val actors =
            document.select("div.mvic-info p:contains(Actors)").select("a").map { it.text() }
        val year =
            document
                .select("div.mvic-info p:contains(Release)")
                .select("a")
                .text()
                .toIntOrNull()
        val recommendation = document.select("div.ml-item").mapNotNull { it.toSearchResult() }
        return if (tvtag == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div.tvseason").amap { info ->
                val season =
                    info
                        .select("strong")
                        .text()
                        .substringAfter("Season")
                        .trim()
                        .toIntOrNull()
                info.select("div.les-content a").forEach {
                    Log.d("Phis", "$it")
                    val name =
                        it
                            .select("a")
                            .text()
                            .substringAfter("-")
                            .trim()
                    val href = it.select("a").attr("href")
                    val rawepisode =
                        it
                            .select("a")
                            .text()
                            .substringAfter("Episode")
                            .substringBefore("-")
                            .trim()
                            .toIntOrNull()
                    episodes.add(
                        newEpisode(data = href) {
                            this.episode = rawepisode
                            this.name = name
                            this.season = season
                        },
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                addTrailer(trailer)
                addActors(actors)
                this.recommendations = recommendation
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                addTrailer(trailer)
                addActors(actors)
                this.recommendations = recommendation
            }
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
            document.select("div.movieplay iframe, div#mv-info iframe, iframe[src], iframe[data-src]")
                .mapNotNull { el ->
                    (el.attr("data-src").takeIf { it.isNotBlank() }
                        ?: el.attr("src").takeIf { it.isNotBlank() })
                }
                .map { raw ->
                    when {
                        raw.startsWith("http") -> raw
                        raw.startsWith("//") -> "https:$raw"
                        else -> "$mainUrl$raw"
                    }
                }
                .distinct()

        if (iframes.isEmpty()) return false

        iframes.amap { src ->
            val resolvedCount = java.util.concurrent.atomic.AtomicInteger(0)
            runCatching {
                loadExtractor(src, "$mainUrl/", subtitleCallback) { link ->
                    resolvedCount.incrementAndGet()
                    callback.invoke(link)
                }
            }
            if (resolvedCount.get() == 0) {
                val host = runCatching { java.net.URI(src).host }
                    .getOrNull()?.removePrefix("www.") ?: name
                callback.invoke(
                    newExtractorLink(host, host, src) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    },
                )
            }
        }
        return true
    }
}
