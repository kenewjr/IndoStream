package com.nekopoi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element
import java.net.URI

class Nekopoi : MainAPI() {
    override var mainUrl = "https://nekopoi.care"
    override var name = "Nekopoi"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    private val fetch by lazy { Session(app.baseClient) }
    override val supportedTypes = setOf(
        TvType.NSFW,
    )

    companion object {
        val session = Session(Requests().baseClient)
        val mirrorBlackList = arrayOf(
            "MegaupNet",
            "DropApk",
            "Racaty",
            "ZippyShare",
            "VideobinCo",
            "DropApk",
            "SendCm",
            "GoogleDrive",
        )
        const val mirroredHost = "https://www.mirrored.to"

        // Theme NekoPoi v2.x stores poster/thumbnail URLs as inline CSS, e.g.
        //   style="background-image: url('https://nekopoi.care/.../poster.jpg')"
        // This regex extracts the URL regardless of single, double, or no quotes.
        val backgroundImageRegex = Regex("""url\((?:['"])?(.*?)(?:['"])?\)""")

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

    }

    // Curated home rows. Each entry becomes a horizontally scrollable row in
    // CloudStream / fork apps, giving the user an "advanced browse" UX without
    // needing fork-specific filter APIs.
    //
    //  Group 1 — Format
    //  Group 2 — Status / freshness
    //  Group 3 — Popular tags (genre filters)
    override val mainPage = mainPageOf(
        // Format
        "$mainUrl/category/hentai/" to "Hentai",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/3d-hentai/" to "3D Hentai",
        "$mainUrl/category/jav-cosplay/" to "JAV Cosplay",
        // Status & freshness
        "$mainUrl/category/sub-indo/" to "Sub Indo",
        "$mainUrl/category/uncensored/" to "Uncensored",
        "$mainUrl/category/censored/" to "Censored",
        "$mainUrl/" to "Terbaru",
        // Popular genre tags (acts as quick filter rows)
        "$mainUrl/tag/big-tits/" to "Tag: Big Tits",
        "$mainUrl/tag/big-boobs/" to "Tag: Big Boobs",
        "$mainUrl/tag/schoolgirl/" to "Tag: Schoolgirl",
        "$mainUrl/tag/romance/" to "Tag: Romance",
        "$mainUrl/tag/vanilla/" to "Tag: Vanilla",
        "$mainUrl/tag/threesome/" to "Tag: Threesome",
        "$mainUrl/tag/maid/" to "Tag: Maid",
        "$mainUrl/tag/yuri/" to "Tag: Yuri",
        "$mainUrl/tag/ntr/" to "Tag: NTR",
        "$mainUrl/tag/anal/" to "Tag: Anal",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = fetch.get("${request.data}/page/$page").document
        // Theme NekoPoi v2.x renders both category and search listings inside
        // div.nk-search-results > ul > li. The previous "div.result ul li"
        // wrapper no longer exists in the rendered HTML.
        val home = document.select("div.nk-search-results ul li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("-episode-")) {
            val title = uri.substringAfter("$mainUrl/").substringBefore("-episode-")
                .removePrefix("new-release-").removePrefix("uncensored-")
            "$mainUrl/hentai/$title"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        // The link is now <a class="nk-search-item"> with the title inside
        // .nk-search-info h2 (h2 alone still works as a fallback).
        val link = this.selectFirst("a.nk-search-item") ?: this.selectFirst("a[href]") ?: return null
        val title = link.selectFirst(".nk-search-info h2")?.text()?.trim()
            ?: link.selectFirst("h2")?.text()?.trim()
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val href = getProperAnimeLink(link.attr("href"))

        // Posters are no longer plain <img>; they are CSS background-images on
        // div.nk-search-thumb (and similar containers used in related lists).
        val styleAttr = this.selectFirst(
            "div.nk-search-thumb, div.nk-related-thumb-crop, div.ltd"
        )?.attr("style").orEmpty()
        val posterFromStyle = backgroundImageRegex.find(styleAttr)?.groupValues?.getOrNull(1)
        val posterUrl = fixUrlNull(posterFromStyle ?: link.selectFirst("img")?.attr("src"))

        // Episode count now appears as .nk-episode-badge on related episode cards.
        val epNum = this.selectFirst("i.dot, .nk-episode-badge")
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        // Smart search dengan prefix filter:
        //   "tag:vanilla schoolgirl"     -> /tag/vanilla/?s=schoolgirl
        //   "category:jav big tits"      -> /category/jav/?s=big+tits
        //   "jav:cosplay"  / "hentai:"   -> shortcut ke kategori populer
        // Tanpa prefix, fallback ke WordPress search standar.
        val (path, residue) = parseSearchPrefix(query)
        val encoded = java.net.URLEncoder.encode(residue, "UTF-8")
        val target = if (path != null) {
            "$mainUrl$path?s=$encoded"
        } else {
            "$mainUrl/?s=$encoded&post_type=anime"
        }
        return fetch.get(target)
            .document
            .select("div.nk-search-results ul li")
            .mapNotNull { it.toSearchResult() }
    }

    private fun parseSearchPrefix(query: String): Pair<String?, String> {
        val trimmed = query.trim()
        val colon = trimmed.indexOf(':').takeIf { it in 1..15 } ?: return null to trimmed
        val prefix = trimmed.substring(0, colon).lowercase()
        val rest = trimmed.substring(colon + 1).trim()
        val slug = rest.lowercase().replace(' ', '-').ifBlank { null }
        return when (prefix) {
            "tag" -> (slug?.let { "/tag/$it/" } to "")
            "category", "cat" -> (slug?.let { "/category/$it/" } to "")
            "jav" -> "/category/jav/" to rest
            "hentai" -> "/category/hentai/" to rest
            "3d" -> "/category/3d-hentai/" to rest
            "cosplay" -> "/category/jav-cosplay/" to rest
            "sub", "subindo" -> "/category/sub-indo/" to rest
            "uncensored" -> "/category/uncensored/" to rest
            "censored" -> "/category/censored/" to rest
            else -> null to trimmed
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetch.get(url).document

        // Title:
        //  * Series page (/hentai/<slug>/) -> .nk-section-header h1 inside .nk-series-info
        //    is the localized "Informasi Anime" header, so we read the synopsis <b>
        //    or fall back to the page <title>.
        //  * Episode page                  -> div.nk-post-header h1
        val title = document.selectFirst("div.nk-post-header h1")?.text()?.trim()
            ?: document.selectFirst("span.nk-series-synopsis b")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: ""

        // Poster:
        //  * Series page  -> div.nk-series-poster (background-image)
        //  * Episode page -> div.nk-featured-img img
        //  * Fallback     -> og:image meta tag, which the new theme always sets.
        val seriesPosterStyle = document.selectFirst("div.nk-series-poster")?.attr("style").orEmpty()
        val poster = fixUrlNull(
            backgroundImageRegex.find(seriesPosterStyle)?.groupValues?.getOrNull(1)
                ?: document.selectFirst("div.nk-featured-img img")?.attr("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        // Genres / synopsis live inside div.konten on episode pages and inside
        // .nk-series-detail / .nk-series-meta-list on series pages.
        val infoBlock = document.select("div.konten, div.nk-series-detail, div.nk-series-meta-list")
        val tags = infoBlock.select("p:contains(Genre), p:contains(Genres)")
            .firstOrNull()?.text()
            ?.substringAfter(":")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        // The new theme uses <b>Tayang</b>, <b>Status</b>, <b>Durasi</b> labels
        // inside <p> tags rather than <li>. The old <li> selectors still work
        // as a fallback for legacy markup, hence the comma-separated lists.
        val year = (infoBlock.select("p:contains(Tayang), li:contains(Tayang)")
            .firstOrNull()?.text() ?: "")
            .substringAfterLast(",")
            .filter { it.isDigit() }
            .toIntOrNull()
        val status = getStatus(
            (infoBlock.select("p:contains(Status), li:contains(Status)")
                .firstOrNull()?.text() ?: "")
                .substringAfter(":").trim()
        )
        val duration = (infoBlock.select("p:contains(Durasi), li:contains(Durasi)")
            .firstOrNull()?.text() ?: "")
            .substringAfterLast(":")
            .filter { it.isDigit() }
            .toIntOrNull()

        // Synopsis: prefer the explicit series-synopsis span on series pages;
        // otherwise grab the first non-empty paragraph in div.konten.
        val description = document.selectFirst("span.nk-series-synopsis")?.text()?.trim()
            ?: document.select("div.konten p")
                .map { it.text().trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("Genre", true) }

        // Episodes:
        //  * Series page  -> a.nk-episode-card (the new grid layout)
        //  * Episode page -> falls back to a single self-link so playback still works.
        val episodes = document.select("a.nk-episode-card").mapNotNull { card ->
            val link = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            val badgeText = card.selectFirst(".nk-episode-badge")?.text()?.trim()
            val name = badgeText
                ?: card.selectFirst(".nk-episode-card-info")?.text()?.trim()
                ?: card.text().trim()
            // Per-episode thumbnail: <div class="nk-episode-card-thumb"
            // style="background-image: url('...')">. Reusing backgroundImageRegex
            // ensures we handle quoted/unquoted URLs uniformly.
            val thumbStyle = card.selectFirst("div.nk-episode-card-thumb")
                ?.attr("style").orEmpty()
            val episodePoster = fixUrlNull(
                backgroundImageRegex.find(thumbStyle)?.groupValues?.getOrNull(1)
            )
            // Parse "Ep 12" / "Episode 12" -> 12 for proper player labels.
            val epNum = badgeText?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }
            newEpisode(link) {
                this.name = name
                this.posterUrl = episodePoster
                this.episode = epNum
            }
        }.takeIf { it.isNotEmpty() } ?: listOf(newEpisode(url) { this.name = title })

        // Recommendations: theme baru menampilkan "Anime Lainnya" / "Related"
        // sebagai grid <div class="nk-related-list"> berisi <a> dengan thumbnail
        // dalam .nk-related-thumb (background-image). Kita re-use toSearchResult
        // supaya parsing poster style="background-image:" tetap konsisten.
        val recommendations = document.select(
            "div.nk-related-list a, div.nk-related a, div.related a"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(20)

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            engName = title
            posterUrl = poster
            this.year = year
            this.duration = duration
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val res = fetch.get(data).document

        runAllAsync(
                {
                    // Streaming iframes: the old "div#show-stream iframe" container is
                    // gone. The new theme renders each tab as a separate
                    // <div class="nk-player-frame"><iframe src="..."></iframe></div>
                    // inside #nk-player. We grab every iframe with a non-empty src.
                    res.select("#nk-player div.nk-player-frame iframe[src], div.nk-player-frame iframe[src]")
                        .amap { iframe ->
                            loadExtractor(iframe.attr("src"), "$mainUrl/", subtitleCallback, callback)
                        }
                },
                {
                    // Download grid:
                    //   <div class="nk-download-row">
                    //     <div class="nk-download-name">Title [720p]</div>
                    //     <div class="nk-download-links"><a href="ouo.io/...">Mirror</a> ...</div>
                    //   </div>
                    // Replaces the old div.boxdownload > div.liner / div.name structure.
                    res.select("div.nk-download-row").map { ele ->
                        getIndexQuality(ele.selectFirst("div.nk-download-name")?.text()) to
                            ele.selectFirst("div.nk-download-links a:contains(ouo)")?.attr("href")
                    }.filter { it.first != Qualities.P360.value }.map { (quality, ouoUrl) ->
                        val bypassedAds = bypassMirrored(bypassOuo(ouoUrl))
                        bypassedAds.amap ads@{ adsLink ->
                            coroutineScope {
                                loadExtractor(
                                    fixEmbed(adsLink).toString(),
                                    "$mainUrl/",
                                    subtitleCallback,
                                ) { link ->
                                    launch(Dispatchers.IO) {
                                        callback.invoke(
                                            newExtractorLink(
                                                link.name,
                                                link.name,
                                                link.url,
                                                link.type
                                            ) {
                                                this.referer = link.referer
                                                this.quality = if (link.type == ExtractorLinkType.M3U8) {
                                                    link.quality
                                                } else {
                                                    quality
                                                }
                                                this.headers = link.headers
                                                this.extractorData = link.extractorData
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            )

        return true
    }

    private fun fixEmbed(url: String?): String? {
        if (url == null) return null
        val host = getBaseUrl(url)
        return when {
            url.contains("streamsb", true) -> url.replace("$host/", "$host/e/")
            else -> url
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private suspend fun bypassOuo(url: String?): String? {
        var res = session.get(url ?: return null)
        run lit@{
            (1..2).forEach { _ ->
                if (res.headers["location"] != null) return@lit
                val document = res.document
                val nextUrl = document.select("form").attr("action")
                val data = document.select("form input").mapNotNull {
                    it.attr("name") to it.attr("value")
                }.toMap().toMutableMap()
                val captchaKey =
                    document.select("script[src*=https://www.google.com/recaptcha/api.js?render=]")
                        .attr("src").substringAfter("render=")
                val token = APIHolder.getCaptchaToken(url, captchaKey)
                data["x-token"] = token ?: ""
                res = session.post(
                    nextUrl,
                    data = data,
                    headers = mapOf("content-type" to "application/x-www-form-urlencoded"),
                    allowRedirects = false
                )
            }
        }

        return res.headers["location"]
    }

    private fun NiceResponse.selectMirror(): String? {
        return this.document.selectFirst("script:containsData(#passcheck)")?.data()
            ?.substringAfter("\"GET\", \"")?.substringBefore("\"")
    }

    private suspend fun bypassMirrored(url: String?): List<String?> {
        val request = session.get(url ?: return emptyList())
        delay(2000)
        val mirrorUrl = request.selectMirror() ?: run {
            val nextUrl = request.document.select("div.col-sm.centered.extra-top a").attr("href")
            app.get(nextUrl).selectMirror()
        }
        return session.get(
            fixUrl(
                mirrorUrl ?: return emptyList(),
                mirroredHost
            )
        ).document.select("table.hoverable tbody tr")
            .filter { mirror ->
                !mirrorIsBlackList(mirror.selectFirst("img")?.attr("alt"))
            }.amap {
                val fileLink = it.selectFirst("a")?.attr("href")
                session.get(
                    fixUrl(
                        fileLink ?: return@amap null,
                        mirroredHost
                    )
                ).document.selectFirst("div.code_wrap code")?.text()
            }
    }

    private fun mirrorIsBlackList(host: String?): Boolean {
        return mirrorBlackList.any { it.equals(host, true) }
    }

    private fun fixUrl(url: String, domain: String): String {
        if (url.startsWith("http")) {
            return url
        }
        if (url.isEmpty()) {
            return ""
        }

        val startsWithNoHttp = url.startsWith("//")
        if (startsWithNoHttp) {
            return "https:$url"
        } else {
            if (url.startsWith('/')) {
                return domain + url
            }
            return "$domain/$url"
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return when (val quality =
            Regex("""(?i)\[(\d+[pk])]""").find(str ?: "")?.groupValues?.getOrNull(1)?.lowercase()) {
            "2k" -> Qualities.P1440.value
            else -> getQualityFromName(quality)
        }
    }

}