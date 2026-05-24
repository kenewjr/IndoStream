package com.anoboy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Anoboy : MainAPI() {
    // [VERIFIED]: anoboy.my.id aktif per audit Mei 2026.
    override var mainUrl = "https://anoboy.my.id"
    override var name = "Anoboy"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    // [FIX]: endpoint /my-ajax sudah 404 — pakai HTML scraping langsung.
    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest Release",
        "$mainUrl/ongoing/page/" to "Ongoing",
        "$mainUrl/complete/page/" to "Complete",
        "$mainUrl/movies/page/" to "Movie",
        "$mainUrl/live-action/page/" to "Live Action",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // [FIX]: invalid JSON karena /my-ajax 404 → parse HTML langsung.
        val document = runCatching {
            app.get("${request.data}$page/").document
        }.getOrNull() ?: return newHomePageResponse(request.name, emptyList())

        // [UPDATED SELECTOR]: kartu sekarang div.xrelated > a.nwa
        val home = document.select("div.xrelated").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val anchor = this.selectFirst("a.nwa") ?: this.selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").let { h ->
            if (h.startsWith("http")) h else "$mainUrl${if (h.startsWith("/")) h else "/$h"}"
        }
        val title = anchor.selectFirst("div.titlelist")?.text()?.trim()
            ?: anchor.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val poster = anchor.selectFirst("img")?.let {
            it.attr("src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("data-src")
        }
        val epNum = anchor.selectFirst("div.eplist")?.text()
            ?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            addSub(epNum)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val document = runCatching {
            app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        }.getOrNull() ?: return emptyList()
        return document.select("div.xrelated").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".entry-title, h1.title-post")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: ""

        val poster = document.selectFirst(".thumbposter img, .thumbhd img, .post-thumb img")
            ?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val tags = document.select(".genxed a, .genre a, a[rel*=tag]").map { it.text() }
        val typeText = document.selectFirst("div.info-content .spe span:last-child")
            ?.ownText()?.lowercase()
            ?: document.select(".infolist li").firstOrNull { it.text().contains("Type", true) }
                ?.text()
            ?: "tv"
        val statusText = document.selectFirst(".spe > span")?.ownText()
            ?: document.select(".infolist li").firstOrNull { it.text().contains("Status", true) }?.text()
            ?: "Completed"

        val description = document.select("div[itemprop=description], .sinops, .post-body p")
            .text().trim()

        val episodes = document.select(".eplister ul li, ul.ulinklist li")
            .mapNotNull { ep ->
                val anchor = ep.selectFirst("a") ?: return@mapNotNull null
                val link = anchor.attr("href").let { h ->
                    if (h.startsWith("http")) h else "$mainUrl${if (h.startsWith("/")) h else "/$h"}"
                }
                val epName = ep.selectFirst(".epl-title")?.text() ?: anchor.text().trim()
                val epNum = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(link) {
                    this.name = epName
                    this.episode = epNum
                }
            }
            .ifEmpty { listOf(newEpisode(url) { this.name = title }) }
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
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data, referer = "$mainUrl/").document

            // Anoboy renders embed iframes with a relative path on `data-src`
            // (e.g. /uploads/adsbatch720.php?...). The actual host that serves
            // those embeds is encoded in an inline JS variable `xd` as base64
            // and prefixed at runtime via atob(xd). We decode that here so the
            // plugin keeps working when the host rotates.
            val embedHostRegex = Regex("""xd\s*=\s*['"]([A-Za-z0-9+/=]+)['"]""")
            val embedHost: String = embedHostRegex.find(document.html())
                ?.groupValues?.getOrNull(1)
                ?.let {
                    runCatching {
                        String(android.util.Base64.decode(it, android.util.Base64.DEFAULT))
                            .trim().trimEnd('/')
                    }.getOrNull()
                }
                ?: "https://ww1.anoboy.boo"

            fun resolve(raw: String): String? {
                val u = raw.trim().ifBlank { return null }
                return when {
                    u.startsWith("http") -> u
                    u.startsWith("//") -> "https:$u"
                    u.startsWith("/") -> "$embedHost$u"
                    else -> "$embedHost/$u"
                }
            }

            // Source 1: the primary <iframe id=mediaplayer> data-src
            val iframeSources = document.select("iframe").mapNotNull { iframe ->
                (iframe.attr("data-src").takeIf { it.isNotBlank() }
                    ?: iframe.attr("data-litespeed-src").takeIf { it.isNotBlank() }
                    ?: iframe.attr("src").takeIf { it.isNotBlank() })
                    ?.let { resolve(it) }
            }

            // Source 2: every mirror anchor inside .vmiror blocks (PC 720, 360,
            // YUp, etc.). data-video is the same kind of relative URL.
            val mirrorSources = document.select(".vmiror a[data-video]").mapNotNull { a ->
                a.attr("data-video").takeIf { it.isNotBlank() }?.let { resolve(it) }
            }

            val allSources = (iframeSources + mirrorSources).distinct()
            if (allSources.isEmpty()) return false

            allSources.amap { src ->
                runCatching {
                    if (src.contains("/uploads/adsbatch", ignoreCase = true) ||
                        src.contains("/uploads/yup/", ignoreCase = true)
                    ) {
                        // adsbatch / yup wrappers contain a nested real iframe
                        val innerDoc = app.get(src, referer = data).document
                        val realIframe = innerDoc.selectFirst("iframe[src]")?.attr("src")
                            ?: innerDoc.selectFirst("iframe[data-src]")?.attr("data-src")
                        if (!realIframe.isNullOrBlank()) {
                            val resolved = resolve(realIframe) ?: realIframe
                            loadExtractor(resolved, src, subtitleCallback, callback)
                        }
                    } else {
                        loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                    }
                }
            }

            true
        } catch (e: Exception) {
            false
        }
    }
}
