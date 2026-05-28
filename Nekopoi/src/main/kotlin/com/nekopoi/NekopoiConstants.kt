package com.nekopoi

import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName


internal const val DOMAIN = "https://nekopoi.care"


internal val baseHeaders =
    mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Connection" to "keep-alive",
    )

internal val mirrorBlackList =
    arrayOf(
        "MegaupNet",
        "DropApk",
        "Racaty",
        "ZippyShare",
        "VideobinCo",
        "DropApk",
        "SendCm",
        "GoogleDrive",
    )

internal const val mirroredHost = "https://www.mirrored.to"

internal val backgroundImageRegex =
    Regex("""background-image\s*:\s*url\(\s*['"]?([^'")]+)['"]?\s*\)""")

internal val nekopoiMainPage: List<MainPageData> =
    mainPageOf(
        "$DOMAIN/?orderby=terbaru" to "🆕 Terbaru",
        "$DOMAIN/category/hentai/" to "🔞 Hentai",
        "$DOMAIN/category/jav/" to "📺 JAV",
        "$DOMAIN/category/3d-hentai/" to "🎭 3D Hentai",
        "$DOMAIN/category/2d-animation/" to "🎨 2D Animation",
        "$DOMAIN/category/jav-cosplay/" to "👘 JAV Cosplay",
        "$DOMAIN/genres/big-oppai/" to "Big Oppai",
        "$DOMAIN/genres/schoolgirl/" to "Schoolgirl",
        "$DOMAIN/genres/vanilla/" to "Vanilla",
        "$DOMAIN/genres/romance/" to "Romance",
        "$DOMAIN/genres/shota/" to "Shota",
        "$DOMAIN/genres/maid/" to "Maid",
        "$DOMAIN/genres/yuri/" to "Yuri",
        "$DOMAIN/genres/netorare/" to "Netorare",
        "$DOMAIN/genres/harem/" to "Harem",
        "$DOMAIN/genres/milf/" to "MILF",
        "$DOMAIN/genres/incest/" to "Incest",
        "$DOMAIN/genres/futanari/" to "Futanari",
        "$DOMAIN/genres/loli/" to "Loli",
        "$DOMAIN/genres/uncensored/" to "Uncensored",
    )


internal fun getStatus(t: String?): ShowStatus? = runCatching {
    when (t) {
        "Completed" -> ShowStatus.Completed
        "Ongoing" -> ShowStatus.Ongoing
        else -> null
    }
}.getOrNull()

internal fun getIndexQuality(str: String?): Int {
    val quality =
        Regex("""(?i)\[(\d+[pk])]""")
            .find(str ?: "")
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
            ?: Regex("""(?i)(\d+[pk])""")
                .find(str ?: "")
                ?.groupValues
                ?.getOrNull(1)
                ?.lowercase()
    return when (quality) {
        "4k" -> Qualities.P2160.value
        "2k" -> Qualities.P1440.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        "360p" -> Qualities.P360.value
        else -> getQualityFromName(quality)
    }
}
