package com.nekopoi

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Nekopoi : MainAPI() {
    override var mainUrl = "https://nekopoi.care"
    override var name = "Nekopoi"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage =
        mainPageOf(
            "$mainUrl/page/" to "Terbaru",
            "$mainUrl/category/hentai/page/" to "Hentai",
            "$mainUrl/category/jav/page/" to "JAV",
            "$mainUrl/category/3d-hentai/page/" to "3D Hentai",
            "$mainUrl/category/2d-animation/page/" to "2D Animation",
            "$mainUrl/category/jav-cosplay/page/" to "JAV Cosplay",
        )

    private suspend fun safeGet(url: String): org.jsoup.nodes.Document? =
        runCatching { app.get(url, referer = "$mainUrl/", timeout = 30L).document }.getOrNull()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (request.data.endsWith("/page/")) "${request.data}$page/" else request.data
        val doc =
            safeGet(url) ?: return newHomePageResponse(
                list = HomePageList(request.name, emptyList(), false),
                hasNext = false,
            )

        val items = doc.parsePosts()
        return newHomePageResponse(
            list = HomePageList(request.name, items, false),
            hasNext = items.isNotEmpty() && doc.selectFirst("a:contains(Selanjutnya), a.next") != null,
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = safeGet("$mainUrl/?s=$encoded") ?: return emptyList()
        return doc.parsePosts()
    }

    /**
     * Generic WordPress-style post discovery. Tries the strongest selector first
     * and falls back to bare `article` + `h2 > a` which works on virtually every
     * WP theme nekopoi.care has used.
     */
    private fun org.jsoup.nodes.Document.parsePosts(): List<AnimeSearchResponse> {
        val nodes =
            select("article.post, article[id^=post-]")
                .ifEmpty { select("div.post, div[id^=post-]") }
                .ifEmpty { select("h2:has(a[href*=\"$mainUrl\"])").mapNotNull { it.parent() } }

        return nodes.mapNotNull { it.toResult() }.distinctBy { it.url }
    }

    private fun Element.toResult(): AnimeSearchResponse? {
        val anchor =
            this.selectFirst("h2 > a, h2 a, .entry-title a, h3 a")
                ?: this.selectFirst("a[href][title]")
                ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.contains("nekopoi.care")) return null
        // Filter out menu/category links — actual posts have a slug-style path.
        if (href.endsWith("/category/") || href.contains("/category/") && !href.contains("-")) return null

        val title =
            anchor.text().trim().ifBlank { anchor.attr("title").trim() }
                .ifBlank { return null }

        val poster =
            this.selectFirst("img")?.let { img ->
                fixUrlNull(
                    img.attr("data-src").takeIf { it.isNotBlank() }
                        ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                        ?: img.attr("src"),
                )
            }

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc =
            safeGet(url) ?: return newAnimeLoadResponse(
                url.substringAfterLast("/").replace("-", " ").trim().ifBlank { "Untitled" },
                url,
                TvType.NSFW,
            ) { addEpisodes(DubStatus.Subbed, listOf(newEpisode(url))) }

        val title =
            doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: url.substringAfterLast("/").replace("-", " ").trim()

        val poster =
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("article img, .entry-content img")?.attr("src")

        val description =
            doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst(".entry-content p, article p")?.text()?.trim()

        // Many Nekopoi posts are single-episode; if a "DAFTAR EPISODE" list is
        // present, harvest links from it. Otherwise the page itself is the episode.
        val episodes =
            doc.select("a[href*=\"$mainUrl\"]:matchesOwn((?i)Episode\\s*\\d+)")
                .mapNotNull { a ->
                    val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                    val name = a.text().trim()
                    val num =
                        Regex("(?i)Episode\\s*(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
                    newEpisode(href) {
                        this.name = name
                        this.episode = num
                    }
                }.distinctBy { it.data }
                .ifEmpty { listOf(newEpisode(url) { this.name = title }) }

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = safeGet(data) ?: return false

        val iframes =
            doc.select("iframe[src], iframe[data-src], iframe[data-litespeed-src]")
                .mapNotNull { el ->
                    el.attr("data-src").takeIf { it.isNotBlank() }
                        ?: el.attr("data-litespeed-src").takeIf { it.isNotBlank() }
                        ?: el.attr("src").takeIf { it.isNotBlank() }
                }
                .map { raw ->
                    when {
                        raw.startsWith("http") -> raw
                        raw.startsWith("//") -> "https:$raw"
                        raw.startsWith("/") -> "$mainUrl$raw"
                        else -> "$mainUrl/$raw"
                    }
                }
                .distinct()

        if (iframes.isEmpty()) return false

        var any = false
        iframes.forEach { src ->
            runCatching {
                loadExtractor(src, "$mainUrl/", subtitleCallback) { link ->
                    any = true
                    callback.invoke(link)
                }
            }
        }
        return any
    }
}
