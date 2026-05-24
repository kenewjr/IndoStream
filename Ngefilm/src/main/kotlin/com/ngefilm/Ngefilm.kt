package com.ngefilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Ngefilm : MainAPI() {

    override var mainUrl = "https://new35.ngefilm.site"

    override var name = "Ngefilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    // [FIX]: format URL - parameter advanced search yang panjang sering broken.
    // Pakai URL kategori sederhana yang lebih reliable.
    override val mainPage = mainPageOf(
        "/page/%d/" to "Latest",
        "/genre/action/page/%d/" to "Action",
        "/genre/drama/page/%d/" to "Drama",
        "/genre/comedy/page/%d/" to "Comedy",
        "/genre/horror/page/%d/" to "Horror",
        "/genre/thriller/page/%d/" to "Thriller",
        "/category/movies/page/%d/" to "Movies",
        "/category/series/page/%d/" to "Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // [FIX]: implementasi getMainPage — sebelumnya tidak ada → throw
        // NotImplementedError ("This operation is not implemented").
        return try {
            val url = mainUrl + request.data.format(page)
            val document = app.get(url).document
            // [UPDATED SELECTOR]: theme Gomovies WordPress menggunakan
            // article.item-infinite atau article.item.
            val home = document.select("article.item-infinite, article.item")
                .mapNotNull { it.toSearchResult() }
            newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title a, h2 a, .entry-title a")?.text()?.trim()
            ?: return null
        val href = this.selectFirst("a[href]")?.attr("href")?.let { fixUrl(it) }
            ?: return null
        val poster = this.selectFirst("img")?.let {
            it.attr("data-src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("src")
        }?.let { fixUrlNull(it) }
        val quality = this.selectFirst(".gmr-quality-item, .gmr-qual")?.text()?.trim().orEmpty()
        return if (quality.isNotEmpty()) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                addQuality(quality)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // [FIX]: implementasi search.
        return try {
            val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
            document.select("article.item-infinite, article.item").mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // [FIX]: implementasi load — sebelumnya tidak ada.
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
        // [FIX]: implementasi loadLinks — sebelumnya tidak ada → throw
        // NotImplementedError saat user mencoba memutar.
        return try {
            val document = app.get(data).document
            val iframes = document.select("iframe[src], iframe[data-litespeed-src]")
                .mapNotNull {
                    (it.attr("data-litespeed-src").takeIf { s -> s.isNotBlank() }
                        ?: it.attr("src").takeIf { s -> s.isNotBlank() })
                }
                .map { if (it.startsWith("http")) it else "https:$it" }
                .distinct()

            iframes.amap { iframe ->
                runCatching {
                    loadExtractor(iframe, mainUrl, subtitleCallback, callback)
                }
            }
            iframes.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}
