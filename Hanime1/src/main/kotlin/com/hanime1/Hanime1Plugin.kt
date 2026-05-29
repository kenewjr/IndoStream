package com.hanime1

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Hanime1Plugin : Plugin() {
    override fun load(context: Context) {
        runCatching { registerMainAPI(Hanime1Provider()) }
            .onFailure { Log.e("Hanime1", "registerMainAPI failed", it) }
    }
}
