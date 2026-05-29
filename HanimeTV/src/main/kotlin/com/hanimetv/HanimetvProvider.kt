package com.hanimetv

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
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class HanimetvProvider : MainAPI() {
    override var mainUrl = DOMAIN
    override var name = "HanimeTV"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = hanimetvMainPage

    /** Resilient JSON GET with retry/back-off for the v8 API. */
    internal suspend fun safeApiGet(
        url: String,
        maxRetries: Int = 3,
    ): NiceResponse? {
        var lastError: Throwable? = null
        repeat(maxRetries) { attempt ->
            try {
                return app.get(url, headers = apiHeaders, timeout = 30L)
            } catch (t: Throwable) {
                lastError = t
                android.util.Log.e(
                    "HanimeTV",
                    "safeApiGet attempt ${attempt + 1}/$maxRetries failed for $url: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                )
                if (attempt < maxRetries - 1) delay(700L * (attempt + 1))
            }
        }
        logError(lastError ?: Exception("HanimeTV safeApiGet failed: $url"))
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val zeroPage = (page - 1).coerceAtLeast(0)
        val data = request.data
        val (kind, arg) =
            data.substringBefore(":") to data.substringAfter(":", missingDelimiterValue = "")

        val items: List<SearchResponse> =
            when (kind) {
                "trending" -> fetchTrending(arg.ifBlank { "week" }, zeroPage)
                "browse" -> {
                    val parts = arg.split(":")
                    fetchBrowse(
                        orderBy = parts.getOrNull(0).orEmpty(),
                        ordering = parts.getOrNull(1) ?: "desc",
                        page = zeroPage,
                    )
                }
                "tag" -> fetchByTag(arg, zeroPage)
                "brand" -> fetchByBrand(arg, zeroPage)
                else -> emptyList()
            }

        return newHomePageResponse(
            list =
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false,
                ),
            hasNext = items.isNotEmpty(),
        )
    }

    private suspend fun fetchTrending(
        time: String,
        page: Int,
    ): List<SearchResponse> {
        val url = "$API_BASE/browse-trending?time=$time&page=$page"
        val resp = safeApiGet(url) ?: return emptyList()
        val parsed = resp.parsedSafe<HanimeBrowseResponse>() ?: return emptyList()
        return parsed.hentaiVideos.orEmpty().mapNotNull { hentaiVideoToSearchResponse(it) }
    }

    private suspend fun fetchBrowse(
        orderBy: String,
        ordering: String,
        page: Int,
    ): List<SearchResponse> {
        val url =
            "$API_BASE/browse?time=all-time&order_by=$orderBy&ordering=$ordering&page=$page"
        val resp = safeApiGet(url) ?: return emptyList()
        val parsed = resp.parsedSafe<HanimeBrowseResponse>() ?: return emptyList()
        return parsed.hentaiVideos.orEmpty().mapNotNull { hentaiVideoToSearchResponse(it) }
    }

    private suspend fun fetchByTag(
        tag: String,
        page: Int,
    ): List<SearchResponse> = searchViaPostApi(
        searchText = "",
        tags = listOf(tag),
        brands = emptyList(),
        page = page,
    )

    private suspend fun fetchByBrand(
        brand: String,
        page: Int,
    ): List<SearchResponse> = searchViaPostApi(
        searchText = "",
        tags = emptyList(),
        brands = listOf(brand),
        page = page,
    )

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        return searchViaPostApi(
            searchText = trimmed,
            tags = emptyList(),
            brands = emptyList(),
            page = 0,
        )
    }

    /**
     * The hanime.tv search endpoint expects a POST body like:
     *   {
     *     "search_text": "...",
     *     "tags": [],
     *     "tags_mode": "AND",
     *     "brands": [],
     *     "blacklist": [],
     *     "order_by": "created_at_unix",
     *     "ordering": "desc",
     *     "page": 0
     *   }
     *
     * Response contains a `hits` field that is a JSON-encoded string of an
     * array of HentaiVideo objects (yes, double-serialized).
     */
    private suspend fun searchViaPostApi(
        searchText: String,
        tags: List<String>,
        brands: List<String>,
        page: Int,
    ): List<SearchResponse> {
        val bodyJson =
            JSONObject().apply {
                put("search_text", searchText)
                put("tags", JSONArray(tags))
                put("tags_mode", "AND")
                put("brands", JSONArray(brands))
                put("blacklist", JSONArray())
                put("order_by", "created_at_unix")
                put("ordering", "desc")
                put("page", page)
            }.toString()

        val response =
            try {
                val mediaType = "application/json;charset=UTF-8".toMediaTypeOrNull()
                app.post(
                    SEARCH_API,
                    requestBody = bodyJson.toRequestBody(mediaType),
                    headers = searchApiHeaders,
                    timeout = 30L,
                )
            } catch (t: Throwable) {
                android.util.Log.e("HanimeTV", "search POST failed", t)
                return emptyList()
            }

        if (!response.isSuccessful) {
            android.util.Log.e("HanimeTV", "search POST status ${response.code}")
            return emptyList()
        }
        val parsed = response.parsedSafe<HanimeSearchResponse>() ?: return emptyList()
        val hitsJson = parsed.hits ?: return emptyList()

        return runCatching {
            val arr = JSONArray(hitsJson)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val slug = obj.optString("slug", "").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val name = obj.optString("name", "").ifBlank { slug }
                val poster =
                    obj.optString("cover_url", "").ifBlank {
                        obj.optString("poster_url", "")
                    }.ifBlank { null }
                newAnimeSearchResponse(
                    name,
                    "$DOMAIN/videos/hentai/$slug",
                    TvType.NSFW,
                ) {
                    this.posterUrl = poster
                }
            }
        }.getOrElse {
            android.util.Log.e("HanimeTV", "hits parse failed", it)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse = parseLoadPage(url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = resolveStreamLinks(data, isCasting, subtitleCallback, callback)
}
