package com.hanime1

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

class Hanime1Provider : MainAPI() {
    override var mainUrl = DOMAIN
    override var name = "Hanime1"
    override val hasMainPage = true
    override var lang = "zh"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = hanime1MainPage

    /**
     * Wrapped GET with retries + plain-fallback. Mirrors Nekopoi.safeGet so
     * intermittent Cloudflare blocks or socket resets don't kill a whole
     * MainPage / search request.
     */
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
                    timeout = 30L,
                )
            } catch (t: Throwable) {
                lastError = t
                android.util.Log.e(
                    "Hanime1",
                    "safeGet attempt ${attempt + 1}/$maxRetries failed for $url: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                )
                if (attempt < maxRetries - 1) delay(700L * (attempt + 1))
            }
        }

        // Last-ditch: try without our extra headers (some CF-routes prefer minimal request)
        try {
            android.util.Log.w("Hanime1", "safeGet falling back to plain app.get for $url")
            return app.get(url, timeout = 30L)
        } catch (t: Throwable) {
            android.util.Log.e(
                "Hanime1",
                "safeGet plain fallback failed for $url: ${t.javaClass.simpleName}: ${t.message}",
                t,
            )
        }
        logError(lastError ?: Exception("Hanime1 safeGet failed: $url"))
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val base = request.data
        val pagedUrl =
            when {
                page <= 1 -> base
                base.contains("?") -> "$base&page=$page"
                else -> "$base?page=$page"
            }
        val document =
            safeGet(pagedUrl)?.document
                ?: return newHomePageResponse(
                    list = HomePageList(name = request.name, list = emptyList(), isHorizontalImages = false),
                    hasNext = false,
                )

        val cards =
            document
                .select("div.search-doujin-videos.hidden-xs")
                .ifEmpty { document.select("div.search-doujin-videos") }
                .ifEmpty { document.select("div.home-rows-videos-wrapper > div") }
                .mapNotNull { toSearchResult(it) }

        val results: List<SearchResponse> =
            if (cards.isNotEmpty()) {
                cards
            } else {
                val banners =
                    document
                        .select("div.home-rows-titles-padding, div.home-rows-pic-padding")
                        .mapNotNull { toBannerResult(it) }
                banners.ifEmpty {
                    document
                        .select("a[href*='/watch?v=']")
                        .mapNotNull { it.parent()?.let { p -> toGenericResult(p) } }
                        .distinctBy { it.url }
                }
            }

        val hasNext =
            results.isNotEmpty() &&
                document.selectFirst(
                    "a[rel=next], li.next a, a[aria-label=Next], a:contains(下一頁), a:contains(Next)",
                ) != null

        return newHomePageResponse(
            list =
                HomePageList(
                    name = request.name,
                    list = results,
                    isHorizontalImages = false,
                ),
            hasNext = hasNext,
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val parsed = parseSearchPrefix(query)
        val params = mutableListOf<String>()
        if (parsed.keyword.isNotBlank()) params.add("query=${encodeQuery(parsed.keyword)}")
        if (parsed.key != null && !parsed.value.isNullOrBlank()) {
            params.add("${parsed.key}=${encodeQuery(parsed.value)}")
        }
        val target =
            if (params.isEmpty()) {
                "$mainUrl/search?query=${encodeQuery(query)}"
            } else {
                "$mainUrl/search?${params.joinToString("&")}"
            }
        val doc = safeGet(target)?.document ?: return emptyList()
        return doc
            .select("div.search-doujin-videos.hidden-xs")
            .ifEmpty { doc.select("div.search-doujin-videos") }
            .ifEmpty { doc.select("a[href*='/watch?v=']").mapNotNull { it.parent() } }
            .mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse = parseLoadPage(url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = resolveStreamLinks(data, isCasting, subtitleCallback, callback)
}
