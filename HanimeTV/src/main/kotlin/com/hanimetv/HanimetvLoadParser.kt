package com.hanimetv

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import java.util.Calendar
import java.util.TimeZone

/**
 * Hanime.tv detail loader.
 *
 * `url` will look like https://hanime.tv/videos/hentai/<slug>. We extract the
 * slug, hit /api/v8/video?id=<slug>, then assemble a LoadResponse.
 *
 * Episodes come from `hentai_franchise_hentai_videos` (other entries in the
 * same franchise). If a title is a one-shot we just emit a single self-episode.
 */
internal suspend fun HanimetvProvider.parseLoadPage(url: String): LoadResponse {
    val slug = extractSlug(url)
        ?: throw ErrorLoadingException("Unable to derive slug from URL: $url")
    Log.d("HanimeTV", "parseLoadPage: slug=$slug")

    val apiUrl = "$API_BASE/video?id=$slug"
    val response = app.get(apiUrl, headers = apiHeaders, timeout = 30L)
    if (!response.isSuccessful) {
        throw ErrorLoadingException("HanimeTV API ${response.code}: $apiUrl")
    }

    val payload = response.parsedSafe<HanimeVideoResponse>()
        ?: throw ErrorLoadingException("HanimeTV API parse failure: $apiUrl")
    val video = payload.hentaiVideo
        ?: throw ErrorLoadingException("HanimeTV API missing hentai_video: $apiUrl")

    val title = video.name?.takeIf { it.isNotBlank() } ?: slug
    val poster = video.coverUrl?.takeIf { it.isNotBlank() }
        ?: video.posterUrl?.takeIf { it.isNotBlank() }
    val description = video.description?.let { stripHtml(it) }
    val tags =
        (video.tags ?: payload.hentaiTags)
            ?.mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotEmpty() } }
            ?.distinct()
            .orEmpty()
    val year = unixToYear(video.releasedAtUnix ?: video.createdAtUnix)

    val franchiseVideos = payload.hentaiFranchiseHentaiVideos.orEmpty()

    val episodes =
        if (franchiseVideos.size > 1) {
            franchiseVideos
                .sortedBy { it.releasedAtUnix ?: it.createdAtUnix ?: 0L }
                .mapIndexedNotNull { idx, ep ->
                    val epSlug = ep.slug ?: return@mapIndexedNotNull null
                    val epUrl = "$DOMAIN/videos/hentai/$epSlug"
                    val name = ep.name?.takeIf { it.isNotBlank() } ?: "Episode ${idx + 1}"
                    val epPoster =
                        ep.coverUrl?.takeIf { it.isNotBlank() }
                            ?: ep.posterUrl?.takeIf { it.isNotBlank() }
                    newEpisode(epUrl) {
                        this.name = name
                        this.episode = ep.episode?.toIntOrNull() ?: (idx + 1)
                        this.posterUrl = epPoster
                    }
                }
        } else {
            listOf(
                newEpisode("$DOMAIN/videos/hentai/$slug") {
                    this.name = title
                    this.episode = video.episode?.toIntOrNull() ?: 1
                    this.posterUrl = poster
                },
            )
        }

    val recommendations =
        franchiseVideos
            .filter { it.slug != slug }
            .mapNotNull { v ->
                val s = v.slug ?: return@mapNotNull null
                val name = v.name?.takeIf { it.isNotBlank() } ?: s
                newAnimeSearchResponse(
                    name,
                    "$DOMAIN/videos/hentai/$s",
                    TvType.NSFW,
                ) {
                    this.posterUrl =
                        v.coverUrl?.takeIf { it.isNotBlank() }
                            ?: v.posterUrl?.takeIf { it.isNotBlank() }
                }
            }
            .distinctBy { it.url }
            .take(20)

    return newAnimeLoadResponse(title, url, TvType.NSFW) {
        engName = title
        posterUrl = poster
        this.year = year
        plot = description
        this.tags = tags
        this.recommendations = recommendations
        addEpisodes(DubStatus.Subbed, episodes)
        showStatus = getStatus(video.brand)
    }
}

/**
 * Strip HTML tags from a description (HanimeTV often returns HTML in the
 * description field).
 */
private fun stripHtml(html: String): String =
    html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>\\s*<p>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

internal fun extractSlug(url: String): String? {
    val direct = Regex("""/videos/hentai/([\w\-]+)""").find(url)?.groupValues?.getOrNull(1)
    if (direct != null) return direct
    return url.trimEnd('/').substringAfterLast("/").takeIf { it.isNotBlank() }
}

private fun unixToYear(unix: Long?): Int? {
    if (unix == null || unix <= 0) return null
    return runCatching {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = unix * 1000
        cal.get(Calendar.YEAR)
    }.getOrNull()
}
