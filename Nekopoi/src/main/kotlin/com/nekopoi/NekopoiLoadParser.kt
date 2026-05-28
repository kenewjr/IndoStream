package com.nekopoi

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import org.jsoup.nodes.Element

private val metadataPrefixes =
    listOf(
        "genre", "anime", "producers", "duration", "size", "catatan",
        "jenis", "status", "tayang", "durasi", "skor", "studio",
        "sinopsis", "unduh", "download",
    )

internal suspend fun Nekopoi.parseLoadPage(url: String): LoadResponse {
    Log.d("Nekopoi", "parseLoadPage: url=$url")
    val document = safeGet(url)?.document
        ?: run {
            Log.e("Nekopoi", "parseLoadPage: safeGet returned null for $url")
            throw ErrorLoadingException("Nekopoi page unreachable: $url")
        }
    Log.d("Nekopoi", "parseLoadPage: page fetched, body length=${document.text().length}")

    val title =
        document.selectFirst("div.nk-post-header h1")?.text()?.trim()
            ?: document.selectFirst("span.nk-series-synopsis b")?.text()?.trim()
            ?: document
                .selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.trim()
                ?.removeSuffix(" - Nekopoi")
                ?.removeSuffix(" – Nekopoi")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst("title")
                ?.text()
                ?.trim()
                ?.removeSuffix(" - Nekopoi")
                ?.removeSuffix(" – Nekopoi")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: document
                .select("h1")
                .firstOrNull { el ->
                    val text = el.text().trim()
                    text.isNotBlank() &&
                        !text.equals("INFORMASI ANIME", ignoreCase = true) &&
                        !text.contains("kali", ignoreCase = true)
                }?.text()
                ?.trim()
            ?: ""

    val contentImg =
        document
            .selectFirst("div.konten img, article img, .entry-content img")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
    val seriesPosterStyle = document.selectFirst("div.nk-series-poster")?.attr("style").orEmpty()
    val posterFromStyle = backgroundImageRegex.find(seriesPosterStyle)?.groupValues?.getOrNull(1)
    val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
    val poster = fixUrlNull(contentImg ?: posterFromStyle ?: ogImage)

    val infoBlock =
        document.select(
            "div.konten, div.nk-series-detail, div.nk-series-meta-list, " +
                "div.info-anime, div.nk-info, section, article",
        )

    val genreCandidates =
        infoBlock.select(
            "p:contains(Genre), li:contains(Genre), span:contains(Genre), div:contains(Genre), " +
                "p:contains(GENRE), li:contains(GENRE), span:contains(GENRE), div:contains(GENRE)",
        )
    val genreElement =
        genreCandidates.minByOrNull { it.text().length }
            ?: infoBlock.select("*:matchesOwn((?i)Genre)").firstOrNull()

    val tags =
        if (genreElement != null) {
            val genreLinks =
                genreElement
                    .select("a")
                    .map { it.text().trim() }
                    .filter { it.isNotEmpty() && !it.equals("Genre", ignoreCase = true) }
            genreLinks.ifEmpty {
                genreElement
                    .text()
                    .replace(Regex("(?i)genre\\s*:?"), "")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
        } else {
            emptyList()
        }
    val tayangText =
        infoBlock
            .select(
                "*:contains(TAYANG), *:contains(Tayang), p:contains(Tayang), li:contains(Tayang)",
            ).firstOrNull { el ->
                val ownText = el.ownText().trim()
                ownText.contains("Tayang", ignoreCase = true) || ownText.contains("TAYANG")
            }?.text()
            ?: infoBlock.select("p:contains(Tayang), li:contains(Tayang)").firstOrNull()?.text()
            ?: ""
    val year =
        Regex("(\\d{4})")
            .findAll(tayangText)
            .lastOrNull()
            ?.value
            ?.toIntOrNull()

    val statusText =
        infoBlock
            .select(
                "*:contains(STATUS), *:contains(Status), p:contains(Status), li:contains(Status)",
            ).firstOrNull { el ->
                val ownText = el.ownText().trim()
                ownText.contains("Status", ignoreCase = true) || ownText.contains("STATUS")
            }?.text()
            ?: infoBlock.select("p:contains(Status), li:contains(Status)").firstOrNull()?.text()
            ?: ""
    val status = getStatus(statusText.substringAfter(":").trim())

    val durasiText =
        infoBlock
            .select(
                "*:contains(DURASI), *:contains(Durasi), *:contains(DURATION), " +
                    "p:contains(Durasi), li:contains(Durasi), p:contains(Duration), li:contains(Duration)",
            ).firstOrNull { el ->
                val ownText = el.ownText().trim()
                ownText.contains("Durasi", ignoreCase = true) ||
                    ownText.contains("DURASI") ||
                    ownText.contains("Duration", ignoreCase = true) ||
                    ownText.contains("DURATION")
            }?.text()
            ?: infoBlock.select("p:contains(Durasi), li:contains(Durasi)").firstOrNull()?.text()
            ?: ""
    val duration =
        durasiText
            .substringAfter(":")
            .let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }
    val skorText =
        infoBlock
            .select(
                "*:contains(SKOR), *:contains(Skor), p:contains(Skor), li:contains(Skor)",
            ).firstOrNull { el ->
                val ownText = el.ownText().trim()
                ownText.contains("Skor", ignoreCase = true) || ownText.contains("SKOR")
            }?.text()
            ?: infoBlock.select("p:contains(Skor), li:contains(Skor)").firstOrNull()?.text()
            ?: ""
    val score =
        skorText
            .substringAfter(":")
            .trim()
            .takeIf { it.isNotBlank() }

    val description =
        document
            .selectFirst("span.nk-series-synopsis")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: run {
                val allElements =
                    document.select(
                        "div.konten, div.nk-series-detail, article, .entry-content",
                    )
                val sinopsisEl =
                    allElements
                        .select("*:contains(SINOPSIS), *:contains(Sinopsis)")
                        .firstOrNull { el ->
                            val ownText = el.ownText().trim()
                            ownText.contains("SINOPSIS", ignoreCase = true)
                        }
                if (sinopsisEl != null) {
                    val afterColon =
                        sinopsisEl
                            .text()
                            .substringAfter(":", "")
                            .trim()
                            .takeIf { it.isNotBlank() }
                    afterColon ?: sinopsisEl
                        .nextElementSibling()
                        ?.text()
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
            ?: document
                .select("div.konten p, article p, .entry-content p")
                .map { it.text().trim() }
                .firstOrNull { text ->
                    text.isNotEmpty() &&
                        metadataPrefixes.none { prefix -> text.startsWith(prefix, ignoreCase = true) } &&
                        !(text.contains(":") && text.indexOf(":") < 20)
                }
            ?: document
                .selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

    val episodes = parseEpisodes(document, url, title)
    Log.d("Nekopoi", "parseLoadPage: title='$title' episodes.size=${episodes.size}")
    val recommendations = parseRecommendations(document, title, poster)

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
        runCatching { addScore(score) }
    }
}

private fun MainAPI.parseEpisodes(
    document: org.jsoup.nodes.Document,
    url: String,
    title: String,
): List<com.lagradost.cloudstream3.Episode> {
    val episodesFromCards =
        document.select("a.nk-episode-card").mapNotNull { card ->
            val link = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
            val badgeText = card.selectFirst(".nk-episode-badge")?.text()?.trim()
            val name =
                badgeText
                    ?: card.selectFirst(".nk-episode-card-info")?.text()?.trim()
                    ?: card.text().trim()
            val thumbStyle =
                card
                    .selectFirst("div.nk-episode-card-thumb")
                    ?.attr("style")
                    .orEmpty()
            val episodePoster =
                fixUrlNull(
                    backgroundImageRegex.find(thumbStyle)?.groupValues?.getOrNull(1),
                )

            val epNum = badgeText?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }
            newEpisode(link) {
                this.name = name
                this.posterUrl = episodePoster
                this.episode = epNum
            }
        }
    val episodesFromDaftarSection =
        if (episodesFromCards.isEmpty()) {
            val daftarHeading =
                document
                    .select("h1, h2, h3, h4, h5, h6, b, strong, p")
                    .firstOrNull { el ->
                        el.text().trim().contains("DAFTAR EPISODE", ignoreCase = true)
                    }
            val episodeLinks =
                if (daftarHeading != null) {
                    val parent = daftarHeading.parent()
                    val linksInParent =
                        parent
                            ?.select("a[href]")
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
                val epNum =
                    epNumberRegex
                        .find(episodeName)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()

                newEpisode(href) {
                    this.name = episodeName
                    this.episode = epNum
                }
            }
        } else {
            emptyList()
        }

    Log.d(
        "Nekopoi",
        "parseEpisodes: cards=${episodesFromCards.size} daftar=${episodesFromDaftarSection.size}",
    )
    return episodesFromCards
        .ifEmpty { episodesFromDaftarSection }
        .takeIf { it.isNotEmpty() }
        ?: run {
            Log.w("Nekopoi", "parseEpisodes: no episodes parsed, falling back to single self-link for $url")
            listOf(newEpisode(url) { this.name = title })
        }
}

