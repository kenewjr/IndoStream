package com.nekopoi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
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
    //  Group 1 — "Terbaru" first so latest episodes are immediately visible
    //  Group 2 — Format / Type categories (matches the site's main nav)
    //  Group 3 — Status / freshness sub-categories
    //  Group 4 — Popular genres pulled from the official /genre-list/ page.
    //            NOTE: Theme NekoPoi v2.x uses /genres/<slug>/ (plural) for
    //            genre archives — the legacy /tag/<slug>/ paths return empty
    //            results, which is why earlier rows looked broken.
    override val mainPage = mainPageOf(
        // Latest first
        "$mainUrl/" to "Terbaru",
        // Format / Type
        "$mainUrl/category/hentai/" to "Hentai",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/3d-hentai/" to "3D Hentai",
        "$mainUrl/category/2d-animation/" to "2D Animation",
        "$mainUrl/category/jav-cosplay/" to "JAV Cosplay",
        // Status & freshness
        "$mainUrl/category/sub-indo/" to "Sub Indo",
        "$mainUrl/category/uncensored/" to "Uncensored",
        "$mainUrl/category/censored/" to "Censored",
        // Popular genres (curated from /genre-list/)
        "$mainUrl/genres/big-oppai/" to "Genre: Big Oppai",
        "$mainUrl/genres/schoolgirl/" to "Genre: Schoolgirl",
        "$mainUrl/genres/vanilla/" to "Genre: Vanilla",
        "$mainUrl/genres/romance/" to "Genre: Romance",
        "$mainUrl/genres/threesome/" to "Genre: Threesome",
        "$mainUrl/genres/maid/" to "Genre: Maid",
        "$mainUrl/genres/yuri/" to "Genre: Yuri",
        "$mainUrl/genres/netorare/" to "Genre: Netorare",
        "$mainUrl/genres/anal/" to "Genre: Anal",
        "$mainUrl/genres/harem/" to "Genre: Harem",
        "$mainUrl/genres/milf/" to "Genre: MILF",
        "$mainUrl/genres/incest/" to "Genre: Incest",
        "$mainUrl/genres/futanari/" to "Genre: Futanari",
        "$mainUrl/genres/loli/" to "Genre: Loli",
        "$mainUrl/genres/uncensored/" to "Genre: Uncensored",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Build paginated URL. Trailing slash on request.data is normalized so
        // both "$mainUrl/" (Terbaru) and "$mainUrl/category/.../" produce a
        // valid "/page/N/" URL without double slashes.
        val base = request.data.trimEnd('/')
        val document = fetch.get("$base/page/$page/").document

        // The site has gone through multiple theme revisions. We try several
        // selector strategies in priority order to handle both current and
        // legacy markup:
        //
        //   Strategy 1 — Category/genre archives (div.nk-search-results > ul > li)
        //     Uses .nk-search-item cards with PORTRAIT posters (~210x300).
        //
        //   Strategy 2 — Current homepage "Episode Terbaru" grid
        //     Uses div.nk-post-card with div.nk-thumb-crop (background-image)
        //     and div.nk-post-meta containing h2 > a title and date.
        //     Thumbnails are HORIZONTAL (300x169).
        //
        //   Strategy 3 — Legacy homepage grid (li:has(div.nk-grid-thumb))
        //     Older theme layout, kept for backward compat.
        //
        //   Strategy 4 — Broad fallback for arbitrary h2-based item containers.

        // Strategy 1: Category/genre archive items
        // The "HASIL" section on category/genre pages may use different container
        // structures across theme revisions. Try the known class-based selector
        // first, then broaden to catch items rendered as plain <a> elements or
        // <li> wrappers inside any results container.
        val searchItems = document.select("div.nk-search-results ul li").ifEmpty {
            document.select("div.nk-search-results li")
        }.ifEmpty {
            // Broader fallback: look for a results section containing linked items.
            // The current theme renders HASIL items as <a> elements (possibly inside
            // a list or div container) that each hold title + synopsis text.
            document.select("div.result-list a[href], div.nk-result-list a[href], section.hasil a[href]")
        }.mapNotNull { it.toSearchResult() }

        val (home, isHorizontal) = if (searchItems.isNotEmpty()) {
            // Portrait posters (search/genre/category)
            searchItems to false
        } else {
            // Strategy 2: Current homepage layout — div.nk-post-card grid inside
            // the "Episode Terbaru" section. Each card has a thumb-crop background
            // image and a meta block with the h2 title link and date.
            val postCardItems = document.select("div.nk-episodes-area div.nk-post-card, #nk-episode-grid div.nk-post-card, div.nk-post-card")
                .mapNotNull { it.toPostCardResult() }
            if (postCardItems.isNotEmpty()) {
                postCardItems to true
            } else {
                // Strategy 3: Legacy homepage grid-thumb items
                val gridItems = document.select("li:has(div.nk-grid-thumb)")
                    .mapNotNull { it.toGridThumbResult() }
                if (gridItems.isNotEmpty()) {
                    gridItems to true
                } else {
                    // Strategy 4: Broad fallback — any list/article with an h2 link
                    val broadItems = document.select("li:has(h2 a[href]), li:has(a[href] h2), article:has(h2 a[href])")
                        .mapNotNull { it.toBroadResult() }
                    broadItems to true
                }
            }
        }

        // Real pagination state: NekoPoi renders a <nav class="navigation
        // pagination"> with an <a class="next page-numbers"> when more pages
        // exist. We also check for "Selanjutnya" link text (Indonesian for
        // "Next") which the current theme may use.
        val hasNext = home.isNotEmpty() &&
            document.selectFirst("a.next.page-numbers, .nav-links a.next, a:contains(Selanjutnya)") != null

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = isHorizontal
            ),
            hasNext = hasNext
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

    /**
     * Extracts a title from combined text content found on category/genre "HASIL"
     * pages. These pages render each item as an `<a>` element whose text is the
     * title followed by a synopsis snippet, e.g.:
     *
     *   "[4K] Valkyrie Choukyou ... Episode 2 Subtitle Indonesia Sinopsis : Anime ini..."
     *
     * We split on known delimiters ("Sinopsis", "Subtitle Indonesia") and take
     * the portion before the delimiter as the title. If no delimiter is found,
     * we take up to the first 100 characters to avoid overly long titles.
     */
    private fun extractTitleFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()

        // Try splitting on "Sinopsis" first (most reliable delimiter)
        val sinopsisIdx = trimmed.indexOf("Sinopsis", ignoreCase = true)
        if (sinopsisIdx > 0) {
            return trimmed.substring(0, sinopsisIdx).trim().ifBlank { null }
        }

        // Try splitting on "Subtitle Indonesia" (appears before synopsis in some items)
        val subtitleIdx = trimmed.indexOf("Subtitle Indonesia", ignoreCase = true)
        if (subtitleIdx > 0) {
            return trimmed.substring(0, subtitleIdx).trim().ifBlank { null }
        }

        // No known delimiter found — use the full text if it's reasonably short,
        // otherwise truncate to avoid absurdly long titles in the UI.
        return if (trimmed.length <= 120) {
            trimmed
        } else {
            trimmed.substring(0, 100).trim()
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        // The link is now <a class="nk-search-item"> with the title inside
        // .nk-search-info h2 (h2 alone still works as a fallback).
        // On category/genre "HASIL" pages, the element itself may be an <a>
        // containing the title + synopsis as combined text content.
        val link = if (this.tagName() == "a" && this.hasAttr("href")) {
            this
        } else {
            this.selectFirst("a.nk-search-item") ?: this.selectFirst("a[href]") ?: return null
        }
        val title = link.selectFirst(".nk-search-info h2")?.text()?.trim()
            ?: link.selectFirst("h2")?.text()?.trim()
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: extractTitleFromText(link.text())
            ?: return null
        val href = getProperAnimeLink(link.attr("href"))

        // Prefer direct <img> tag src (current site structure uses plain img tags
        // for thumbnails), then fall back to CSS background-image on known containers.
        val imgSrc = this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val styleAttr = this.selectFirst(
            "div.nk-search-thumb, div.nk-related-thumb-crop, div.ltd"
        )?.attr("style").orEmpty()
        val posterFromStyle = backgroundImageRegex.find(styleAttr)?.groupValues?.getOrNull(1)
        val posterUrl = fixUrlNull(imgSrc ?: posterFromStyle)

        // Episode count now appears as .nk-episode-badge on related episode cards.
        val epNum = this.selectFirst("i.dot, .nk-episode-badge")
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    /**
     * Parses an "Episode Terbaru" card from the homepage feed (and its
     * /page/N/ pagination). Markup looks like:
     *
     *   <ul>
     *     <li>
     *       <div class="nk-grid-thumb"
     *            style="background-image: url('...')">
     *         <a href="..." title="..."></a>
     *       </div>
     *       <div class="nk-jav-meta">
     *         <a href="..." title="..."><h2>Title</h2></a>
     *         <span><span class="dashicons ..."></span>Date</span>
     *       </div>
     *     </li>
     *   </ul>
     *
     * Different from .nk-search-item (no genre badges/synopsis) AND from the
     * legacy .nk-post-card sticky layout (which we ignore so the Terbaru row
     * doesn't surface ancient announcement posts like "Selamat Tahun Baru
     * 2022").
     */
    private fun Element.toGridThumbResult(): AnimeSearchResponse? {
        // Title comes from .nk-jav-meta h2; the link can live on either the
        // wrapping anchor in .nk-jav-meta or the empty anchor inside
        // .nk-grid-thumb. Prefer the meta link because it carries the title
        // attribute as a fallback.
        val metaLink = this.selectFirst("div.nk-jav-meta a[href]")
        val thumbLink = this.selectFirst("div.nk-grid-thumb a[href]")
        val link = metaLink ?: thumbLink ?: this.selectFirst("a[href]") ?: return null

        val title = this.selectFirst("div.nk-jav-meta h2")?.text()?.trim()
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: link.text().trim().ifBlank { null }
            ?: return null
        val href = getProperAnimeLink(link.attr("href"))

        // Prefer direct <img> tag src (current site structure uses plain img tags
        // for thumbnails), then fall back to CSS background-image on .nk-grid-thumb.
        val imgSrc = this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val thumbStyle = this.selectFirst("div.nk-grid-thumb")
            ?.attr("style").orEmpty()
        val posterFromStyle = backgroundImageRegex.find(thumbStyle)?.groupValues?.getOrNull(1)
        val poster = fixUrlNull(imgSrc ?: posterFromStyle)

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    /**
     * Parses a "Episode Terbaru" card from the current homepage layout.
     * Markup:
     *   <div class="nk-post-card">
     *     <div class="nk-post-thumb">
     *       <div class="nk-thumb-crop"
     *            style="background-image: url('https://nekopoi.care/.../thumb.jpg')"></div>
     *     </div>
     *     <div class="nk-post-meta">
     *       <h2><a href="...">Title</a></h2>
     *       <span><span class="dashicons ..."></span>Sabtu, 23 Mei 2026</span>
     *       <!-- optional series link span for episode posts -->
     *       <span><a href="https://nekopoi.care/hentai/<series>/">Series Name</a></span>
     *     </div>
     *   </div>
     */
    private fun Element.toPostCardResult(): AnimeSearchResponse? {
        val titleLink = this.selectFirst("div.nk-post-meta h2 a[href]")
            ?: this.selectFirst("h2 a[href]")
            ?: this.selectFirst("a[href]")
            ?: return null
        val title = titleLink.text().trim().ifBlank { null }
            ?: titleLink.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val href = getProperAnimeLink(titleLink.attr("href"))

        // Thumbnail comes from inline CSS background-image on .nk-thumb-crop
        val thumbStyle = this.selectFirst("div.nk-thumb-crop, div.nk-post-thumb [style*=background-image]")
            ?.attr("style").orEmpty()
        val posterFromStyle = backgroundImageRegex.find(thumbStyle)?.groupValues?.getOrNull(1)
        val imgSrc = this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val poster = fixUrlNull(posterFromStyle ?: imgSrc)

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    /**
     * Broad fallback parser for the current theme where neither
     * .nk-search-results nor .nk-grid-thumb selectors match.
     *
     * The current homepage renders "EPISODE TERBARU", "HENTAI TERBARU", and
     * "JAV TERBARU" sections where each item is a list element (or article)
     * containing:
     *   - An <a> with href pointing to the content page
     *   - An <h2> with the title text (inside or adjacent to the link)
     *   - An <img> with the thumbnail
     *   - Optional date text
     *
     * This parser is intentionally lenient to survive minor theme tweaks.
     */
    private fun Element.toBroadResult(): AnimeSearchResponse? {
        // Find the primary link — prefer one that wraps or is near the h2 title
        val h2El = this.selectFirst("h2") ?: return null
        val h2Link = h2El.selectFirst("a[href]") ?: h2El.parent()?.takeIf { it.tagName() == "a" && it.hasAttr("href") }
        val link = h2Link ?: this.selectFirst("a[href]") ?: return null

        val title = h2El.text().trim().ifBlank { null }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val href = getProperAnimeLink(link.attr("href"))

        // Thumbnail: direct <img> src first, then background-image style fallback
        val imgSrc = this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val styleAttr = this.selectFirst("[style*=background-image]")?.attr("style").orEmpty()
        val posterFromStyle = backgroundImageRegex.find(styleAttr)?.groupValues?.getOrNull(1)
        val poster = fixUrlNull(imgSrc ?: posterFromStyle)

        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        // Smart search dengan prefix filter:
        //   "genre:vanilla"              -> /genres/vanilla/   (alias: tag:)
        //   "category:jav big tits"      -> /category/jav/?s=big+tits
        //   "jav:cosplay"  / "hentai:"   -> shortcut ke kategori populer
        // Tanpa prefix, fallback ke WordPress search standar.
        // Note: /genres/<slug>/ archives tidak meng-honor query string ?s=,
        // jadi prefix genre/tag mengabaikan residue dan langsung load arsip.
        val (path, residue) = parseSearchPrefix(query)
        val encoded = java.net.URLEncoder.encode(residue, "UTF-8")
        val target = if (path != null) {
            "$mainUrl$path?s=$encoded"
        } else {
            "$mainUrl/?s=$encoded&post_type=anime"
        }
        return fetch.get(target)
            .document.let { doc ->
                doc.select("div.nk-search-results ul li").ifEmpty {
                    doc.select("div.nk-search-results li")
                }.ifEmpty {
                    doc.select("div.result-list a[href], div.nk-result-list a[href], section.hasil a[href]")
                }
            }
            .mapNotNull { it.toSearchResult() }
    }

    private fun parseSearchPrefix(query: String): Pair<String?, String> {
        val trimmed = query.trim()
        val colon = trimmed.indexOf(':').takeIf { it in 1..15 } ?: return null to trimmed
        val prefix = trimmed.substring(0, colon).lowercase()
        val rest = trimmed.substring(colon + 1).trim()
        val slug = rest.lowercase().replace(' ', '-').ifBlank { null }
        return when (prefix) {
            // Both "genre:" and the legacy "tag:" map to /genres/<slug>/.
            // The site dropped /tag/ archives in theme v2.x; keeping the alias
            // means existing user shortcuts keep working.
            "genre", "genres", "tag" -> (slug?.let { "/genres/$it/" } to "")
            "category", "cat" -> (slug?.let { "/category/$it/" } to "")
            "jav" -> "/category/jav/" to rest
            "hentai" -> "/category/hentai/" to rest
            "3d" -> "/category/3d-hentai/" to rest
            "2d" -> "/category/2d-animation/" to rest
            "cosplay" -> "/category/jav-cosplay/" to rest
            "sub", "subindo" -> "/category/sub-indo/" to rest
            "uncensored" -> "/category/uncensored/" to rest
            "censored" -> "/category/censored/" to rest
            else -> null to trimmed
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetch.get(url).document

        // Title extraction with multi-strategy fallback:
        //  * Episode page  -> div.nk-post-header h1 (e.g., "[4K] Euphoria Episode 1 Subtitle Indonesia")
        //  * Series page   -> The title appears as a standalone element in the "INFORMASI ANIME"
        //    section. It may be a <b> inside the synopsis span, or a separate text element.
        //    We also try the page <title> tag (stripping the site name suffix) and
        //    og:title meta as reliable fallbacks for series pages.
        val title = document.selectFirst("div.nk-post-header h1")?.text()?.trim()
            ?: document.selectFirst("span.nk-series-synopsis b")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.trim()?.removeSuffix(" - Nekopoi")?.removeSuffix(" – Nekopoi")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("title")?.text()?.trim()
                ?.removeSuffix(" - Nekopoi")?.removeSuffix(" – Nekopoi")?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: document.select("h1").firstOrNull { el ->
                // Skip h1 elements that are section headers (e.g., "INFORMASI ANIME")
                val text = el.text().trim()
                text.isNotBlank() && !text.equals("INFORMASI ANIME", ignoreCase = true)
                    && !text.contains("kali", ignoreCase = true) // skip view count headers
            }?.text()?.trim()
            ?: ""

        // Poster extraction with multi-strategy fallback:
        // 1. Direct <img> in content area (episode pages have featured images with direct URLs)
        // 2. CSS background-image on series poster container (series pages)
        // 3. og:image meta tag (always set by the theme, universal fallback)
        val contentImg = document.selectFirst("div.konten img, article img, .entry-content img")
            ?.attr("src")?.takeIf { it.isNotBlank() }
        val seriesPosterStyle = document.selectFirst("div.nk-series-poster")?.attr("style").orEmpty()
        val posterFromStyle = backgroundImageRegex.find(seriesPosterStyle)?.groupValues?.getOrNull(1)
        val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
        val poster = fixUrlNull(contentImg ?: posterFromStyle ?: ogImage)

        // Genres / metadata live inside div.konten on episode pages and inside
        // .nk-series-detail / .nk-series-meta-list on series pages.
        // The "INFORMASI ANIME" section uses labeled fields (e.g., "GENRE:", "STATUS:")
        // which may appear in various element types (<p>, <li>, <span>, <div>, or plain text).
        // We broaden the search to any element containing the label text.
        val infoBlock = document.select(
            "div.konten, div.nk-series-detail, div.nk-series-meta-list, " +
            "div.info-anime, div.nk-info, section, article"
        )

        // GENRE extraction: try <a> links first (series pages link genres to /genres/<slug>/),
        // then fall back to comma-separated text after the ":" delimiter.
        val genreElement = infoBlock.select("*:contains(Genre), *:contains(GENRE)")
            .firstOrNull { el ->
                // Only match elements where "Genre" appears as a label, not deeply nested
                val ownText = el.ownText().trim()
                ownText.contains("Genre", ignoreCase = true) || ownText.contains("GENRE")
            }
            ?: infoBlock.select("p:contains(Genre), li:contains(Genre), span:contains(Genre), div:contains(Genre)")
                .firstOrNull()
        val tags = if (genreElement != null) {
            // Prefer extracting from <a> links (series pages have linked genres)
            val genreLinks = genreElement.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
            genreLinks.ifEmpty {
                // Fall back to text parsing: split on comma after the ":" label
                genreElement.text()
                    .substringAfter(":")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
        } else emptyList()

        // TAYANG (year) extraction: find the element containing "TAYANG" or "Tayang",
        // then extract the 4-digit year from the date string (e.g., "Dec 11, 2011" -> 2011).
        val tayangText = infoBlock.select(
            "*:contains(TAYANG), *:contains(Tayang), p:contains(Tayang), li:contains(Tayang)"
        ).firstOrNull { el ->
            val ownText = el.ownText().trim()
            ownText.contains("Tayang", ignoreCase = true) || ownText.contains("TAYANG")
        }?.text()
            ?: infoBlock.select("p:contains(Tayang), li:contains(Tayang)").firstOrNull()?.text()
            ?: ""
        val year = Regex("(\\d{4})").findAll(tayangText)
            .lastOrNull()?.value?.toIntOrNull()

        // STATUS extraction: find element containing "STATUS" or "Status",
        // extract text after ":" and map to ShowStatus.
        val statusText = infoBlock.select(
            "*:contains(STATUS), *:contains(Status), p:contains(Status), li:contains(Status)"
        ).firstOrNull { el ->
            val ownText = el.ownText().trim()
            ownText.contains("Status", ignoreCase = true) || ownText.contains("STATUS")
        }?.text()
            ?: infoBlock.select("p:contains(Status), li:contains(Status)").firstOrNull()?.text()
            ?: ""
        val status = getStatus(statusText.substringAfter(":").trim())

        // DURASI / DURATION extraction: find element containing "DURASI", "Durasi", or "DURATION",
        // then extract the numeric minutes value (e.g., "30 menit" -> 30).
        val durasiText = infoBlock.select(
            "*:contains(DURASI), *:contains(Durasi), *:contains(DURATION), " +
            "p:contains(Durasi), li:contains(Durasi), p:contains(Duration), li:contains(Duration)"
        ).firstOrNull { el ->
            val ownText = el.ownText().trim()
            ownText.contains("Durasi", ignoreCase = true) || ownText.contains("DURASI")
                || ownText.contains("Duration", ignoreCase = true) || ownText.contains("DURATION")
        }?.text()
            ?: infoBlock.select("p:contains(Durasi), li:contains(Durasi)").firstOrNull()?.text()
            ?: ""
        val duration = durasiText.substringAfter(":")
            .let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }

        // SKOR (rating) extraction: find element containing "SKOR" or "Skor",
        // then extract the numeric rating value (e.g., "6.54").
        // CloudStream uses addScore() which accepts a string rating.
        val skorText = infoBlock.select(
            "*:contains(SKOR), *:contains(Skor), p:contains(Skor), li:contains(Skor)"
        ).firstOrNull { el ->
            val ownText = el.ownText().trim()
            ownText.contains("Skor", ignoreCase = true) || ownText.contains("SKOR")
        }?.text()
            ?: infoBlock.select("p:contains(Skor), li:contains(Skor)").firstOrNull()?.text()
            ?: ""
        val score = skorText.substringAfter(":")
            .trim().takeIf { it.isNotBlank() }

        // Synopsis extraction with multi-strategy fallback:
        //  1. Dedicated synopsis span (series pages with .nk-series-synopsis)
        //  2. Text after "SINOPSIS" / "Sinopsis" label (episode pages)
        //  3. First substantial paragraph in content area that isn't metadata
        //  4. og:description meta tag as final fallback
        val metadataPrefixes = listOf(
            "genre", "anime", "producers", "duration", "size", "catatan",
            "jenis", "status", "tayang", "durasi", "skor", "studio",
            "sinopsis", "unduh", "download"
        )
        val description = document.selectFirst("span.nk-series-synopsis")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: run {
                // Strategy 2: Look for text after "SINOPSIS" or "Sinopsis" label.
                // Episode pages render "SINOPSIS :" followed by the synopsis paragraph.
                val allElements = document.select(
                    "div.konten, div.nk-series-detail, article, .entry-content"
                )
                val sinopsisEl = allElements.select(
                    "*:contains(SINOPSIS), *:contains(Sinopsis)"
                ).firstOrNull { el ->
                    val ownText = el.ownText().trim()
                    ownText.contains("SINOPSIS", ignoreCase = true)
                }
                if (sinopsisEl != null) {
                    // The synopsis text may be in the next sibling element or after the ":"
                    val afterColon = sinopsisEl.text().substringAfter(":", "")
                        .trim().takeIf { it.isNotBlank() }
                    afterColon ?: sinopsisEl.nextElementSibling()?.text()?.trim()
                        ?.takeIf { it.isNotBlank() }
                } else null
            }
            ?: document.select("div.konten p, article p, .entry-content p")
                .map { it.text().trim() }
                .firstOrNull { text ->
                    text.isNotEmpty() &&
                        metadataPrefixes.none { prefix -> text.startsWith(prefix, ignoreCase = true) } &&
                        // Skip lines that look like "LABEL : value" metadata
                        // (short text before colon indicates a field label, not prose)
                        !(text.contains(":") && text.indexOf(":") < 20)
                }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }

        // Episodes:
        //  * Series page  -> a.nk-episode-card (the new grid layout)
        //  * Series page  -> "DAFTAR EPISODE" section with <a> links (current theme)
        //  * Episode page -> falls back to a single self-link so playback still works.
        //
        // Strategy 1: Try the class-based selector (legacy/newer grid layout)
        val episodesFromCards = document.select("a.nk-episode-card").mapNotNull { card ->
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
        }

        // Strategy 2: Find the "DAFTAR EPISODE" section and parse episode links.
        // The current theme renders episodes as <a> elements after a heading
        // containing "DAFTAR EPISODE". Each link's text is like:
        //   "Ep 1 [4K] Euphoria Episode 1 Subtitle Indonesia  15 Mei 2026"
        // We strip the trailing date and extract the episode number from "Ep N".
        val episodesFromDaftarSection = if (episodesFromCards.isEmpty()) {
            // Find the heading element containing "DAFTAR EPISODE"
            val daftarHeading = document.select("h1, h2, h3, h4, h5, h6, b, strong, p")
                .firstOrNull { el ->
                    el.text().trim().contains("DAFTAR EPISODE", ignoreCase = true)
                }
            // Collect <a> links that follow the heading — they may be siblings,
            // or contained in the same parent/section element.
            val episodeLinks = if (daftarHeading != null) {
                // Try: links within the same parent container as the heading
                val parent = daftarHeading.parent()
                val linksInParent = parent?.select("a[href]")
                    ?.filter { it.attr("href").isNotBlank() }
                    ?: emptyList()
                // If the parent only has the heading and no links, look at
                // the next sibling elements (the section after the heading)
                linksInParent.ifEmpty {
                    val siblings = mutableListOf<Element>()
                    var sibling = daftarHeading.nextElementSibling()
                    while (sibling != null) {
                        // Stop if we hit another section heading
                        if (sibling.tagName().matches(Regex("h[1-6]"))) break
                        siblings.addAll(sibling.select("a[href]"))
                        if (sibling.tagName() == "a" && sibling.hasAttr("href")) {
                            siblings.add(sibling)
                        }
                        sibling = sibling.nextElementSibling()
                    }
                    siblings.filter { it.attr("href").isNotBlank() }
                }
            } else {
                emptyList()
            }

            // Regex to strip trailing date like "15 Mei 2026" or "1 Jan 2025"
            val trailingDateRegex = Regex("""\s+\d{1,2}\s+\w+\s+\d{4}\s*$""")
            // Regex to extract episode number from "Ep N" or "Episode N" prefix
            val epNumberRegex = Regex("""(?:Ep|Episode)\s+(\d+)""", RegexOption.IGNORE_CASE)

            episodeLinks.mapNotNull { link ->
                val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val rawText = link.text().trim()
                if (rawText.isBlank()) return@mapNotNull null

                // Strip trailing date from the title
                val episodeName = rawText.replace(trailingDateRegex, "").trim()
                // Extract episode number from "Ep N" or "Episode N"
                val epNum = epNumberRegex.find(episodeName)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(href) {
                    this.name = episodeName
                    this.episode = epNum
                }
            }
        } else {
            emptyList()
        }

        val episodes = episodesFromCards.ifEmpty { episodesFromDaftarSection }
            .takeIf { it.isNotEmpty() } ?: listOf(newEpisode(url) { this.name = title })

        // Recommendations: theme baru menampilkan "Anime Lainnya" / "Related"
        // sebagai grid <div class="nk-related-list"> berisi <a> dengan thumbnail
        // dalam .nk-related-thumb (background-image). Kita re-use toSearchResult
        // supaya parsing poster style="background-image:" tetap konsisten.
        //
        // On Episode_Pages, we also look for:
        //  - "SERI <name>" link pointing back to the parent series page
        //  - "INFO LAINNYA" section (related episodes)
        //  - "HENTAI LAINNYA" section (related series)
        // This satisfies Requirement 6.6: attempt to link back to parent series.
        val relatedItems = document.select(
            "div.nk-related-list a, div.nk-related a, div.related a"
        ).mapNotNull { it.toSearchResult() }

        // Find "SERI" link on episode pages — links back to the parent series.
        // The link text is "SERI <SeriesName>" and href points to /hentai/<slug>/
        val seriLink = document.select("a[href*=/hentai/]")
            .firstOrNull { el -> el.text().trim().startsWith("SERI", ignoreCase = true) }
        val seriResult = seriLink?.let { link ->
            val href = fixUrlNull(link.attr("href")) ?: return@let null
            val seriTitle = link.text().trim()
                .removePrefix("SERI").removePrefix("seri").trim()
                .takeIf { it.isNotBlank() } ?: title
            newAnimeSearchResponse(seriTitle, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }

        // Collect links from "INFO LAINNYA" and "HENTAI LAINNYA" sections
        // (additional related content shown on episode pages)
        val lainnyaItems = document.select(
            "div:has(h2:contains(LAINNYA)) a[href], " +
            "div:has(h3:contains(LAINNYA)) a[href], " +
            "section:has(h2:contains(LAINNYA)) a[href], " +
            "section:has(h3:contains(LAINNYA)) a[href]"
        ).mapNotNull { it.toSearchResult() }

        val recommendations = (listOfNotNull(seriResult) + relatedItems + lainnyaItems)
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
            addScore(score)
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
                    // Streaming iframes: the site uses a tabbed player section with
                    // Server 1, Server 2, Server 3 tabs. Each tab has an ID like
                    // #nk-stream-1, #nk-stream-2, #nk-stream-3 and contains an iframe.
                    // We try multiple selector patterns to handle current and legacy markup:
                    //   1. Original class-based selectors (legacy theme)
                    //   2. Tab-based selectors using nk-stream-N IDs (current theme)
                    //   3. Generic selectors for any element with ID starting with "nk-stream"
                    //   4. Broader player container selectors
                    res.select(
                        "#nk-player div.nk-player-frame iframe[src], " +
                        "div.nk-player-frame iframe[src], " +
                        "#nk-stream-1 iframe, #nk-stream-2 iframe, #nk-stream-3 iframe, " +
                        "div[id^=nk-stream] iframe, " +
                        ".nk-player iframe[src], " +
                        ".player-embed iframe[src]"
                    ).mapNotNull { iframe ->
                        (iframe.attr("src").takeIf { it.isNotBlank() }
                            ?: iframe.attr("data-src").takeIf { it.isNotBlank() })
                    }
                        .mapNotNull { raw ->
                            // Normalize the URL: handle protocol-relative and relative paths
                            val normalized = when {
                                raw.startsWith("//") -> "https:$raw"
                                raw.startsWith("/") -> "$mainUrl$raw"
                                !raw.startsWith("http") -> "$mainUrl/$raw"
                                else -> raw
                            }
                            // Apply fixEmbed() to transform known embed formats (e.g., streamsb)
                            fixEmbed(normalized)
                        }
                        .distinct()
                        .amap { src ->
                            loadExtractor(src, "$mainUrl/", subtitleCallback, callback)
                        }
                },
                {
                    // Download section parsing with two strategies:
                    //
                    // Strategy 1 (legacy): Class-based selectors
                    //   <div class="nk-download-row">
                    //     <div class="nk-download-name">Title [720p]</div>
                    //     <div class="nk-download-links"><a href="ouo.io/...">Mirror</a> ...</div>
                    //   </div>
                    //
                    // Strategy 2 (current): "UNDUH" section with Resolution_Groups
                    //   The download section has an "UNDUH" heading followed by
                    //   resolution groups. Each group has a label containing a
                    //   resolution indicator in brackets (e.g., "[4K]", "[1080p]")
                    //   and multiple <a> links to ouo.io mirrors underneath.

                    // Collect (quality, ouoUrl) pairs from whichever strategy matches
                    val downloadPairs = mutableListOf<Pair<Int, String>>()

                    // Strategy 1: Legacy class-based selectors
                    val legacyRows = res.select("div.nk-download-row")
                    if (legacyRows.isNotEmpty()) {
                        legacyRows.mapNotNull { ele ->
                            val quality = getIndexQuality(ele.selectFirst("div.nk-download-name")?.text())
                            val href = ele.selectFirst("div.nk-download-links a[href*=ouo]")?.attr("href")
                                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            downloadPairs.add(quality to href)
                        }
                    }

                    // Strategy 2: "UNDUH" section with resolution groups
                    if (downloadPairs.isEmpty()) {
                        // Find the "UNDUH" heading element
                        val unduhHeading = res.select("h1, h2, h3, h4, h5, h6, b, strong, p")
                            .firstOrNull { el ->
                                el.text().trim().equals("UNDUH", ignoreCase = true)
                            }

                        if (unduhHeading != null) {
                            // Collect all elements after the UNDUH heading until the next
                            // major section heading. Resolution groups are identified by
                            // text containing "[4K]", "[1080p]", "[720p]", "[480p]", "[360p]".
                            val resolutionRegex = Regex("""\[(\d+[pk]|4K)\]""", RegexOption.IGNORE_CASE)

                            // Walk through siblings/children after the UNDUH heading to
                            // find resolution group containers. The structure may be:
                            //   - Sibling elements after the heading
                            //   - Children of the heading's parent container
                            val downloadContainer = unduhHeading.parent()
                            val allElements = downloadContainer?.children() ?: unduhHeading.siblingElements()

                            // Track whether we've passed the UNDUH heading
                            var pastUnduh = false
                            var currentQuality: Int? = null

                            for (element in allElements) {
                                // Skip until we pass the UNDUH heading
                                if (element == unduhHeading) {
                                    pastUnduh = true
                                    continue
                                }
                                if (!pastUnduh) continue

                                // Stop if we hit another major section heading (not a resolution label)
                                val elementText = element.text().trim()
                                if (element.tagName().matches(Regex("h[1-6]")) &&
                                    !resolutionRegex.containsMatchIn(elementText) &&
                                    elementText.isNotBlank() &&
                                    !elementText.contains("ouo", ignoreCase = true)) {
                                    break
                                }

                                // Check if this element contains a resolution indicator
                                val resMatch = resolutionRegex.find(elementText)
                                if (resMatch != null) {
                                    currentQuality = getIndexQuality(elementText)
                                }

                                // Collect all ouo.io links within this element
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

                        // Strategy 2b: If the UNDUH heading approach didn't work,
                        // try a broader search for any elements with resolution
                        // indicators that contain ouo.io links
                        if (downloadPairs.isEmpty()) {
                            val resolutionRegex = Regex("""\[(\d+[pk]|4K)\]""", RegexOption.IGNORE_CASE)
                            // Look for any container elements whose text matches a resolution pattern
                            res.select("*:matches(\\[\\d+[pk]\\]|\\[4K\\])").forEach { element ->
                                val elementText = element.text().trim()
                                if (resolutionRegex.containsMatchIn(elementText)) {
                                    val quality = getIndexQuality(elementText)
                                    // Get ouo links from this element or its parent
                                    val links = element.select("a[href*=ouo]")
                                    val effectiveLinks = if (links.isEmpty()) {
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

                    // Process all collected download pairs through OUO → (Mirrored or Direct) → Extractor.
                    // After OUO bypass, the destination URL may be:
                    //   a) A mirrored.to URL → needs bypassMirrored() to resolve actual file host URLs
                    //   b) A direct file host URL (KrakenFiles, Mp4Upload, Pixeldrain, etc.) → use directly
                    // Each step is wrapped in try-catch so that a failure on one mirror
                    // (network error, captcha failure, changed page structure) does not
                    // crash the entire download processing — we simply skip that mirror
                    // and continue with the remaining ones (Requirements 5.3, 5.4, 5.6).
                    downloadPairs
                        .filter { it.first != Qualities.P360.value }
                        .map { (quality, ouoUrl) ->
                            // Validate that the ouo.io URL looks correct before attempting bypass
                            if (ouoUrl.isBlank() || !ouoUrl.contains("ouo.io")) return@map
                            val destinationUrl = try {
                                bypassOuo(ouoUrl)
                            } catch (e: Exception) {
                                // OUO bypass failed (captcha, timeout, network error) — skip this mirror
                                null
                            }
                            if (destinationUrl.isNullOrBlank()) return@map

                            // Check if the OUO destination is a mirrored.to URL or a direct file host
                            val isMirroredUrl = destinationUrl.contains("mirrored.to", ignoreCase = true)

                            val fileLinks: List<String> = if (isMirroredUrl) {
                                // Route A: mirrored.to → bypass to get actual file host URLs
                                try {
                                    bypassMirrored(destinationUrl).filterNotNull().filter { it.isNotBlank() }
                                } catch (e: Exception) {
                                    // Mirrored.to bypass failed — skip this mirror
                                    emptyList()
                                }
                            } else {
                                // Route B: direct file host URL — use it directly
                                listOf(destinationUrl)
                            }

                            fileLinks.amap ads@{ adsLink ->
                                try {
                                    val embedUrl = fixEmbed(adsLink) ?: return@ads
                                    coroutineScope {
                                        loadExtractor(
                                            embedUrl,
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
                                } catch (e: Exception) {
                                    // Extractor failed for this link — skip and continue
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
        // Try bracketed format first: [720p], [1080p], [4K], [2K]
        // Then fall back to unbracketed: 720p, 1080p, 4K, 2K
        val quality = Regex("""(?i)\[(\d+[pk])]""").find(str ?: "")?.groupValues?.getOrNull(1)?.lowercase()
            ?: Regex("""(?i)(\d+[pk])""").find(str ?: "")?.groupValues?.getOrNull(1)?.lowercase()
        return when (quality) {
            "4k" -> Qualities.P2160.value
            "2k" -> Qualities.P1440.value
            "1080p" -> Qualities.P1080.value
            "720p" -> Qualities.P720.value
            "480p" -> Qualities.P480.value
            "360p" -> Qualities.P360.value
            else -> getQualityFromName(quality)
        }
    }

}