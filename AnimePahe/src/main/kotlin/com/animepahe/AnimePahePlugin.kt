package com.animepahe

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

enum class ServerList(
    val link: Pair<String, Boolean>,
) {
    SI("https://animepahe.pw" to true),
    ORG("https://animepahe.org" to true),
    BEST("https://animepahe.com" to true),
}

@CloudstreamPlugin
class AnimePaheProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimePahe())
        registerExtractorAPI(Kwik())
        registerExtractorAPI(Pahe())
    }
}
