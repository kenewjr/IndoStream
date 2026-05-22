package com.nodrakorid

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NodrakoridPlugin : Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list
        // directly.
        registerMainAPI(Nodrakorid())
        // `Chillx` tidak ada lagi di paket extractors CloudStream (sudah dihapus
        // upstream), jadi import dan registrasinya dihapus. CloudStream akan
        // otomatis memakai resolver bawaan untuk host yang sebelumnya dipegang
        // class ini.
        registerExtractorAPI(Boosterx())
    }
}
