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
     * PROXY FIX: Hanime1.me returns HTTP 403 (no CF challenge) on direct
     * requests from many mobile/residential IPs. We chain four strategies:
     *   1. Direct GET with full browser headers + retries.
     *   2. Public CORS/reverse proxies (allorigins, corsproxy.io, ...).
     *   3. User-deployed Cloudflare Worker (if [CF_WORKER_URL] is set).
     *   4. User-configured HTTP/SOCKS proxy (if [USER_PROXY_HOST] is set).
     *
     * Returns the first response that looks usable (HTTP 200..399, body length
     * > a small threshold, no CF JS-challenge marker) or null on full failure.
     */
    internal suspend fun safeGet(
        url: String,
        referer: String? = "$mainUrl/",
        maxRetries: Int = 3,
    ): NiceResponse? {
        // METHOD 1 — direct request with browser-flavored headers.        // PROXY FIX
        safeGetDirect(url, referer, maxRetries)?.let { return it }         // PROXY FIX

        // METHOD 2 — public CORS/reverse proxy fan-out.                   // PROXY FIX
        android.util.Log.w("Hanime1", "safeGet direct failed, trying public proxies for $url")
        safeGetViaProxy(url)?.let { return it }                            // PROXY FIX

        // METHOD 3 — user's Cloudflare Worker proxy.                      // PROXY FIX
        if (CF_WORKER_URL.isNotBlank()) {                                  // PROXY FIX
            android.util.Log.w("Hanime1", "safeGet proxies failed, trying CF Worker for $url")
            safeGetViaWorker(url)?.let { return it }                       // PROXY FIX
        }                                                                   // PROXY FIX

        // METHOD 4 — user-configured HTTP/SOCKS proxy.                    // PROXY FIX
        if (USER_PROXY_HOST.isNotBlank() && USER_PROXY_PORT > 0) {         // PROXY FIX
            android.util.Log.w("Hanime1", "safeGet CF Worker failed, trying HTTP proxy for $url")
            safeGetViaHttpProxy(url, referer)?.let { return it }           // PROXY FIX
        }                                                                   // PROXY FIX

        android.util.Log.e("Hanime1", "safeGet ALL methods failed: $url") // PROXY FIX
        return null
    }

    /** PROXY FIX: Original direct-with-retries path, now extracted as a helper. */
    private suspend fun safeGetDirect(
        url: String,
        referer: String?,
        maxRetries: Int,
    ): NiceResponse? {
        repeat(maxRetries) { attempt ->
            try {
                val res = app.get(
                    url,
                    referer = referer,
                    headers = baseHeaders,
                    timeout = 30L,
                )
                if (res.code in 200..399 && !looksLikeChallenge(res.text)) {
                    android.util.Log.d("Hanime1", "safeGet direct OK code=${res.code} for $url")
                    return res
                }
                android.util.Log.w(
                    "Hanime1",
                    "safeGet direct attempt ${attempt + 1}/$maxRetries got code=${res.code} " +
                        "len=${res.text.length} challenge=${looksLikeChallenge(res.text)} for $url",
                )
            } catch (t: Throwable) {
                android.util.Log.e(
                    "Hanime1",
                    "safeGet direct attempt ${attempt + 1}/$maxRetries failed for $url: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                )
            }
            if (attempt < maxRetries - 1) delay(700L * (attempt + 1))
        }
        return null
    }

    /**
     * PROXY FIX (Method 2): Try each public proxy in [proxyList] in order. The
     * target URL is URL-encoded and appended; we accept the response only if it
     * looks like real hanime1 HTML (>500 bytes, not a challenge page).
     */
    private suspend fun safeGetViaProxy(url: String): NiceResponse? {
        val encoded = java.net.URLEncoder.encode(url, "UTF-8")
        for (proxy in proxyList) {
            val proxyUrl = "$proxy$encoded"
            try {
                val res = app.get(proxyUrl, headers = proxyHeaders, timeout = 20L)
                if (res.code == 200 &&
                    res.text.length > 500 &&
                    !looksLikeChallenge(res.text)
                ) {
                    android.util.Log.d("Hanime1", "safeGet proxy OK: $proxy for $url")
                    return res
                }
                android.util.Log.w(
                    "Hanime1",
                    "safeGet proxy code=${res.code} len=${res.text.length}: $proxy",
                )
            } catch (t: Throwable) {
                android.util.Log.w(
                    "Hanime1",
                    "safeGet proxy error: $proxy → ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }
        return null
    }

    /**
     * PROXY FIX (Method 3): Forward through the user's Cloudflare Worker.
     * Worker contract: GET {CF_WORKER_URL}?url=<encoded hanime1 url> returns
     * the upstream response body verbatim (see Hanime1/cloudflare-worker.js).
     */
    private suspend fun safeGetViaWorker(url: String): NiceResponse? {
        if (CF_WORKER_URL.isBlank()) return null
        return try {
            val workerUrl = "$CF_WORKER_URL?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
            val res = app.get(
                workerUrl,
                headers = mapOf(
                    "User-Agent" to
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                ),
                timeout = 25L,
            )
            if (res.code == 200 &&
                res.text.length > 500 &&
                !looksLikeChallenge(res.text)
            ) {
                android.util.Log.d("Hanime1", "safeGet CF Worker OK for $url")
                res
            } else {
                android.util.Log.w(
                    "Hanime1",
                    "safeGet CF Worker code=${res.code} len=${res.text.length} for $url",
                )
                null
            }
        } catch (t: Throwable) {
            android.util.Log.e(
                "Hanime1",
                "safeGet CF Worker failed for $url: ${t.javaClass.simpleName}: ${t.message}",
            )
            null
        }
    }

    /**
     * PROXY FIX (Method 4): Route the request through a user-configured HTTP
     * or SOCKS proxy. We build a short-lived OkHttpClient with the proxy and
     * adapt the okhttp3.Response into a NiceResponse so callers stay agnostic.
     */
    private suspend fun safeGetViaHttpProxy(
        url: String,
        referer: String?,
    ): NiceResponse? {
        if (USER_PROXY_HOST.isBlank() || USER_PROXY_PORT <= 0) return null
        return try {
            val proxyType =
                if (USER_PROXY_IS_SOCKS) java.net.Proxy.Type.SOCKS
                else java.net.Proxy.Type.HTTP
            val proxy = java.net.Proxy(
                proxyType,
                java.net.InetSocketAddress(USER_PROXY_HOST, USER_PROXY_PORT),
            )
            val client = okhttp3.OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            val requestBuilder = okhttp3.Request.Builder().url(url).get()
            baseHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }
            if (!referer.isNullOrBlank()) requestBuilder.header("Referer", referer)

            val raw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(requestBuilder.build()).execute()
            }
            try {
                val nice = com.lagradost.nicehttp.NiceResponse(raw, parser = null)
                if (raw.code in 200..399 &&
                    nice.text.length > 500 &&
                    !looksLikeChallenge(nice.text)
                ) {
                    android.util.Log.d(
                        "Hanime1",
                        "safeGet HTTP proxy OK code=${raw.code} for $url",
                    )
                    nice
                } else {
                    android.util.Log.w(
                        "Hanime1",
                        "safeGet HTTP proxy code=${raw.code} len=${nice.text.length} for $url",
                    )
                    null
                }
            } catch (t: Throwable) {
                raw.close()
                throw t
            }
        } catch (t: Throwable) {
            android.util.Log.e(
                "Hanime1",
                "safeGet HTTP proxy failed for $url: ${t.javaClass.simpleName}: ${t.message}",
            )
            null
        }
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

    @Volatile private var sessionWarmed = false

    /**
     * Hanime1.me 403's the very first request from a fresh client. A throwaway
     * GET against the homepage with full browser headers populates whatever
     * cookie / fingerprint store NiceHttp keeps, after which subsequent calls
     * succeed. Idempotent and best-effort: failures are swallowed.
     */
    private suspend fun warmupSession() {
        if (sessionWarmed) return
        try {
            app.get(mainUrl, headers = baseHeaders, timeout = 15L)
        } catch (_: Throwable) {
            // Ignore — even a failed warmup may have set cookies.
        }
        sessionWarmed = true
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        if (page <= 1) warmupSession()
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
