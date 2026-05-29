package com.hanimetv

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hanime.tv stream resolver.
 *
 * `data` is the canonical /videos/hentai/<slug> URL. We:
 *   1. Re-fetch /api/v8/video?id=<slug> (the manifest is per-request).
 *   2. Iterate every server -> stream pair.
 *   3. Emit each playable stream as a CloudStream ExtractorLink.
 *
 * Streams without a `url` (premium-only or guest-blocked) are skipped silently
 * but logged. Streams missing a height fall back to Unknown quality.
 */
internal suspend fun HanimetvProvider.resolveStreamLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val slug = extractSlug(data) ?: run {
        Log.e("HanimeTV", "resolveStreamLinks: no slug from $data")
        return false
    }
    val apiUrl = "$API_BASE/video?id=$slug"
    Log.d("HanimeTV", "resolveStreamLinks: fetching $apiUrl")

    val response =
        try {
            app.get(apiUrl, headers = apiHeaders, timeout = 30L)
        } catch (t: Throwable) {
            Log.e("HanimeTV", "resolveStreamLinks: request failed for $apiUrl", t)
            return false
        }
    if (!response.isSuccessful) {
        Log.e("HanimeTV", "resolveStreamLinks: api ${response.code} for $apiUrl")
        return false
    }

    val payload = response.parsedSafe<HanimeVideoResponse>() ?: run {
        Log.e("HanimeTV", "resolveStreamLinks: parse failure for $apiUrl")
        return false
    }
    val manifest = payload.videosManifest ?: run {
        Log.w("HanimeTV", "resolveStreamLinks: no videos_manifest for $slug")
        return false
    }

    val linkCount = AtomicInteger(0)
    manifest.servers.orEmpty().forEach { server ->
        val serverName = server.name?.takeIf { it.isNotBlank() } ?: "Hanime"
        server.streams.orEmpty().forEach { stream ->
            val url = stream.url?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach

            // Skip explicitly guest-blocked premium streams (they 403 anyway).
            if (stream.isGuestAllowed == false) {
                Log.d("HanimeTV", "skip premium-only stream height=${stream.height}")
                return@forEach
            }

            val height = stream.height
            val isM3u8 =
                url.contains(".m3u8", true) ||
                    stream.kind?.equals("hls", ignoreCase = true) == true
            val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val quality =
                heightToQuality(height).takeIf { it != Qualities.Unknown.value }
                    ?: Qualities.Unknown.value
            val displayName =
                buildString {
                    append("HanimeTV ")
                    append(serverName)
                    if (height != null) {
                        append(" ${height}p")
                    } else if (!stream.kind.isNullOrBlank()) {
                        append(" ${stream.kind}")
                    }
                }.trim()

            callback(
                newExtractorLink(
                    source = "HanimeTV",
                    name = displayName,
                    url = url,
                    type = type,
                ) {
                    this.referer = "$DOMAIN/"
                    this.quality = quality
                },
            )
            linkCount.incrementAndGet()
            Log.d("HanimeTV", "registered $displayName -> ${url.take(80)}")
        }
    }

    val total = linkCount.get()
    Log.d("HanimeTV", "resolveStreamLinks: total $total links registered")
    return total > 0
}
