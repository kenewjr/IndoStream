package com.pusatfilm

import com.lagradost.cloudstream3.SubtitleFile
// `apmap` di-deprecate dengan level ERROR karena memblokir thread via runBlocking.
// `amap` adalah pengganti suspending yang resmi dan punya signature serupa.
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

open class Kotakajaib : ExtractorApi() {
    override val name = "Kotakajaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = true

    override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) {
        app.get(url, referer = referer).document.select("ul#dropdown-server li a").amap {
            loadExtractor(
                    base64Decode(it.attr("data-frame")),
                    "$mainUrl/",
                    subtitleCallback,
                    callback
            )
        }
    }
}
