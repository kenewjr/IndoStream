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

class Playmogo : Filemoon() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
}

class Streampoi : Filemoon() {
    override val name = "Streampoi"
    override val mainUrl = "https://streampoi.com"
}

