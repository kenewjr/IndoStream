package com.hanimetv

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HanimetvPlugin : Plugin() {
    override fun load(context: Context) {
        runCatching { registerMainAPI(HanimetvProvider()) }
            .onFailure { Log.e("HanimeTV", "registerMainAPI failed", it) }
    }
}
