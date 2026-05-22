package com.dutamovie

import android.content.Context
import com.lagradost.cloudstream3.extractors.JWPlayer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DutaMoviePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DutaMovie())
        // `Ryderjet` tidak ada lagi di paket extractors CloudStream sehingga
        // baris registrasinya dihapus. Sebagai gantinya, semua extractor JWPlayer
        // lokal kita daftarkan supaya host yang relevan (embedpyrox, helvid, p2pplay)
        // tetap bisa di-resolve.
        registerExtractorAPI(JWPlayer())
        registerExtractorAPI(Embedpyrox())
        registerExtractorAPI(Helvid())
        registerExtractorAPI(P2pplay())
    }
}
