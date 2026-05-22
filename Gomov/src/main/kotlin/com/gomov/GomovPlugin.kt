package com.gomov

import android.content.Context
// `Chillx` sudah dihapus dari paket extractors CloudStream upstream, jadi
// import dan registrasinya ikut dihapus.
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GomovPlugin : Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list
        // directly.
        registerMainAPI(Gomov())
        registerExtractorAPI(Watchx())
        registerExtractorAPI(Boosterx())
        registerExtractorAPI(Dhtpre())
        registerExtractorAPI(Filelions())
    }
}
