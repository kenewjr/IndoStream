
package com.dramaid

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DramaidPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Dramaid())
        // [REMOVED]: Oppadrama dihapus per request audit — domain bare-IP
        // 45.11.57.64 tidak lagi resolve.
        registerExtractorAPI(Vanfem())
        registerExtractorAPI(Filelions())
        registerExtractorAPI(Gcam())
    }
}