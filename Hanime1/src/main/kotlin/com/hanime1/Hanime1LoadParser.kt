package com.hanime1

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import org.jsoup.nodes.Document

/**
 * Hanime1 single-video page layout (well-known structure):
 *  - <h3 id="shareBtn-title">Title</h3>
 *  - <video id="player" poster="https://...">  ← poster URL
 *  - <div class="video-description-panel-content">description</div>
 *  - <a href="?tags%5B%5D=巨乳">巨乳</a>  ← tags as anchors with tags[] query
 *  - <a href="?genre=裏番">裏番</a>      ← genre anchor
 *  - <a class="related-video-content" href="/watch?v=...">  ← related (used as recs)
 *  - <a class="hentai-video-card-link" href="/watch?v=...">  ← episodes for series
 *
 * This parser is conservative: each section is wrapped in runCatching so a
 * partial DOM still produces a usable LoadResponse on Kototoro/CloudStream.
 */
internal suspend fun Hanime1Provider.parseLoadPage(url: String): LoadResponse {
    Log.d("Hanime1", "parseLoadPage: url=$url")
    val document = safeGet(url)?.document
        ?: run {
            Log.e("Hanime1", "parseLoadPage: safeGet returned null for $url")
            throw ErrorLoadingException("Hanime1 page unreachable: $url")
        }

    val title = parseTitle(document) ?: extractVideoIdFromUrl(url) ?: "Hanime1"
    val poster = parsePoster(document)
    val description = parseDescription(document)
    val tags = parseTags(document)
    val year = parseYear(document)
    val recommendations = parseRelated(document)
    val episodes = parseEpisodeList(document, url, title, poster)

    Log.d(
        "Hanime1",
        "parseLoadPage: title='$title' poster=${poster?.take(80)} " +
            "tags=${tags.size} eps=${episodes.size} recs=${recommendations.size}",
    )

    return newAnimeLoadResponse(title, url, TvType.NSFW) {
        engName = title
        posterUrl = poster
        this.year = year
        addEpisodes(DubStatus.Subbed, episodes)
        plot = description
        this.tags = tags
        this.recommendations = recommendations
    }
}

private fun parseTitle(doc: Document): String? {
    val raw =
        doc.selectFirst("h3#shareBtn-title")?.text()
            ?: doc.selectFirst("h1.video-title, h1#video-title, h3.video-title")?.text()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("title")?.text()
    return cleanTitle(raw)
}

private fun parsePoster(doc: Document): String? {
    val candidates =
        listOfNotNull(
            doc.selectFirst("video#player")?.attr("poster"),
            doc.selectFirst("video[poster]")?.attr("poster"),
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("link[rel=image_src]")?.attr("href"),
        )
    return candidates.firstOrNull { it.isNotBlank() }?.let { fixUrlOrNull(it) }
}

private fun parseDescription(doc: Document): String? {
    val raw =
        doc.selectFirst("div.video-description-panel-content")?.text()
            ?: doc.selectFirst("div.video-description-panel")?.text()
            ?: doc.selectFirst("div#video-description-panel")?.text()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
    return raw?.trim()?.takeIf { it.isNotBlank() }
}

private fun parseTags(doc: Document): List<String> {
    val tagAnchors =
        doc.select(
            "a[href*='tags%5B%5D='], a[href*='tags[]='], " +
                "a[href*='genre='], a[href*='?broad='], " +
                "div.single-video-tag a, div#tags a",
        )
    return tagAnchors
        .map { it.text().trim() }
        .filter { it.isNotEmpty() && it.length <= 24 }
        .distinct()
        .take(30)
}

private fun parseYear(doc: Document): Int? {
    val timeEl = doc.selectFirst("div#video-caption-text, time[datetime], div.video-caption-text")
    val text = timeEl?.text().orEmpty()
    return Regex("""(20\d{2})""").find(text)?.value?.toIntOrNull()
}

private fun MainAPI.parseRelated(doc: Document): List<SearchResponse> {
    val cards =
        doc.select(
            "a.related-video-content, a.hentai-video-card-link, " +
                "div.related-watch-wrap a[href*='/watch?v='], " +
                "div#related-tab a[href*='/watch?v=']",
        )
    return cards
        .mapNotNull { a ->
            val href = fixUrlOrNull(a.attr("href")) ?: return@mapNotNull null
            val img = a.selectFirst("img")
            val title =
                a.attr("title").takeIf { it.isNotBlank() }
                    ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: a.selectFirst(".related-video-title, .video-title")?.text()?.trim()
                    ?: return@mapNotNull null
            val poster =
                img?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
            newAnimeSearchResponse(
                cleanTitle(title) ?: title,
                href,
                TvType.NSFW,
            ) {
                this.posterUrl = fixUrlOrNull(poster)
            }
        }
        .distinctBy { it.url }
        .take(24)
}

private fun MainAPI.parseEpisodeList(
    doc: Document,
    selfUrl: String,
    title: String,
    poster: String?,
): List<Episode> {
    // Hanime1 series pages expose other episodes via "playlist" cards.
    // When viewing a single watch page, /playlists/<id> may also be embedded.
    val playlistCards =
        doc.select(
            "div.hentai-sex-position a.related-watch-wrap-link, " +
                "a.hentai-video-card-link, " +
                "div.playlist-watch-wrap a[href*='/watch?v=']",
        )

    val episodes =
        playlistCards
            .mapNotNull { card ->
                val href = fixUrlNull(card.attr("href")) ?: return@mapNotNull null
                if (!href.contains("/watch?v=")) return@mapNotNull null
                val img = card.selectFirst("img")
                val name =
                    img?.attr("alt")?.takeIf { it.isNotBlank() }
                        ?: card.attr("title").takeIf { it.isNotBlank() }
                        ?: card.text().trim().ifBlank { null }
                        ?: title
                val episodePoster =
                    img?.attr("src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                newEpisode(href) {
                    this.name = cleanTitle(name) ?: name
                    this.posterUrl = fixUrlOrNull(episodePoster) ?: poster
                    this.episode = parseEpisodeNumber(name)
                }
            }
            .distinctBy { it.data }

    return episodes.ifEmpty {
        listOf(newEpisode(selfUrl) { this.name = title })
    }
}
