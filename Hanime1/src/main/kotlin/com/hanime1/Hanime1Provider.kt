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
                val res = app.get(
                    url,
                    referer = referer,
                    headers = baseHeaders,
                    timeout = 30L,
                )
                if (res.isSuccessful && !looksLikeChallenge(res.text)) {
                    return res
                }
                android.util.Log.w(
                    "Hanime1",
                    "safeGet attempt ${attempt + 1}/$maxRetries got code=${res.code} " +
                        "len=${res.text.length} challenge=${looksLikeChallenge(res.text)} for $url",
                )
            } catch (t: Throwable) {
                lastError = t
                android.util.Log.e(
                    "Hanime1",
                    "safeGet attempt ${attempt + 1}/$maxRetries failed for $url: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                )
            }
            if (attempt < maxRetries - 1) delay(700L * (attempt + 1))
        }

        // Last-ditch: try without our extra headers (some CF-routes prefer minimal request)
        try {
            android.util.Log.w("Hanime1", "safeGet falling back to plain app.get for $url")
            val res = app.get(url, timeout = 30L)
            if (res.isSuccessful && !looksLikeChallenge(res.text)) return res
            android.util.Log.e(
                "Hanime1",
                "safeGet plain fallback unusable code=${res.code} len=${res.text.length} for $url",
            )
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

    private fun looksLikeChallenge(body: String): Boolean {
        if (body.length < 4096) {
            val lower = body.lowercase()
            if (lower.contains("just a moment") ||
                lower.contains("cf-chl-") ||
                lower.contains("checking your browser") ||
                lower.contains("attention required")
            ) return true
        }
        return false
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
        android.util.Log.d("Hanime1", "search: query='$query' parsed=$parsed -> $target")
        val results = collectSearchResults(target)
        if (results.isNotEmpty()) {
            android.util.Log.d("Hanime1", "search: returning ${results.size} primary results")
            return results
        }

        // Fallback 1: drop any prefix and try the keyword as a plain query.
        if (parsed.key != null) {
            val plain = "$mainUrl/search?query=${encodeQuery(query)}"
            android.util.Log.w("Hanime1", "search: empty primary, retrying plain $plain")
            val plainRes = collectSearchResults(plain)
            if (plainRes.isNotEmpty()) return plainRes
        }

        // Fallback 2: tag-style search for the raw query.
        val tagUrl = "$mainUrl/search?tags%5B%5D=${encodeQuery(query.trim())}"
        android.util.Log.w("Hanime1", "search: empty plain, retrying tag $tagUrl")
        return collectSearchResults(tagUrl)
    }

    private suspend fun collectSearchResults(target: String): List<SearchResponse> {
        val res = safeGet(target)
        if (res == null) {
            android.util.Log.w("Hanime1", "collectSearchResults: safeGet returned null for $target")
            return emptyList()
        }
        val doc = res.document
        val bodyLen = res.text.length
        val cards =
            doc.select("div.search-doujin-videos.hidden-xs")
                .ifEmpty { doc.select("div.search-doujin-videos") }
                .ifEmpty { doc.select("div.home-rows-videos-wrapper > div") }
        val parsed =
            cards
                .mapNotNull { toSearchResult(it) }
                .ifEmpty {
                    doc.select("a[href*='/watch?v=']")
                        .mapNotNull { it.parent()?.let { p -> toGenericResult(p) } }
                }
                .distinctBy { it.url }
        android.util.Log.d(
            "Hanime1",
            "collectSearchResults: code=${res.code} len=$bodyLen cards=${cards.size} " +
                "parsed=${parsed.size} for $target",
        )
        return parsed
    }

    override suspend fun load(url: String): LoadResponse = parseLoadPage(url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = resolveStreamLinks(data, isCasting, subtitleCallback, callback)
}
