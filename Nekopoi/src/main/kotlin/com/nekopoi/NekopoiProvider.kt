package com.nekopoi

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay

class Nekopoi : MainAPI() {
    override var mainUrl = DOMAIN
    override var name = "Nekopoi"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = nekopoiMainPage

    internal suspend fun safeGet(
        url: String,
        referer: String? = "$mainUrl/",
        maxRetries: Int = 3,
    ): NiceResponse? {
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
                android.util.Log.e(
                    "Nekopoi",
                    "safeGet headered attempt ${attempt + 1}/$maxRetries failed for $url: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                )
                if (attempt < maxRetries - 1) {
                    delay(700L * (attempt + 1))
                }
            }
        }

        try {
            android.util.Log.w("Nekopoi", "safeGet falling back to plain app.get for $url")
            val plain = app.get(url)
            android.util.Log.d(
                "Nekopoi",
                "safeGet plain fallback SUCCEEDED for $url (status=${plain.code})",
            )
            return plain
        } catch (t: Throwable) {
            android.util.Log.e(
                "Nekopoi",
                "safeGet plain fallback also failed for $url: " +
                    "${t.javaClass.simpleName}: ${t.message}",
                t,
            )
        }

        logError(lastError ?: Exception("Nekopoi safeGet failed: $url"))
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val base = request.data.trimEnd('/')
        val document =
            safeGet("$base/page/$page/")?.document
                ?: return newHomePageResponse(
                    list = HomePageList(name = request.name, list = emptyList(), isHorizontalImages = false),
                    hasNext = false,
                )
        val searchItems =
            document
                .select("div.nk-search-results ul li")
                .ifEmpty { document.select("div.nk-search-results li") }
                .ifEmpty {
                    document.select("div.result-list a[href], div.nk-result-list a[href], section.hasil a[href]")
                }.mapNotNull { toSearchResult(it) }

        val (home, isHorizontal) =
            if (searchItems.isNotEmpty()) {
                searchItems to false
            } else {
                val postCardItems =
                    document
                        .select(
                            "div.nk-episodes-area #nk-episode-grid div.nk-post-card, " +
                                "#nk-episode-grid div.nk-post-card, " +
                                "div.nk-episodes-area div.nk-post-card",
                        ).mapNotNull { toPostCardResult(it) }
                if (postCardItems.isNotEmpty()) {
                    postCardItems to true
                } else {
                    val gridItems =
                        document
                            .select("li:has(div.nk-grid-thumb)")
                            .mapNotNull { toGridThumbResult(it) }
                    if (gridItems.isNotEmpty()) {
                        gridItems to true
                    } else {
                        val broadItems =
                            document
                                .select("li:has(h2 a[href]), li:has(a[href] h2), article:has(h2 a[href])")
                                .mapNotNull { toBroadResult(it) }
                        broadItems to true
                    }
                }
            }

        val hasNext =
            home.isNotEmpty() &&
                document.selectFirst("a.next.page-numbers, .nav-links a.next, a:contains(Selanjutnya)") != null

        return newHomePageResponse(
            list =
                HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = isHorizontal,
                ),
            hasNext = hasNext,
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val (path, residue) = parseSearchPrefix(query)
        val encoded = java.net.URLEncoder.encode(residue, "UTF-8")
        val target =
            if (path != null) {
                "$mainUrl$path?s=$encoded"
            } else {
                "$mainUrl/?s=$encoded&post_type=anime"
            }
        val doc = safeGet(target)?.document ?: return emptyList()
        return doc
            .select("div.nk-search-results ul li")
            .ifEmpty { doc.select("div.nk-search-results li") }
            .ifEmpty {
                doc.select("div.result-list a[href], div.nk-result-list a[href], section.hasil a[href]")
            }.mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse = parseLoadPage(url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = resolveStreamLinks(data, isCasting, subtitleCallback, callback)
}