private fun MainAPI.parseRecommendations(
    document: org.jsoup.nodes.Document,
    title: String,
    poster: String?,
): List<com.lagradost.cloudstream3.SearchResponse> {
    val relatedItems =
        document
            .select(
                "div.nk-related-list a, div.nk-related a, div.related a",
            ).mapNotNull { toSearchResult(it) }

    val seriLink =
        document
            .select("a[href*=/hentai/]")
            .firstOrNull { el -> el.text().trim().startsWith("SERI", ignoreCase = true) }
    val seriResult =
        seriLink?.let { link ->
            val href = fixUrlNull(link.attr("href")) ?: return@let null
            val seriTitle =
                link
                    .text()
                    .trim()
                    .removePrefix("SERI")
                    .removePrefix("seri")
                    .trim()
                    .takeIf { it.isNotBlank() } ?: title
            newAnimeSearchResponse(seriTitle, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }

    val lainnyaItems =
        document
            .select(
                "div:has(h2:contains(LAINNYA)) a[href], " +
                    "div:has(h3:contains(LAINNYA)) a[href], " +
                    "section:has(h2:contains(LAINNYA)) a[href], " +
                    "section:has(h3:contains(LAINNYA)) a[href]",
            ).mapNotNull { toSearchResult(it) }

    return (listOfNotNull(seriResult) + relatedItems + lainnyaItems)
        .distinctBy { it.url }
        .take(20)
}

