package com.animepahe

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

enum class ServerList(val link: Pair<String, Boolean>) {
    SI("https://animepahe.pw" to true),
    ORG("https://animepahe.org" to true),
    BEST("https://animepahe.com" to true),
}

@CloudstreamPlugin
class AnimePaheProviderPlugin : Plugin() {
    override fun load() {
        registerMainAPI(AnimePahe())
        registerExtractorAPI(Kwik())
        registerExtractorAPI(Pahe())

        // Tap the gear icon next to AnimePahe in CloudStream's plugin list to
        // open the server picker built in BottomSheet.kt.
        this.openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            BottomFragment(this).show(activity.supportFragmentManager, "AnimePahe-Settings")
        }
    }

    companion object {
        var currentAnimepaheServer: String
            get() = getKey("ANIMEPAHE_CURRENT_SERVER") ?: ServerList.BEST.link.first
            set(value) {
                setKey("ANIMEPAHE_CURRENT_SERVER", value)
            }
    }
}