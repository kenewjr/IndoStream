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
 * NOTE: /api/v8/browse-trending and /api/v8/browse return 401 without an auth
 * token (logs confirm `fetchTrending: code=401`). Tag/brand rows go through
 * the public `search.htv-services.com` algolia endpoint which does not require
 * auth, so we keep only those.
 */
internal val hanimetvMainPage: List<MainPageData> =
    mainPageOf(
        "tag:Big Boobs" to "Big Boobs",
        "tag:Schoolgirl" to "Schoolgirl",
        "tag:Vanilla" to "Vanilla",
        "tag:Romance" to "Romance",
        "tag:Maid" to "Maid",
        "tag:Yuri" to "Yuri",
        "tag:NTR" to "NTR",
        "tag:Harem" to "Harem",
        "tag:MILF" to "MILF",
        "tag:Incest" to "Incest",
        "tag:Futanari" to "Futanari",
        "tag:Loli" to "Loli",
        "brand:Uncensored" to "Uncensored",
        "brand:3D" to "3D",
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
