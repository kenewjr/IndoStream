package com.anoboy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicInteger

class Anoboy : MainAPI() {
    override var mainUrl = DOMAIN
    override var name = "Anoboy"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes =
        setOf(
            TvType.Anime,
            TvType.AnimeMovie,
            TvType.OVA,
        )

    companion object {
        // Single point of domain rotation. Change here when the site moves.
        const val DOMAIN = "https://anoboy.my.id"

        // Default embed shell host used when the page's `xd=` token is missing or unparseable.
        const val EMBED_FALLBACK = "https://ww1.anoboy.boo"

        val baseHeaders =
            mapOf(
                "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
            )

        fun getType(t: String): TvType = if (t.contains("OVA", true) || t.contains("Special", true)) {
            TvType.OVA
        } else if (t.contains("Movie", true)) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        // Kototoro R8 may strip enum values; runCatching + nullable return guards.
        fun getStatus(t: String): ShowStatus? = runCatching {
            when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> null
            }
        }.getOrNull()
    }

    override val mainPage =
        mainPageOf(
            "$mainUrl/page/" to "Latest Release",
            "$mainUrl/ongoing/page/" to "Ongoing",
            "$mainUrl/complete/page/" to "Complete",
            "$mainUrl/movies/page/" to "Movie",
            "$mainUrl/live-action/page/" to "Live Action",
        )

    /**
     * Wraps app.get with up to [maxRetries] attempts, uniform headers, and a 30s timeout.
     * Returns null instead of throwing so callsites stay null-safe.
     */
    private suspend fun safeGet(
        url: String,
        referer: String? = "$mainUrl/",
        maxRetries: Int = 3,
    ): com.lagradost.nicehttp.NiceResponse? {
        var lastError: Throwable? = null
        repeat(maxRetries) { attempt ->
            try {
                return app.get(
                    url,
                    referer = referer,
                    headers = baseHeaders,
                )
            } catch (t: Throwable) {
                lastError = t
                if (attempt < maxRetries - 1) {
                    kotlinx.coroutines.delay(700L * (attempt + 1))
                }
            }
        }
        com.lagradost.cloudstream3.mvvm.logError(
            (lastError ?: Exception("Anoboy safeGet failed: $url")),
        )
        return null
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val document =
            safeGet("${request.data}$page/")?.document
                ?: return newHomePageResponse(request.name, emptyList())

        val home = document.select("div.xrelated").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = this.selectFirst("a.nwa") ?: this.selectFirst("a[href]") ?: return null
        val href =
            anchor.attr("href").let { h ->
                if (h.startsWith("http")) h else "$mainUrl${if (h.startsWith("/")) h else "/$h"}"
            }
        val title =
            anchor.selectFirst("div.titlelist")?.text()?.trim()
                ?: anchor.selectFirst("img")?.attr("alt")?.trim()
                ?: return null
        val poster =
            anchor.selectFirst("img")?.let {
                it.attr("src").takeIf { s -> s.isNotBlank() }
                    ?: it.attr("data-src")
            }
        val epNum =
            anchor
                .selectFirst("div.eplist")
                ?.text()
                ?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            addSub(epNum)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document =
            safeGet("$mainUrl/?s=$encoded")?.document ?: return emptyList()
        return document.select("div.xrelated").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title =
            document.selectFirst(".entry-title, h1.title-post")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: ""

        val poster =
            document
                .selectFirst(".thumbposter img, .thumbhd img, .post-thumb img")
                ?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val tags = document.select(".genxed a, .genre a, a[rel*=tag]").map { it.text() }
        val typeText =
            document
                .selectFirst("div.info-content .spe span:last-child")
                ?.ownText()
                ?.lowercase()
                ?: document
                    .select(".infolist li")
                    .firstOrNull { it.text().contains("Type", true) }
                    ?.text()
                ?: "tv"
        val statusText =
            document.selectFirst(".spe > span")?.ownText()
                ?: document.select(".infolist li").firstOrNull { it.text().contains("Status", true) }?.text()
                ?: "Completed"

        val description =
            document
                .select("div[itemprop=description], .sinops, .post-body p")
                .text()
                .trim()

        val episodes =
            document
                .select(".eplister ul li, ul.ulinklist li")
                .mapNotNull { ep ->
                    val anchor = ep.selectFirst("a") ?: return@mapNotNull null
                    val link =
                        anchor.attr("href").let { h ->
                            if (h.startsWith("http")) h else "$mainUrl${if (h.startsWith("/")) h else "/$h"}"
                        }
                    val epName = ep.selectFirst(".epl-title")?.text() ?: anchor.text().trim()
                    val epNum =
                        Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                            .find(epName)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()
                    newEpisode(link) {
                        this.name = epName
                        this.episode = epNum
                    }
                }.ifEmpty { listOf(newEpisode(url) { this.name = title }) }
                .reversed()

        return newAnimeLoadResponse(title, url, getType(typeText)) {
            engName = title
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(statusText)
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val document = safeGet(data)?.document ?: return false

            val embedHostRegex = Regex("""xd\s*=\s*['"]([A-Za-z0-9+/=]+)['"]""")
            val embedHost: String =
                embedHostRegex
                    .find(document.html())
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let {
                        runCatching {
                            String(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
                                .trim()
                                .trimEnd('/')
                        }.getOrNull()
                    }
                    ?: EMBED_FALLBACK

            fun resolve(raw: String): String? {
                val u = raw.trim().ifBlank { return null }
                return when {
                    u.startsWith("http") -> u
                    u.startsWith("//") -> "https:$u"
                    u.startsWith("/") -> "$embedHost$u"
                    else -> "$embedHost/$u"
                }
            }

            val iframeSources =
                document.select("iframe").mapNotNull { iframe ->
                    (
                        iframe.attr("data-src").takeIf { it.isNotBlank() }
                            ?: iframe.attr("data-litespeed-src").takeIf { it.isNotBlank() }
                            ?: iframe.attr("src").takeIf { it.isNotBlank() }
                        )?.let { resolve(it) }
                }

            val mirrorSources =
                document.select(".vmiror a[data-video]").mapNotNull { a ->
                    a.attr("data-video").takeIf { it.isNotBlank() }?.let { resolve(it) }
                }

            val allSources = (iframeSources + mirrorSources).distinct()
            if (allSources.isEmpty()) return false

            coroutineScope {
                allSources.forEach { src ->
                    launch {
                        runCatching {
                            if (src.contains("/uploads/adsbatch", ignoreCase = true) ||
                                src.contains("/uploads/yup/", ignoreCase = true)
                            ) {
                                val innerDoc = safeGet(src, referer = data)?.document
                                val realIframe =
                                    innerDoc?.selectFirst("iframe[src]")?.attr("src")
                                        ?: innerDoc?.selectFirst("iframe[data-src]")?.attr("data-src")
                                if (!realIframe.isNullOrBlank()) {
                                    val resolved = resolve(realIframe) ?: realIframe
                                    registerWithFallback(resolved, src, subtitleCallback, callback)
                                }
                            } else {
                                registerWithFallback(src, "$mainUrl/", subtitleCallback, callback)
                            }
                        }
                    }
                }
            }

            true
        } catch (e: Exception) {
            com.lagradost.cloudstream3.mvvm.logError(e)
            false
        }
    }

    private suspend fun registerWithFallback(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val resolvedCount = AtomicInteger(0)
        loadExtractor(url, referer, subtitleCallback) { link ->
            resolvedCount.incrementAndGet()
            callback.invoke(link)
        }
        if (resolvedCount.get() == 0) {
            val host =
                runCatching { java.net.URI(url).host }
                    .getOrNull()
                    ?.removePrefix("www.") ?: "Embed"
            callback.invoke(
                newExtractorLink(host, host, url) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                },
            )
        }
    }
}
