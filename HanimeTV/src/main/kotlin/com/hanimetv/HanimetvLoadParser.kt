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
import com.lagradost.nicehttp.NiceResponse
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
    Log.d("HanimeTV", "parseLoadPage: GET $apiUrl")

    val response: NiceResponse? =
        try {
            app.get(apiUrl, headers = apiHeaders, timeout = 30L)
        } catch (t: Throwable) {
            Log.e("HanimeTV", "parseLoadPage: API request threw", t)
            null
        }

    val payload: HanimeVideoResponse? =
        if (response != null && response.isSuccessful) {
            Log.d("HanimeTV", "parseLoadPage: API ${response.code} OK, body=${response.text.length} chars")
            // Dump a sample of the body so we can verify the actual JSON shape.
            Log.d("HanimeTV", "parseLoadPage: body sample=${response.text.take(1500)}")
            val direct =
                try {
                    response.parsedSafe<HanimeVideoResponse>()
                } catch (t: Throwable) {
                    Log.e("HanimeTV", "parseLoadPage: parsedSafe threw", t)
                    null
                }
            direct ?: parseFromRawJson(response.text).also {
                if (it != null) {
                    Log.d("HanimeTV", "parseLoadPage: parsed via JSONObject fallback")
                } else {
                    Log.w("HanimeTV", "parseLoadPage: JSONObject fallback also failed")
                }
            }
        } else {
            Log.w(
                "HanimeTV",
                "parseLoadPage: API failed code=${response?.code} - falling back to HTML scrape",
            )
            null
        }

    val effectivePayload = payload ?: parseLoadFromHtml(url, slug)
        ?: throw ErrorLoadingException("HanimeTV: both API and HTML scrape failed for $slug")

    val video = effectivePayload.hentaiVideo
        ?: throw ErrorLoadingException("HanimeTV: missing hentai_video for $slug")
    Log.d("HanimeTV", "parseLoadPage: video name='${video.name}'")

    val title = video.name?.takeIf { it.isNotBlank() } ?: slug
    val poster = video.coverUrl?.takeIf { it.isNotBlank() }
        ?: video.posterUrl?.takeIf { it.isNotBlank() }
    val description = video.description?.let { stripHtml(it) }
    val tags =
        (video.tags ?: effectivePayload.hentaiTags)
            ?.mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotEmpty() } }
            ?.distinct()
            .orEmpty()
    val year = unixToYear(video.releasedAtUnix ?: video.createdAtUnix)

    val franchiseVideos = effectivePayload.hentaiFranchiseHentaiVideos.orEmpty()

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
                    this.posterHeaders = imageHeaders
                }
            }
            .distinctBy { it.url }
            .take(20)

    return newAnimeLoadResponse(title, url, TvType.NSFW) {
        engName = title
        posterUrl = poster
        this.posterHeaders = imageHeaders
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

/**
 * HTML fallback for when the v8 API blocks us (401/403/Cloudflare). The
 * hanime.tv HTML page embeds a `window.__NUXT__ = (function(...))` blob
 * that contains a `state` object with the same shape as the API response
 * (hentai_video, videos_manifest, hentai_franchise_hentai_videos).
 *
 * We extract just the JSON-y fragments we need with regex; this is more
 * resilient than trying to fully evaluate the IIFE.
 */
internal suspend fun parseLoadFromHtml(url: String, slug: String): HanimeVideoResponse? {
    return try {
        Log.d("HanimeTV", "parseLoadFromHtml: GET $url")
        val res = app.get(url, headers = baseHeaders, timeout = 30L)
        if (!res.isSuccessful) {
            Log.e("HanimeTV", "parseLoadFromHtml: HTTP ${res.code}")
            return null
        }
        val html = res.text
        Log.d("HanimeTV", "parseLoadFromHtml: ${html.length} chars")

        val titleMatch =
            Regex("""<meta\s+property="og:title"\s+content="([^"]+)"""")
                .find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.getOrNull(1)
        val descMatch =
            Regex("""<meta\s+property="og:description"\s+content="([^"]+)"""")
                .find(html)?.groupValues?.getOrNull(1)
        val posterMatch =
            Regex("""<meta\s+property="og:image"\s+content="([^"]+)"""")
                .find(html)?.groupValues?.getOrNull(1)

        val name = titleMatch
            ?.removeSuffix(" | hanime.tv")
            ?.removeSuffix(" - hanime.tv")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (name.isNullOrBlank()) {
            Log.w("HanimeTV", "parseLoadFromHtml: no title found, abort")
            return null
        }

        Log.d("HanimeTV", "parseLoadFromHtml: scraped title='$name'")
        HanimeVideoResponse(
            hentaiVideo =
                HentaiVideo(
                    slug = slug,
                    name = name,
                    description = descMatch,
                    posterUrl = posterMatch,
                    coverUrl = posterMatch,
                ),
        )
    } catch (t: Throwable) {
        Log.e("HanimeTV", "parseLoadFromHtml: threw", t)
        null
    }
}

/**
 * Permissive JSON fallback. The v8 endpoint sometimes returns a wrapped
 * object (`{"data": {...}}`) or uses different casing/keys. This walks the
 * response with org.json so we can pull what we need without strict DTO
 * shape matching.
 */
internal fun parseFromRawJson(body: String): HanimeVideoResponse? {
    return try {
        val root = org.json.JSONObject(body)
        val container =
            when {
                root.has("hentai_video") -> root
                root.has("data") -> root.getJSONObject("data")
                else -> root
            }
        val v =
            container.optJSONObject("hentai_video")
                ?: container.optJSONObject("video")
                ?: return null

        val tagsArr = container.optJSONArray("hentai_tags")
        val tags =
            (0 until (tagsArr?.length() ?: 0)).mapNotNull { i ->
                tagsArr?.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
                    ?.let { HentaiTag(text = it) }
            }

        val franchiseArr =
            container.optJSONArray("hentai_franchise_hentai_videos")
                ?: container.optJSONArray("franchise_videos")
        val franchise =
            (0 until (franchiseArr?.length() ?: 0)).mapNotNull { i ->
                val obj = franchiseArr?.optJSONObject(i) ?: return@mapNotNull null
                HentaiVideo(
                    slug = obj.optString("slug").takeIf { it.isNotBlank() },
                    name = obj.optString("name").takeIf { it.isNotBlank() },
                    coverUrl = obj.optString("cover_url").takeIf { it.isNotBlank() },
                    posterUrl = obj.optString("poster_url").takeIf { it.isNotBlank() },
                )
            }

        val manifest =
            container.optJSONObject("videos_manifest")?.let { m ->
                val serversArr = m.optJSONArray("servers")
                val servers =
                    (0 until (serversArr?.length() ?: 0)).mapNotNull { si ->
                        val s = serversArr?.optJSONObject(si) ?: return@mapNotNull null
                        val streamsArr = s.optJSONArray("streams")
                        val streams =
                            (0 until (streamsArr?.length() ?: 0)).mapNotNull { ti ->
                                val st = streamsArr?.optJSONObject(ti) ?: return@mapNotNull null
                                HanimeStream(
                                    url = st.optString("url").takeIf { it.isNotBlank() },
                                    height = st.optInt("height", 0).takeIf { it > 0 },
                                    width = st.optInt("width", 0).takeIf { it > 0 },
                                    kind = st.optString("kind").takeIf { it.isNotBlank() },
                                    isGuestAllowed = st.optBoolean("is_guest_allowed", true),
                                )
                            }
                        HanimeServer(
                            name = s.optString("name").takeIf { it.isNotBlank() },
                            streams = streams,
                        )
                    }
                VideosManifest(servers = servers)
            }

        HanimeVideoResponse(
            hentaiVideo =
                HentaiVideo(
                    slug = v.optString("slug").takeIf { it.isNotBlank() },
                    name = v.optString("name").takeIf { it.isNotBlank() },
                    description = v.optString("description").takeIf { it.isNotBlank() },
                    posterUrl = v.optString("poster_url").takeIf { it.isNotBlank() },
                    coverUrl = v.optString("cover_url").takeIf { it.isNotBlank() },
                    brand = v.optString("brand").takeIf { it.isNotBlank() },
                    releasedAtUnix = v.optLong("released_at_unix", 0L).takeIf { it > 0 },
                    createdAtUnix = v.optLong("created_at_unix", 0L).takeIf { it > 0 },
                    episode = v.optString("episode").takeIf { it.isNotBlank() },
                ),
            hentaiTags = tags,
            hentaiFranchiseHentaiVideos = franchise,
            videosManifest = manifest,
        )
    } catch (t: Throwable) {
        Log.e("HanimeTV", "parseFromRawJson: threw", t)
        null
    }
}
