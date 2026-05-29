package com.hanime1

import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName

internal const val DOMAIN = "https://hanime1.me"

internal val baseHeaders =
    mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        "Connection" to "keep-alive",
        "Referer" to "$DOMAIN/",
    )

internal val ajaxHeaders =
    baseHeaders +
        mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )

// Cloudflare-friendly cookie keys we may need to copy across requests
internal val cfCookieNames = setOf("__cf_bm", "cf_clearance", "user_lang")

internal val backgroundImageRegex =
    Regex("""background-image\s*:\s*url\(\s*['"]?([^'")]+)['"]?\s*\)""")

// Pulls the per-quality streams object from the player JS:
//   var streams = {"360":"https://...mp4","480":"...","720":"...","1080":"..."};
internal val streamsBlobRegex =
    Regex("""var\s+streams\s*=\s*(\{[^;]+?\})\s*;""", RegexOption.DOT_MATCHES_ALL)

internal val singleStreamPairRegex =
    Regex(""""(\d+)"\s*:\s*"([^"]+)"""")

internal val hanime1MainPage: List<MainPageData> =
    mainPageOf(
        "$DOMAIN/" to "🏠 首頁推薦",
        "$DOMAIN/search?sort=最新上市" to "🆕 最新上市",
        "$DOMAIN/search?sort=最新上傳" to "📤 最新上傳",
        "$DOMAIN/search?sort=本日排行" to "🔥 本日排行",
        "$DOMAIN/search?sort=本週排行" to "📈 本週排行",
        "$DOMAIN/search?sort=本月排行" to "🏆 本月排行",
        "$DOMAIN/search?sort=觀看次數" to "👁️ 觀看最多",
        "$DOMAIN/search?genre=裏番" to "🎬 裏番",
        "$DOMAIN/search?genre=同人" to "📚 同人",
        "$DOMAIN/search?genre=Cosplay" to "👘 Cosplay",
        "$DOMAIN/search?genre=3D" to "🎭 3D",
        "$DOMAIN/search?tags%5B%5D=巨乳" to "Big Oppai",
        "$DOMAIN/search?tags%5B%5D=蘿莉" to "Loli",
        "$DOMAIN/search?tags%5B%5D=制服" to "School Uniform",
        "$DOMAIN/search?tags%5B%5D=女僕" to "Maid",
        "$DOMAIN/search?tags%5B%5D=泳裝" to "Swimsuit",
        "$DOMAIN/search?tags%5B%5D=NTR" to "NTR",
        "$DOMAIN/search?tags%5B%5D=後宮" to "Harem",
        "$DOMAIN/search?tags%5B%5D=人妻" to "MILF",
        "$DOMAIN/search?tags%5B%5D=亂倫" to "Incest",
        "$DOMAIN/search?tags%5B%5D=百合" to "Yuri",
        "$DOMAIN/search?tags%5B%5D=扶他" to "Futanari",
        "$DOMAIN/search?tags%5B%5D=無碼" to "Uncensored",
    )

internal fun getStatus(t: String?): ShowStatus? = runCatching {
    when {
        t.isNullOrBlank() -> null
        t.contains("Completed", true) || t.contains("完結", true) -> ShowStatus.Completed
        t.contains("Ongoing", true) || t.contains("連載", true) -> ShowStatus.Ongoing
        else -> null
    }
}.getOrNull()

internal fun getIndexQuality(str: String?): Int {
    val raw = str.orEmpty()
    val match =
        Regex("""(?i)\[?(\d{3,4}p|2k|4k)\]?""")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.lowercase()
    return when (match) {
        "4k" -> Qualities.P2160.value
        "2k" -> Qualities.P1440.value
        "2160p" -> Qualities.P2160.value
        "1440p" -> Qualities.P1440.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        "360p" -> Qualities.P360.value
        else -> getQualityFromName(match)
    }
}

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
