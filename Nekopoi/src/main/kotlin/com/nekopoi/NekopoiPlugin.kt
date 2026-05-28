package com.nekopoi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NekopoiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Nekopoi())
        registerExtractorAPI(Filemoon())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(Streampoi())
    }
}
