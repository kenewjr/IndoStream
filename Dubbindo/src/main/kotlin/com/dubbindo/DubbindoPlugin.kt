package com.dubbindo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DubbindoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Dubbindo())
    }
}
