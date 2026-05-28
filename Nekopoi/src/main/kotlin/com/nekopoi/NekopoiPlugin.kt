package com.nekopoi

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NekopoiPlugin : Plugin() {
    override fun load(context: Context) {
        runCatching { registerMainAPI(Nekopoi()) }
            .onFailure { Log.e("Nekopoi", "registerMainAPI failed", it) }
        runCatching { registerExtractorAPI(Filemoon()) }
            .onFailure { Log.e("Nekopoi", "registerExtractorAPI(Filemoon) failed", it) }
        runCatching { registerExtractorAPI(Playmogo()) }
            .onFailure { Log.e("Nekopoi", "registerExtractorAPI(Playmogo) failed", it) }
        runCatching { registerExtractorAPI(Streampoi()) }
            .onFailure { Log.e("Nekopoi", "registerExtractorAPI(Streampoi) failed", it) }
    }
}
