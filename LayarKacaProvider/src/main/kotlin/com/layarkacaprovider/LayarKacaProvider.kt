package com.layarkacaprovider

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class LayarKacaProvider : MainAPI() {
    companion object {
        // Single point of domain rotation. Update here when any host moves.
        const val MAIN_DOMAIN = "https://tv10.lk21official.cc"
        const val SERIES_DOMAIN = "https://tv4.nontondrama.my"
        const val SEARCH_API = "https://gudangvape.com"
        const val STATIC_POSTER = "https://static-jpg.lk21.party/wp-content/uploads/"

        val baseHeaders =
            mapOf(
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            )
    }

    override var mainUrl = MAIN_DOMAIN
    private var seriesUrl = SERIES_DOMAIN
    private var searchurl = SEARCH_API

    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
        setOf(
            TvType.Movie,
            TvType.TvSeries,
            TvType.AsianDrama,
        )

    override val mainPage =
        mainPageOf(
            "$mainUrl/populer/page/" to "Film Terplopuler",
            "$mainUrl/rating/page/" to "Film Berdasarkan IMDb Rating",
            "$mainUrl/most-commented/page/" to "Film Dengan Komentar Terbanyak",
            "$seriesUrl/latest-series/page/" to "Series Terbaru",
            "$seriesUrl/series/asian/page/" to "Film Asian Terbaru",
            "$mainUrl/latest/page/" to "Film Upload Terbaru",
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
                )
            } catch (t: Throwable) {
                lastError = t
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(700L * (attempt + 1))
                }
            }
        }
        com.lagradost.cloudstream3.mvvm.logError(
            (lastError ?: Exception("LayarKaca safeGet failed: $url")),
        )
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val document =
            safeGet(request.data + page)?.document
                ?: return newHomePageResponse(request.name, emptyList())
        val home =
            document.select("article figure").mapNotNull {
                it.toSearchResult()
            }
        return newHomePageResponse(request.name, home)
    }

    private suspend fun getProperLink(url: String): String {
        if (url.startsWith(seriesUrl)) return url
        val res = safeGet(url)?.document ?: return url
        return if (res.select("title").text().contains("Nontondrama", true)) {
            res.selectFirst("a#openNow")?.attr("href")
                ?: res.selectFirst("div.links a")?.attr("href")
                ?: url
        } else {
            url
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3")?.ownText()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())
        val type = if (this.selectFirst("span.episode") == null) TvType.Movie else TvType.TvSeries
        val posterheaders = posterUrl?.let { mapOf("Referer" to getBaseUrl(it)) } ?: emptyMap()
        return if (type == TvType.TvSeries) {
            val episode =
                this
                    .selectFirst("span.episode strong")
                    ?.text()
                    ?.filter { it.isDigit() }
                    ?.toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheaders
                addSub(episode)
            }
        } else {
            val quality = this.select("div.quality").text().trim()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheaders
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val refer = safeGet(mainUrl)?.url ?: "$mainUrl/"
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val res = safeGet("$searchurl/search.php?s=$encoded", referer = refer)?.text
            ?: return emptyList()
        val results = mutableListOf<SearchResponse>()

        runCatching {
            val root = JSONObject(res)
            val arr = root.getJSONArray("data")

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val title = item.optString("title").ifBlank { continue }
                val slug = item.optString("slug").ifBlank { continue }
                val type = item.optString("type")
                val posterUrl = STATIC_POSTER + item.optString("poster")
                when (type) {
                    "series" ->
                        results.add(
                            newTvSeriesSearchResponse(title, "$seriesUrl/$slug", TvType.TvSeries) {
                                this.posterUrl = posterUrl
                            },
                        )
                    "movie" ->
                        results.add(
                            newMovieSearchResponse(title, "$mainUrl/$slug", TvType.Movie) {
                                this.posterUrl = posterUrl
                            },
                        )
                }
            }
        }.onFailure { com.lagradost.cloudstream3.mvvm.logError(it) }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val fixUrl = getProperLink(url)
        val document = safeGet(fixUrl)?.document
            ?: throw ErrorLoadingException("LayarKaca page unreachable: $fixUrl")
        val baseurl = fetchURL(fixUrl)
        val title =
            document
                .selectFirst("div.movie-info h1")
                ?.text()
                ?.trim()
                ?: url.substringAfterLast("/").replace("-", " ").trim().ifBlank { "Untitled" }
        val poster = document.select("meta[property=og:image]").attr("content").ifBlank { null }
        val tags = document.select("div.tag-list span").map { it.text() }
        val posterheaders = poster?.let { mapOf("Referer" to getBaseUrl(it)) } ?: emptyMap()

        val year =
            Regex("\\d, (\\d+)")
                .find(
                    document.select("div.movie-info h1").text().trim(),
                )?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        val tvType = if (document.selectFirst("#season-data") != null) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div.meta-info")?.text()?.trim()
        val trailer = document.selectFirst("ul.action-left > li:nth-child(3) > a")?.attr("href")
        val rating = document.selectFirst("div.info-tag strong")?.text()

        val recommendations =
            document.select("li.slider article").mapNotNull { rec ->
                val recName = rec.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                val recHref = baseurl + (rec.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
                val recPosterUrl = fixUrlNull(rec.selectFirst("img")?.attr("src"))
                newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                    this.posterUrl = recPosterUrl
                    this.posterHeaders = posterheaders
                }
            }

        return if (tvType == TvType.TvSeries) {
            val json = document.selectFirst("script#season-data")?.data()
            val episodes = mutableListOf<Episode>()
            if (json != null) {
                val root = JSONObject(json)
                root.keys().forEach { seasonKey ->
                    val seasonArr = root.getJSONArray(seasonKey)
                    for (i in 0 until seasonArr.length()) {
                        val ep = seasonArr.getJSONObject(i)
                        val href = fixUrl("$baseurl/" + ep.getString("slug"))
                        val episodeNo = ep.optInt("episode_no")
                        val seasonNo = ep.optInt("s")
                        episodes.add(
                            newEpisode(href) {
                                this.name = "Episode $episodeNo"
                                this.season = seasonNo
                                this.episode = episodeNo
                            },
                        )
                    }
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.posterHeaders = posterheaders
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = runCatching { Score.from10(rating) }.getOrNull()
                this.recommendations = recommendations
                runCatching { addTrailer(trailer) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = posterheaders
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = runCatching { Score.from10(rating) }.getOrNull()
                this.recommendations = recommendations
                runCatching { addTrailer(trailer) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ua =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        val headers = mapOf("User-Agent" to ua)
        val pageDoc = app.get(data, referer = "$mainUrl/", headers = headers).document

        val iframes =
            pageDoc.select(
                "div.embed-container iframe, div.player iframe, div#player iframe, " +
                    "iframe[src], iframe[data-src], iframe[data-litespeed-src]",
            ).mapNotNull { el ->
                (
                    el.attr("data-src").takeIf { it.isNotBlank() }
                        ?: el.attr("data-litespeed-src").takeIf { it.isNotBlank() }
                        ?: el.attr("src").takeIf { it.isNotBlank() }
                    )
            }.map { raw ->
                when {
                    raw.startsWith("http") -> raw
                    raw.startsWith("//") -> "https:$raw"
                    else -> "$mainUrl$raw"
                }
            }.distinct()

        if (iframes.isEmpty()) {
            // last-ditch: try the page url itself as a passthrough
            callback.invoke(
                newExtractorLink(name, name, data) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                },
            )
            return true
        }

        coroutineScope {
            iframes.forEach { src ->
                launch {
                    val resolvedCount = AtomicInteger(0)
                    runCatching {
                        loadExtractor(src, "$mainUrl/", subtitleCallback) { link ->
                            resolvedCount.incrementAndGet()
                            callback.invoke(link)
                        }
                    }
                    if (resolvedCount.get() == 0) {
                        val host = runCatching { URI(src).host }
                            .getOrNull()?.removePrefix("www.") ?: name
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
        return true
    }

    private suspend fun String.getIframe(): String = app
        .get(this, referer = "$seriesUrl/")
        .document
        .select("div.embed-container iframe")
        .attr("src")

    private suspend fun fetchURL(url: String): String {
        val res = app.get(url, allowRedirects = false)
        val href = res.headers["location"]

        return if (href != null) {
            val it = URI(href)
            "${it.scheme}://${it.host}"
        } else {
            url
        }
    }

    private fun Element.getImageAttr(): String = when {
        this.hasAttr("src") -> this.attr("src")
        this.hasAttr("data-src") -> this.attr("data-src")
        else -> this.attr("src")
    }

    fun getBaseUrl(url: String?): String = URI(url).let {
        "${it.scheme}://${it.host}"
    }
}
