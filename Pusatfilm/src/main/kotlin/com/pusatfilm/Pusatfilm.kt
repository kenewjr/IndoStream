package com.pusatfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URI
import org.jsoup.nodes.Element

class Pusatfilm : MainAPI() {

    override var mainUrl = "https://v3.pusatfilm21info.com"

    override var name = "Pusatfilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage =
            mainPageOf(
                    "film-terbaru/page/%d/" to "Film Terbaru",
                    "trending/page/%d/" to "Film Trending",
                    "genre/action/page/%d/" to "Film Action",
                    "series-terbaru/page/%d/" to "Series Terbaru",
                    "drama-korea/page/%d/" to "Drama Korea",
                    "west-series/page/%d/" to "West Series",
                    "drama-china/page/%d/" to "Drama China",
            )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data.format(page)
        val document = app.get("$mainUrl/$data").document
        val home = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality =
            this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        return if (quality.isEmpty()) {
            val episode =
                Regex("Episode\\s?([0-9]+)")
                    .find(title)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: this.select("div.gmr-numbeps > span").text().toIntOrNull()
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(episode)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document =
            app.get("${mainUrl}?s=$query&post_type[]=post&post_type[]=tv", timeout = 50L)
                .document
        val results = document.select("article.item").mapNotNull { it.toSearchResult() }
        return results
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst("a > span.idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr().fixImageQuality())
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun load(url: String): LoadResponse {
        // [FIX]: Pusatfilm sebelumnya memanggil super.load(url) yang akan
        // throw NotImplementedError karena MainAPI tidak punya implementasi
        // default. Kita implementasi load() langsung di sini.
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: ""
        val poster = fixUrlNull(
            document.selectFirst("figure.pull-left img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val description = document.select("div.entry-content > p").joinToString("\n") { it.text() }
        val tags = document.select("a[href*=/genre/]").map { it.text() }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        // Detect tipe konten dari ada/tidaknya episode list
        val episodeAnchors = document.select("div.vid-episodes a, div.gmr-listseries a")
        val isSeries = episodeAnchors.isNotEmpty()

        return if (isSeries) {
            val episodes = episodeAnchors.mapNotNull { eps ->
                val href = fixUrl(eps.attr("href"))
                val name = eps.attr("title").ifBlank { eps.text() }
                val episode = Regex("Episode\\s*(\\d+)").find(name)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                val season = Regex("Season\\s*(\\d+)").find(name)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (episode == null) null
                else newEpisode(href) {
                    this.name = name
                    this.season = season
                    this.episode = episode
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // [FIX]: implementasi minimal — ambil semua iframe di halaman dan
        // serahkan ke loadExtractor. Sebelumnya tidak ada implementasi sama
        // sekali sehingga pemutaran throw "operation not implemented".
        return try {
            val document = app.get(data).document
            val iframes = document.select("iframe[src], iframe[data-litespeed-src]")
                .mapNotNull {
                    (it.attr("data-litespeed-src").takeIf { s -> s.isNotBlank() }
                        ?: it.attr("src").takeIf { s -> s.isNotBlank() })
                }
                .distinct()
            iframes.amap { iframe ->
                runCatching {
                    val src = if (iframe.startsWith("http")) iframe
                    else "https:$iframe"
                    com.lagradost.cloudstream3.utils.loadExtractor(src, mainUrl, subtitleCallback, callback)
                }
            }
            iframes.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
            ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }

}
