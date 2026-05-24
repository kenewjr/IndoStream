package com.dubbindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class Dubbindo : MainAPI() {
    override var mainUrl = "https://www.dubbindo.site"
    override var name = "Dubbindo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes =
        setOf(
            TvType.TvSeries,
            TvType.Movie,
            TvType.Cartoon,
            TvType.Anime,
        )

    override val mainPage =
        mainPageOf(
            "$mainUrl/videos/category/1" to "Movie",
            "$mainUrl/videos/category/3" to "TV Series",
            "$mainUrl/videos/category/5" to "Anime Series",
            "$mainUrl/videos/category/4" to "Anime Movie",
            "$mainUrl/videos/category/other" to "Other",
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val document = app.get("${request.data}?page_id=$page").document
        val home =
            document
                .select("div.videos-latest-list.pt_timeline_vids div.video-wrapper")
                .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = true,
        )
    }

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val title = this.selectFirst("h4,div.video-title")?.text()?.trim() ?: ""
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..10) {
            val document =
                app
                    .get(
                        "$mainUrl/search?keyword=$query&page_id=$i",
                    ).document
            val results =
                document.select("div.videos-latest-list.row div.video-wrapper").mapNotNull {
                    it.toSearchResult()
                }
            searchResponse.addAll(results)
            if (results.isEmpty()) break
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.video-big-title h1")?.text() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val tags = document.select("div.pt_categories li a").map { it.text() }
        val description = document.select("div.watch-video-description p").text()
        val recommendations =
            document.select("div.related-video-wrapper").mapNotNull { it.toSearchResult() }

        val staticSources =
            document
                .select("video#my-video source, video source")
                .map {
                    Video(
                        it.attr("src").takeIf { s -> s.isNotBlank() },
                        it.attr("res").takeIf { s -> s.isNotBlank() }
                            ?: it.attr("size").takeIf { s -> s.isNotBlank() }
                            ?: it.attr("data-quality"),
                        it.attr("type"),
                    )
                }.filter { it.src != null }

        val dynamicSources =
            if (staticSources.isEmpty()) {
                val scriptHtml = document.select("script").joinToString("\n") { it.data() }
                val updateSrcRegex =
                    Regex(
                        """updateSrc\s*\(\s*\[(.*?)\]\s*\)""",
                        setOf(RegexOption.DOT_MATCHES_ALL),
                    )
                val arrayBody =
                    updateSrcRegex
                        .find(scriptHtml)
                        ?.groupValues
                        ?.getOrNull(1)
                        .orEmpty()
                val objectRegex = Regex("""\{[^{}]*}""", RegexOption.DOT_MATCHES_ALL)
                val srcRegex = Regex("""src\s*:\s*['"]([^'"]+)['"]""")
                val typeRegex = Regex("""type\s*:\s*['"]([^'"]+)['"]""")
                val resRegex = Regex("""res\s*:\s*['"]?(\d+)['"]?""")
                objectRegex
                    .findAll(arrayBody)
                    .mapNotNull { match ->
                        val obj = match.value
                        val src = srcRegex.find(obj)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                        Video(
                            src = src,
                            res = resRegex.find(obj)?.groupValues?.getOrNull(1),
                            type = typeRegex.find(obj)?.groupValues?.getOrNull(1),
                        )
                    }.toList()
            } else {
                emptyList()
            }

        val video = (staticSources + dynamicSources).distinctBy { it.src }

        return newMovieLoadResponse(title, url, TvType.Movie, video.toJson()) {
            posterUrl = poster
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val videos = tryParseJson<List<Video>>(data) ?: emptyList()
            var success = false
            videos.forEach { video ->
                val src = video.src?.takeIf { it.isNotBlank() } ?: return@forEach
                if (video.type == "video/mp4" ||
                    video.type == "video/x-msvideo" ||
                    video.type == "video/x-matroska" ||
                    src.endsWith(".mp4", true) ||
                    src.endsWith(".mkv", true)
                ) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            src,
                        ) {
                            this.referer = "$mainUrl/"

                            this.quality = video.res?.toIntOrNull()
                                ?: Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
                                    .find(src)
                                    ?.groupValues
                                    ?.getOrNull(1)
                                    ?.toIntOrNull()
                                ?: Qualities.Unknown.value
                        },
                    )
                    success = true
                } else {
                    runCatching {
                        loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                        success = true
                    }
                }
            }
            success
        } catch (e: Exception) {
            false
        }
    }

    data class Video(
        val src: String? = null,
        val res: String? = null,
        val type: String? = null,
    )
}
