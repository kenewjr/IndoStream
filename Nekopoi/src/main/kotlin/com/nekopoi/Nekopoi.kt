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

        // Matches inline CSS like: background-image: url('https://...')
        // Group 1 captures the URL with optional surrounding single/double quotes.
        val backgroundImageRegex = Regex("""background-image\s*:\s*url\(\s*['"]?([^'")]+)['"]?\s*\)""")

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

    }

    override val mainPage = mainPageOf(
        // Latest first
        "$mainUrl/" to "Terbaru",
        "$mainUrl/category/hentai/" to "Hentai",
        "$mainUrl/category/jav/" to "JAV",
        "$mainUrl/category/3d-hentai/" to "3D Hentai",
        "$mainUrl/category/2d-animation/" to "2D Animation",
        "$mainUrl/category/jav-cosplay/" to "JAV Cosplay",
        "$mainUrl/genres/big-oppai/" to "Genre: Big Oppai",
        "$mainUrl/genres/schoolgirl/" to "Genre: Schoolgirl",
        "$mainUrl/genres/vanilla/" to "Genre: Vanilla",
        "$mainUrl/genres/romance/" to "Genre: Romance",
        "$mainUrl/genres/shota/" to "Genre: Shota",
        "$mainUrl/genres/maid/" to "Genre: Maid",
        "$mainUrl/genres/yuri/" to "Genre: Yuri",
        "$mainUrl/genres/netorare/" to "Genre: Netorare",
        "$mainUrl/genres/saimin/" to "Genre: Saimin",
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
        val base = request.data.trimEnd('/')
        val document = fetch.get("$base/page/$page/").document
        val searchItems = document.select("div.nk-search-results ul li").ifEmpty {
            document.select("div.nk-search-results li")
        }.ifEmpty {
            document.select("div.result-list a[href], div.nk-result-list a[href], section.hasil a[href]")
        }.mapNotNull { it.toSearchResult() }

        val (home, isHorizontal) = if (searchItems.isNotEmpty()) {
            searchItems to false
        } else {
            val postCardItems = document.select(
                "div.nk-episodes-area #nk-episode-grid div.nk-post-card, " +
                "#nk-episode-grid div.nk-post-card, " +
                "div.nk-episodes-area div.nk-post-card"
            ).mapNotNull { it.toPostCardResult() }
            if (postCardItems.isNotEmpty()) {
                postCardItems to true
            } else {
                val gridItems = document.select("li:has(div.nk-grid-thumb)")
                    .mapNotNull { it.toGridThumbResult() }
                if (gridItems.isNotEmpty()) {
                    gridItems to true
                } else {
                    val broadItems = document.select("li:has(h2 a[href]), li:has(a[href] h2), article:has(h2 a[href])")
                        .mapNotNull { it.toBroadResult() }
                    broadItems to true
                }
            }
        }

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
    private fun extractTitleFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()

        val sinopsisIdx = trimmed.indexOf("Sinopsis", ignoreCase = true)
        if (sinopsisIdx > 0) {
            return trimmed.substring(0, sinopsisIdx).trim().ifBlank { null }
        }

        val subtitleIdx = trimmed.indexOf("Subtitle Indonesia", ignoreCase = true)
        if (subtitleIdx > 0) {
            return trimmed.substring(0, subtitleIdx).trim().ifBlank { null }
        }

        return if (trimmed.length <= 120) {
            trimmed
        } else {
            trimmed.substring(0, 100).trim()
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
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

        val imgSrc = this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val styleAttr = this.selectFirst(
            "div.nk-search-thumb, div.nk-related-thumb-crop, div.ltd"
        )?.attr("style").orEmpty()
        val posterFromStyle = backgroundImageRegex.find(styleAttr)?.groupValues?.getOrNull(1)
        val posterUrl = fixUrlNull(imgSrc ?: posterFromStyle)


        val epNum = this.selectFirst("i.dot, .nk-episode-badge")
            ?.text()?.filter { it.isDigit() }?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    private fun Element.toGridThumbResult(): AnimeSearchResponse? {
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

        val contentImg = document.selectFirst("div.konten img, article img, .entry-content img")
            ?.attr("src")?.takeIf { it.isNotBlank() }
        val seriesPosterStyle = document.selectFirst("div.nk-series-poster")?.attr("style").orEmpty()
        val posterFromStyle = backgroundImageRegex.find(seriesPosterStyle)?.groupValues?.getOrNull(1)
        val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
        val poster = fixUrlNull(contentImg ?: posterFromStyle ?: ogImage)

        val infoBlock = document.select(
            "div.konten, div.nk-series-detail, div.nk-series-meta-list, " +
            "div.info-anime, div.nk-info, section, article"
        )


        val genreElement = infoBlock.select("*:contains(Genre), *:contains(GENRE)")
            .firstOrNull { el ->
                val ownText = el.ownText().trim()
                ownText.contains("Genre", ignoreCase = true) || ownText.contains("GENRE")
            }
            ?: infoBlock.select("p:contains(Genre), li:contains(Genre), span:contains(Genre), div:contains(Genre)")
                .firstOrNull()
        val tags = if (genreElement != null) {
            val genreLinks = genreElement.select("a").map { it.text().trim() }.filter { it.isNotEmpty() }
            genreLinks.ifEmpty {
                genreElement.text()
                    .substringAfter(":")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
        } else emptyList()
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

        val statusText = infoBlock.select(
            "*:contains(STATUS), *:contains(Status), p:contains(Status), li:contains(Status)"
        ).firstOrNull { el ->
            val ownText = el.ownText().trim()
            ownText.contains("Status", ignoreCase = true) || ownText.contains("STATUS")
        }?.text()
            ?: infoBlock.select("p:contains(Status), li:contains(Status)").firstOrNull()?.text()
            ?: ""
        val status = getStatus(statusText.substringAfter(":").trim())

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

        val metadataPrefixes = listOf(
            "genre", "anime", "producers", "duration", "size", "catatan",
            "jenis", "status", "tayang", "durasi", "skor", "studio",
            "sinopsis", "unduh", "download"
        )
        val description = document.selectFirst("span.nk-series-synopsis")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: run {
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
                        !(text.contains(":") && text.indexOf(":") < 20)
                }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }

        val episodesFromCards = document.select("a.nk-episode-card").mapNotNull { card ->
            val link = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            val badgeText = card.selectFirst(".nk-episode-badge")?.text()?.trim()
            val name = badgeText
                ?: card.selectFirst(".nk-episode-card-info")?.text()?.trim()
                ?: card.text().trim()
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
        val episodesFromDaftarSection = if (episodesFromCards.isEmpty()) {
            // Find the heading element containing "DAFTAR EPISODE"
            val daftarHeading = document.select("h1, h2, h3, h4, h5, h6, b, strong, p")
                .firstOrNull { el ->
                    el.text().trim().contains("DAFTAR EPISODE", ignoreCase = true)
                }
            val episodeLinks = if (daftarHeading != null) {
                val parent = daftarHeading.parent()
                val linksInParent = parent?.select("a[href]")
                    ?.filter { it.attr("href").isNotBlank() }
                    ?: emptyList()
                linksInParent.ifEmpty {
                    val siblings = mutableListOf<Element>()
                    var sibling = daftarHeading.nextElementSibling()
                    while (sibling != null) {
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

            val trailingDateRegex = Regex("""\s+\d{1,2}\s+\w+\s+\d{4}\s*$""")
            val epNumberRegex = Regex("""(?:Ep|Episode)\s+(\d+)""", RegexOption.IGNORE_CASE)

            episodeLinks.mapNotNull { link ->
                val href = fixUrlNull(link.attr("href")) ?: return@mapNotNull null
                val rawText = link.text().trim()
                if (rawText.isBlank()) return@mapNotNull null
                val episodeName = rawText.replace(trailingDateRegex, "").trim()
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

        val relatedItems = document.select(
            "div.nk-related-list a, div.nk-related a, div.related a"
        ).mapNotNull { it.toSearchResult() }

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
                    val downloadPairs = mutableListOf<Pair<Int, String>>()
                    val legacyRows = res.select("div.nk-download-row")
                    if (legacyRows.isNotEmpty()) {
                        legacyRows.mapNotNull { ele ->
                            val quality = getIndexQuality(ele.selectFirst("div.nk-download-name")?.text())
                            val href = ele.selectFirst("div.nk-download-links a[href*=ouo]")?.attr("href")
                                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            downloadPairs.add(quality to href)
                        }
                    }
                    if (downloadPairs.isEmpty()) {
                        // Find the "UNDUH" heading element
                        val unduhHeading = res.select("h1, h2, h3, h4, h5, h6, b, strong, p")
                            .firstOrNull { el ->
                                el.text().trim().equals("UNDUH", ignoreCase = true)
                            }

                        if (unduhHeading != null) {
                            val resolutionRegex = Regex("""\[(\d+[pk]|4K)\]""", RegexOption.IGNORE_CASE)
                            val downloadContainer = unduhHeading.parent()
                            val allElements = downloadContainer?.children() ?: unduhHeading.siblingElements()

                            // Track whether we've passed the UNDUH heading
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
                                    !elementText.contains("ouo", ignoreCase = true)) {
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

                    downloadPairs
                        .map { (quality, ouoUrl) ->
                            
                            if (ouoUrl.isBlank() || !ouoUrl.contains("ouo.io")) return@map
                            val destinationUrl = try {
                                bypassOuo(ouoUrl)
                            } catch (e: Exception) {
                                
                                null
                            }
                            if (destinationUrl.isNullOrBlank()) return@map

                          
                            val isMirroredUrl = destinationUrl.contains("mirrored.to", ignoreCase = true)

                            val fileLinks: List<String> = if (isMirroredUrl) {
                              
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
                                  
                                    val pixelMatch = Regex("pixeldrain\\.com/u/([\\w-]+)")
                                        .find(adsLink)?.groupValues?.getOrNull(1)
                                    if (pixelMatch != null) {
                                        callback.invoke(
                                            newExtractorLink(
                                                "Pixeldrain",
                                                "Pixeldrain",
                                                "https://pixeldrain.com/api/file/$pixelMatch?download"
                                            ) {
                                                this.referer = "$mainUrl/"
                                                this.quality = quality
                                            }
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