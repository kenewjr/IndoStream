package com.nekopoi

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NekopoiPlugin : Plugin() {
    override fun load(context: Context) {
        // Kototoro is stricter than CloudStream about exceptions during plugin load.
        // Wrap each registration so a single failure (e.g. an extractor whose class
        // init touches a missing dep) doesn't take down the whole plugin and leave it
        // in Packages-but-no-episodes state.
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
