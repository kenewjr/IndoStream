package com.hanimetv

import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.Qualities

internal const val DOMAIN = "https://hanime.tv"
internal const val API_BASE = "https://hanime.tv/api/v8"
internal const val SEARCH_API = "https://search.htv-services.com/"

internal val baseHeaders =
    mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Connection" to "keep-alive",
        "Referer" to "$DOMAIN/",
        "Origin" to DOMAIN,
    )

// Hanime.tv image CDN requires a Referer of https://hanime.tv/ — without it
// Glide/Coil get a 403 and the UI shows blank poster tiles. Pass these headers
// to posterHeaders on every SearchResponse / LoadResponse that has an image.
internal val imageHeaders =
    mapOf(
        "Referer" to "$DOMAIN/",
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
    )

internal val apiHeaders =
    mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$DOMAIN/",
        "Origin" to DOMAIN,
        "X-Requested-With" to "XMLHttpRequest",
    )

internal val searchApiHeaders =
    mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Content-Type" to "application/json;charset=UTF-8",
        "Referer" to "$DOMAIN/",
        "Origin" to DOMAIN,
    )

/**
 * Hanime.tv main page sections.
 *
 * The public `search.htv-services.com` endpoint accepts an `order_by` field;
 * each row here is just a different sort key on the same endpoint. Random uses
 * `created_at_unix` desc but the dispatcher in HanimetvProvider picks a random
 * page each time so the row reshuffles on every refresh.
 *
 * /api/v8/browse-trending and /api/v8/browse return 401 without an auth token
 * so we don't use them here.
 */
internal val hanimetvMainPage: List<MainPageData> =
    mainPageOf(
        "sorted:created_at_unix" to "🆕 Recent Uploads",
        "sorted:released_at_unix" to "📅 New Releases",
        "sorted:views" to "🔥 Trending",
        "random:created_at_unix" to "🎲 Random",
    )

internal fun getStatus(t: String?): ShowStatus? = runCatching {
    when {
        t.isNullOrBlank() -> null
        t.equals("ongoing", ignoreCase = true) -> ShowStatus.Ongoing
        t.equals("completed", ignoreCase = true) || t.equals("finished", ignoreCase = true) ->
            ShowStatus.Completed
        else -> null
    }
}.getOrNull()

internal fun heightToQuality(height: Int?): Int =
    when (height) {
        null -> Qualities.Unknown.value
        in 0..359 -> Qualities.P240.value
        in 360..479 -> Qualities.P360.value
        in 480..719 -> Qualities.P480.value
        in 720..1079 -> Qualities.P720.value
        in 1080..1439 -> Qualities.P1080.value
        in 1440..2159 -> Qualities.P1440.value
        else -> Qualities.P2160.value
    }
