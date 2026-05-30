package com.nekopoi

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Filemoon : ExtractorApi() {
    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d("Nekopoi", "$name extractor: getUrl url=$url referer=$referer")
        val response = app.get(url, referer = referer ?: url)
        val script =
            response.document
                .selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()
                ?: response.text
        val unpacked =
            try {
                getAndUnpack(script)
            } catch (_: Throwable) {
                script
            }

        val m3u8 =
            Regex("""sources:\s*\[\s*\{\s*file\s*:\s*"([^"]+)"""")
                .find(unpacked)
                ?.groupValues
                ?.getOrNull(1)
                ?: Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
                    .find(unpacked)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
                    .find(unpacked)
                    ?.groupValues
                    ?.getOrNull(1)
                ?: run {
                    Log.e("Nekopoi", "$name extractor: no m3u8 found in $url (unpacked length=${unpacked.length})")
                    return
                }

        Log.d("Nekopoi", "$name extractor: m3u8 found = ${m3u8.take(80)}")
        callback.invoke(
            newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                this.referer = referer ?: url
                this.quality = Qualities.Unknown.value
            },
        )
    }
}

/**
 * Playmogo packs its source URL into a different layout than Filemoon
 * (jwplayer-style `file: "..."` inside a `sources` or `setup` block, or
 * sometimes a bare `"hls": "..."` JSON pair). Logs show Filemoon's regex
 * never matches Playmogo's 60k unpacked output, so we maintain our own
 * pattern cascade here and fall back to .mp4 as a last resort.
 */
class Playmogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        Log.d("Nekopoi", "$name extractor: getUrl url=$url referer=$referer")
        val response = app.get(url, referer = referer ?: url)
        val script =
            response.document
                .selectFirst("script:containsData(function(p,a,c,k,e,d))")
                ?.data()
                ?: response.text
        val unpacked =
            try {
                getAndUnpack(script)
            } catch (_: Throwable) {
                script
            }

        val m3u8Patterns =
            listOf(
                Regex("""sources:\s*\[\s*\{\s*file\s*:\s*"([^"]+)""""),
                Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""src\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""source\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex(""""hls"\s*:\s*["']([^"']+)["']"""),
                Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)""""),
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
            )

        val m3u8 = m3u8Patterns.firstNotNullOfOrNull { regex ->
            regex
                .find(unpacked)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.startsWith("http") }
        }

        if (m3u8 != null) {
            Log.d("Nekopoi", "$name extractor: m3u8 found = ${m3u8.take(80)}")
            callback.invoke(
                newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                    this.referer = referer ?: url
                    this.quality = Qualities.Unknown.value
                },
            )
            return
        }

        // Last-resort: direct .mp4
        val mp4 =
            Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""")
                .find(unpacked)
                ?.groupValues
                ?.getOrNull(1)
        if (mp4 != null) {
            Log.d("Nekopoi", "$name extractor: mp4 fallback = ${mp4.take(80)}")
            callback.invoke(
                newExtractorLink(name, name, mp4, ExtractorLinkType.VIDEO) {
                    this.referer = referer ?: url
                    this.quality = Qualities.Unknown.value
                },
            )
            return
        }

        Log.e(
            "Nekopoi",
            "$name extractor: no m3u8 or mp4 found in $url (unpacked length=${unpacked.length})",
        )
    }
}

class Streampoi : Filemoon() {
    override val name = "Streampoi"
    override val mainUrl = "https://streampoi.com"
}

