package com.hanime1

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hanime1 streaming pipeline.
 *
 * Hanime1 ships its own player, so we look for streams in this priority order:
 *  1. <video><source src="...mp4" data-quality="720"> elements (most common path)
 *  2. og:video / og:video:secure_url meta tags
 *  3. Inline JS `var streams = {"360":"...mp4","720":"...mp4"}` blob
 *  4. Generic regex on .mp4 / .m3u8 in the HTML
 *  5. Iframes (very rare on hanime1, but kept for resilience)
 *
 * All MP4/M3U8 hits are wired up directly with newExtractorLink. Iframes are
 * forwarded to loadExtractor so generic CloudStream extractors can handle them.
 */
internal suspend fun Hanime1Provider.resolveStreamLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    Log.d("Hanime1", "resolveStreamLinks: data=$data")
    val doc = safeGet(data)?.document ?: run {
        Log.e("Hanime1", "resolveStreamLinks: safeGet returned null for $data")
        return false
    }
    val html = doc.html()
    val linkCount = AtomicInteger(0)
    val countingCallback: (ExtractorLink) -> Unit = { link ->
        linkCount.incrementAndGet()
        Log.d("Hanime1", "resolveStreamLinks: link source=${link.source} url=${link.url.take(80)}")
        callback(link)
    }

    coroutineScope {
        // Method 1: <source> tags inside <video>
        launch {
            runCatching {
                doc.select("video source[src], video[src]").forEach { el ->
                    val raw = el.attr("src").ifBlank { el.attr("data-src") }
                    val src = normalizeStreamUrl(raw) ?: return@forEach
                    val qualityAttr =
                        el.attr("data-quality").ifBlank { el.attr("size") }
                            .ifBlank { el.attr("label") }
                    emitDirectLink(src, qualityAttr, data, countingCallback)
                }
            }.onFailure { Log.e("Hanime1", "video<source> extraction failed", it) }
        }

        // Method 2: og:video meta
        launch {
            runCatching {
                doc.select("meta[property^=og:video], meta[itemprop=contentURL]").forEach { meta ->
                    val src = normalizeStreamUrl(meta.attr("content")) ?: return@forEach
                    if (src.contains(".mp4", true) || src.contains(".m3u8", true)) {
                        emitDirectLink(src, null, data, countingCallback)
                    }
                }
            }.onFailure { Log.e("Hanime1", "og:video extraction failed", it) }
        }

        // Method 3: var streams = { ... } JS blob
        launch {
            runCatching {
                val blob = streamsBlobRegex.find(html)?.groupValues?.getOrNull(1)
                if (!blob.isNullOrBlank()) {
                    singleStreamPairRegex.findAll(blob).forEach { m ->
                        val height = m.groupValues[1].toIntOrNull()
                        val src = normalizeStreamUrl(m.groupValues[2]) ?: return@forEach
                        emitDirectLink(src, "${height}p", data, countingCallback, height)
                    }
                }
            }.onFailure { Log.e("Hanime1", "streams blob extraction failed", it) }
        }

        // Method 4: generic mp4/m3u8 regex sweep (last-resort de-dupe handled below)
        launch {
            runCatching {
                Regex(
                    """(?:file|src|url|source)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""",
                    RegexOption.IGNORE_CASE,
                ).findAll(html).forEach { m ->
                    val src = normalizeStreamUrl(m.groupValues[1]) ?: return@forEach
                    emitDirectLink(src, null, data, countingCallback)
                }
            }.onFailure { Log.e("Hanime1", "generic regex extraction failed", it) }
        }

        // Method 5: iframe fallback
        launch {
            runCatching {
                doc.select("iframe[src], iframe[data-src]").forEach { el ->
                    val raw = el.attr("src").ifBlank { el.attr("data-src") }
                    val src = normalizeStreamUrl(raw) ?: return@forEach
                    runCatching {
                        loadExtractor(src, data, subtitleCallback, countingCallback)
                    }.onFailure { Log.e("Hanime1", "loadExtractor failed for $src", it) }
                }
            }.onFailure { Log.e("Hanime1", "iframe extraction failed", it) }
        }
    }

    val total = linkCount.get()
    Log.d("Hanime1", "resolveStreamLinks: total links registered = $total")
    return total > 0
}

private suspend fun emitDirectLink(
    url: String,
    qualityHint: String?,
    referer: String,
    callback: (ExtractorLink) -> Unit,
    explicitHeight: Int? = null,
) {
    val isM3u8 = url.contains(".m3u8", true)
    val height = explicitHeight ?: parseHeightFromHint(qualityHint)
    val quality =
        when {
            height != null -> heightToQuality(height)
            qualityHint != null -> getIndexQuality(qualityHint)
            else -> Qualities.Unknown.value
        }
    val displayName =
        when {
            height != null -> "Hanime1 ${height}p"
            !qualityHint.isNullOrBlank() -> "Hanime1 ${qualityHint.trim()}"
            else -> "Hanime1"
        }
    callback(
        newExtractorLink(
            source = "Hanime1",
            name = displayName,
            url = url,
            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
        ) {
            this.referer = referer
            this.quality = quality
        },
    )
}

private fun parseHeightFromHint(hint: String?): Int? {
    if (hint.isNullOrBlank()) return null
    return Regex("""(\d{3,4})""").find(hint)?.value?.toIntOrNull()
}
