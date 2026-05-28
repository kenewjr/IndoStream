// NekopoiStreamHandler.kt - Video stream resolution: iframe extraction, ouo.io and mirrored.to bypass.
package com.nekopoi

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

internal suspend fun Nekopoi.resolveStreamLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
): Boolean {
    Log.d("Nekopoi", "resolveStreamLinks: data=$data")
    val res = safeGet(data)?.document ?: run {
        Log.e("Nekopoi", "resolveStreamLinks: safeGet returned null for $data")
        return false
    }
    Log.d("Nekopoi", "resolveStreamLinks: page fetched, ${res.text().length} chars")

    // Wrap callback to count successful registrations.
    val linkCount = AtomicInteger(0)
    val countingCallback: (ExtractorLink) -> Unit = { link ->
        linkCount.incrementAndGet()
        Log.d("Nekopoi", "resolveStreamLinks: registered link source=${link.source} url=${link.url.take(80)}")
        callback(link)
    }

    // Replaced runAllAsync (Kototoro R8-stripped CloudStream helper) with pure
    // kotlinx.coroutines primitives. coroutineScope waits for all child launches.
    coroutineScope {
        launch {
            runCatching {
            res
                .select(
                    // FIXED: BUG5 - added filemoon/streamtape/doodstream/mp4upload host
                    // selectors and a generic data-src fallback so we still detect embeds when
                    // the wrapper div class changes.
                    "#nk-player div.nk-player-frame iframe[src], " +
                        "div.nk-player-frame iframe[src], " +
                        "#nk-stream-1 iframe, #nk-stream-2 iframe, #nk-stream-3 iframe, " +
                        "div[id^=nk-stream] iframe, " +
                        ".nk-player iframe[src], " +
                        ".player-embed iframe[src], " +
                        "iframe[src*=filemoon], " +
                        "iframe[src*=streamtape], " +
                        "iframe[src*=doodstream], " +
                        "iframe[src*=mp4upload], " +
                        "div[data-src] iframe, " +
                        "iframe[data-src]",
                ).mapNotNull { iframe ->
                    (
                        iframe.attr("src").takeIf { it.isNotBlank() }
                            ?: iframe.attr("data-src").takeIf { it.isNotBlank() }
                    )
                }.mapNotNull { raw ->
                    val normalized =
                        when {
                            raw.startsWith("//") -> "https:$raw"
                            raw.startsWith("/") -> "$mainUrl$raw"
                            !raw.startsWith("http") -> "$mainUrl/$raw"
                            else -> raw
                        }
                    fixEmbed(normalized)
                }.distinct()
                .amap { src ->
                    Log.d("Nekopoi", "resolveStreamLinks: iframe extractor src=$src")
                    loadExtractor(src, "$mainUrl/", subtitleCallback, countingCallback)
                }
            }.onFailure { Log.e("Nekopoi", "iframe extraction block failed", it) }
        }
        launch {
            runCatching {
            val downloadPairs = mutableListOf<Pair<Int, String>>()
            val legacyRows = res.select("div.nk-download-row")
            if (legacyRows.isNotEmpty()) {
                legacyRows.mapNotNull { ele ->
                    val quality = getIndexQuality(ele.selectFirst("div.nk-download-name")?.text())
                    val href =
                        ele
                            .selectFirst("div.nk-download-links a[href*=ouo]")
                            ?.attr("href")
                            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    downloadPairs.add(quality to href)
                }
            }
            if (downloadPairs.isEmpty()) {
                val unduhHeading =
                    res
                        .select("h1, h2, h3, h4, h5, h6, b, strong, p")
                        .firstOrNull { el ->
                            el.text().trim().equals("UNDUH", ignoreCase = true)
                        }

                if (unduhHeading != null) {
                    val resolutionRegex = Regex("""\[(\d+[pk]|4K)\]""", RegexOption.IGNORE_CASE)
                    val downloadContainer = unduhHeading.parent()
                    val allElements = downloadContainer?.children() ?: unduhHeading.siblingElements()

                    var pastUnduh = false
                    var currentQuality: Int? = null

                    for (element in allElements) {
                        if (element == unduhHeading) {
                            pastUnduh = true
                            continue
                        }
                        if (!pastUnduh) continue
                        val elementText = element.text().trim()
                        if (element.tagName().matches(Regex("h[1-6]")) &&
                            !resolutionRegex.containsMatchIn(elementText) &&
                            elementText.isNotBlank() &&
                            !elementText.contains("ouo", ignoreCase = true)
                        ) {
                            break
                        }

                        val resMatch = resolutionRegex.find(elementText)
                        if (resMatch != null) {
                            currentQuality = getIndexQuality(elementText)
                        }

                        if (currentQuality != null) {
                            element.select("a[href*=ouo]").forEach { link ->
                                val href = link.attr("href").takeIf { it.isNotBlank() }
                                if (href != null) {
                                    downloadPairs.add(currentQuality!! to href)
                                }
                            }
                        }
                    }
                }

                if (downloadPairs.isEmpty()) {
                    val resolutionRegex = Regex("""\[(\d+[pk]|4K)\]""", RegexOption.IGNORE_CASE)

                    res.select("*:matches(\\[\\d+[pk]\\]|\\[4K\\])").forEach { element ->
                        val elementText = element.text().trim()
                        if (resolutionRegex.containsMatchIn(elementText)) {
                            val quality = getIndexQuality(elementText)

                            val links = element.select("a[href*=ouo]")
                            val effectiveLinks =
                                if (links.isEmpty()) {
                                    element.parent()?.select("a[href*=ouo]") ?: emptyList()
                                } else {
                                    links
                                }
                            effectiveLinks.forEach { link ->
                                val href = link.attr("href").takeIf { it.isNotBlank() }
                                if (href != null) {
                                    downloadPairs.add(quality to href)
                                }
                            }
                        }
                    }
                }
            }

            downloadPairs
                .map { (quality, ouoUrl) ->
                    if (ouoUrl.isBlank() || !ouoUrl.contains("ouo.io")) return@map
                    val destinationUrl =
                        try {
                            bypassOuo(ouoUrl)
                        } catch (e: Exception) {
                            null
                        }
                    if (destinationUrl.isNullOrBlank()) return@map

                    val isMirroredUrl = destinationUrl.contains("mirrored.to", ignoreCase = true)

                    val fileLinks: List<String> =
                        if (isMirroredUrl) {
                            try {
                                bypassMirrored(destinationUrl).filterNotNull().filter { it.isNotBlank() }
                            } catch (e: Exception) {
                                emptyList()
                            }
                        } else {
                            listOf(destinationUrl)
                        }

                    fileLinks.amap ads@{ adsLink ->
                        try {
                            val pixelMatch =
                                Regex("pixeldrain\\.com/u/([\\w-]+)")
                                    .find(adsLink)
                                    ?.groupValues
                                    ?.getOrNull(1)
                            if (pixelMatch != null) {
                                countingCallback(
                                    newExtractorLink(
                                        "Pixeldrain",
                                        "Pixeldrain",
                                        "https://pixeldrain.com/api/file/$pixelMatch?download",
                                    ) {
                                        this.referer = "$mainUrl/"
                                        this.quality = quality
                                    },
                                )
                            }

                            val embedUrl = fixEmbed(adsLink) ?: return@ads
                            coroutineScope {
                                loadExtractor(
                                    embedUrl,
                                    "$mainUrl/",
                                    subtitleCallback,
                                ) { link ->
                                    launch(Dispatchers.IO) {
                                        countingCallback(
                                            newExtractorLink(
                                                link.name,
                                                link.name,
                                                link.url,
                                                link.type,
                                            ) {
                                                this.referer = link.referer
                                                this.quality =
                                                    if (link.type == ExtractorLinkType.M3U8) {
                                                        link.quality
                                                    } else {
                                                        quality
                                                    }
                                                this.headers = link.headers
                                                this.extractorData = link.extractorData
                                            },
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("Nekopoi", "resolveStreamLinks: ouo/embed pipeline error", e)
                        }
                    }
                }
            }.onFailure { Log.e("Nekopoi", "download/ouo block failed", it) }
        }
    }

    val total = linkCount.get()
    Log.d("Nekopoi", "resolveStreamLinks: total links registered = $total")
    return total > 0
}

// FIXED: BUG4 - removed APIHolder.getCaptchaToken() (requires UI interaction,
// silently returns null under Kototoro's headless plugin runtime). New flow:
//   1. Follow redirects directly via app.get with a real UA + Referer.
//   2. If we land off ouo.io, return that final URL.
//   3. Otherwise submit the form WITHOUT the x-token captcha field and read
//      the Location header from the no-redirect response.
internal suspend fun bypassOuo(url: String?): String? {
    if (url == null) return null
    return try {
        val res = app.get(
            url,
            headers = mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to DOMAIN,
            ),
            allowRedirects = true,
        )
        if (!res.url.contains("ouo.io")) return res.url

        val doc = res.document
        val nextUrl = doc.select("form").attr("action")
        val data =
            doc
                .select("form input")
                .associate { it.attr("name") to it.attr("value") }
                .toMutableMap()
        data.remove("x-token")

        val post = app.post(
            nextUrl,
            data = data,
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer" to url,
            ),
            allowRedirects = false,
        )
        post.headers["location"]?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}

private fun NiceResponse.selectMirror(): String? = this.document
    .selectFirst("script:containsData(#passcheck)")
    ?.data()
    ?.substringAfter("\"GET\", \"")
    ?.substringBefore("\"")

// FIXED: BUG1 (followup) - bypassMirrored also routed through session.
// Switching to app.get to keep the same Kototoro-safe path.
internal suspend fun bypassMirrored(url: String?): List<String?> {
    val request = app.get(url ?: return emptyList(), headers = baseHeaders)
    delay(2000)
    val mirrorUrl =
        request.selectMirror() ?: run {
            val nextUrl = request.document.select("div.col-sm.centered.extra-top a").attr("href")
            app.get(nextUrl, headers = baseHeaders).selectMirror()
        }
    return app
        .get(
            fixUrl(
                mirrorUrl ?: return emptyList(),
                mirroredHost,
            ),
            headers = baseHeaders,
        ).document
        .select("table.hoverable tbody tr")
        .filter { mirror ->
            !mirrorIsBlackList(mirror.selectFirst("img")?.attr("alt"))
        }.amap {
            val fileLink = it.selectFirst("a")?.attr("href")
            app
                .get(
                    fixUrl(
                        fileLink ?: return@amap null,
                        mirroredHost,
                    ),
                    headers = baseHeaders,
                ).document
                .selectFirst("div.code_wrap code")
                ?.text()
        }
}

