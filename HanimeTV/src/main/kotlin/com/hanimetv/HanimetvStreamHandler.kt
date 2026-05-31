package com.hanimetv

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
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
    Log.d("HanimeTV", "resolveStreamLinks: GET $apiUrl")

    val response =
        try {
            app.get(apiUrl, headers = apiHeaders, timeout = 30L)
        } catch (t: Throwable) {
            Log.e("HanimeTV", "resolveStreamLinks: request threw for $apiUrl", t)
            null
        }
    if (response == null || !response.isSuccessful) {
        Log.w(
            "HanimeTV",
            "resolveStreamLinks: API failed code=${response?.code}, falling back to HTML",
        )
        return resolveStreamsFromHtml(data, callback)
    }
    Log.d("HanimeTV", "resolveStreamLinks: API OK, body=${response.text.length}")

    val payload =
        response.parsedSafe<HanimeVideoResponse>()
            ?: parseFromRawJson(response.text)
            ?: run {
                Log.e(
                    "HanimeTV",
                    "resolveStreamLinks: parse + JSONObject fallback failed, trying HTML",
                )
                return resolveStreamsFromHtml(data, callback)
            }
    val manifest = payload.videosManifest ?: run {
        Log.w("HanimeTV", "resolveStreamLinks: no videos_manifest, falling back to HTML")
        return resolveStreamsFromHtml(data, callback)
    }

    // Diagnostic: dump the raw manifest so we can see if hanime.tv is returning
    // real CDN URLs or just guest stubs. The full body is too large for one log
    // line, so just slice the manifest section.
    val manifestStart = response.text.indexOf("\"videos_manifest\"")
    if (manifestStart >= 0) {
        Log.d(
            "HanimeTV",
            "videos_manifest sample=${response.text.substring(manifestStart, (manifestStart + 1200).coerceAtMost(response.text.length))}",
        )
    } else {
        Log.w("HanimeTV", "no videos_manifest key in API body")
    }

    val linkCount = AtomicInteger(0)
    val skippedStubs = AtomicInteger(0)
    manifest.servers.orEmpty().forEach { server ->
        val serverName = server.name?.takeIf { it.isNotBlank() } ?: "Hanime"
        server.streams.orEmpty().forEach { stream ->
            val url = stream.url?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach

            // Skip explicitly guest-blocked premium streams (they 403 anyway).
            if (stream.isGuestAllowed == false) {
                Log.d("HanimeTV", "skip premium-only stream height=${stream.height}")
                return@forEach
            }

            // Skip the well-known guest-stub URL hanime.tv returns instead of
            // real CDN links when you aren't logged in. The player would just
            // crash with NETWORK_CONNECTION_FAILED on it.
            if (url.contains("streamable.cloud/hls/stream.m3u8", ignoreCase = true)) {
                skippedStubs.incrementAndGet()
                Log.w("HanimeTV", "skip stub URL height=${stream.height} url=$url")
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
    val stubs = skippedStubs.get()
    Log.d("HanimeTV", "resolveStreamLinks: registered=$total stubs_skipped=$stubs")

    // If the only thing the API gave us were guest stubs, fall back to the
    // official player iframe and finally to HTML regex on the page.
    if (total == 0) {
        Log.w("HanimeTV", "API returned no real streams (likely guest-blocked)")
        val viaIframe = resolveViaPlayerIframe(slug, data, subtitleCallback, callback)
        if (viaIframe) return true
        Log.w("HanimeTV", "player iframe yielded nothing, trying HTML scrape")
        return resolveStreamsFromHtml(data, callback)
    }
    return true
}

/**
 * Hanime.tv embeds an iframe at https://player.hanime.tv/?v=<slug> on every
 * watch page. Forwarding that URL through CloudStream's loadExtractor lets the
 * generic Cloudflare-stream / Streamtape / etc. extractors take a crack at it.
 *
 * This is the realistic path now that the v8 /api/v8/video?id=<slug> endpoint
 * gates real CDN URLs behind a WASM-generated X-Signature header that we can't
 * reproduce from a Kotlin plugin.
 */
private suspend fun resolveViaPlayerIframe(
    slug: String,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    val iframeUrl = "https://player.hanime.tv/?v=$slug"
    Log.d("HanimeTV", "resolveViaPlayerIframe: $iframeUrl")
    val before = AtomicInteger(0)
    val countingCallback: (ExtractorLink) -> Unit = { link ->
        before.incrementAndGet()
        Log.d(
            "HanimeTV",
            "resolveViaPlayerIframe: extractor link source=${link.source} url=${link.url.take(80)}",
        )
        callback(link)
    }
    return try {
        loadExtractor(iframeUrl, referer, subtitleCallback, countingCallback)
        val n = before.get()
        Log.d("HanimeTV", "resolveViaPlayerIframe: produced $n links")
        n > 0
    } catch (t: Throwable) {
        Log.e("HanimeTV", "resolveViaPlayerIframe: threw", t)
        false
    }
}

/**
 * HTML fallback for streams. Hanime.tv embeds the manifest JSON in the
 * page HTML inside a `window.__NUXT__ = ...` IIFE. We don't fully
 * evaluate it - we just regex out direct .m3u8 / .mp4 references.
 */
internal suspend fun resolveStreamsFromHtml(
    pageUrl: String,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    return try {
        Log.d("HanimeTV", "resolveStreamsFromHtml: GET $pageUrl")
        val res = app.get(pageUrl, headers = baseHeaders, timeout = 30L)
        if (!res.isSuccessful) {
            Log.e("HanimeTV", "resolveStreamsFromHtml: HTTP ${res.code}")
            return false
        }
        val html = res.text
        val seen = mutableSetOf<String>()
        val regex =
            Regex("""(https?:\\?/\\?/[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*)""", RegexOption.IGNORE_CASE)
        regex.findAll(html).forEach { m ->
            val raw = m.groupValues[1].replace("\\/", "/")
            if (!seen.add(raw)) return@forEach
            val isM3u8 = raw.contains(".m3u8", true)
            val heightMatch = Regex("""(\d{3,4})p""").find(raw)?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
            callback(
                newExtractorLink(
                    source = "HanimeTV",
                    name =
                        if (heightMatch != null) "HanimeTV ${heightMatch}p" else "HanimeTV",
                    url = raw,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$DOMAIN/"
                    this.quality = heightToQuality(heightMatch)
                },
            )
            Log.d("HanimeTV", "html-fallback registered ${raw.take(80)}")
        }
        Log.d("HanimeTV", "resolveStreamsFromHtml: ${seen.size} links found")
        seen.isNotEmpty()
    } catch (t: Throwable) {
        Log.e("HanimeTV", "resolveStreamsFromHtml: threw", t)
        false
    }
}
