package com.hanimetv

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeSearchResponse

/**
 * Convert a HentaiVideo DTO from the API to a CloudStream search response.
 *
 * The video URL we use is `https://hanime.tv/videos/hentai/<slug>` which is
 * what `load()` will receive. The slug is the canonical id-like string in
 * every API response.
 */
internal fun MainAPI.hentaiVideoToSearchResponse(video: HentaiVideo): AnimeSearchResponse? {
    val slug = video.slug?.takeIf { it.isNotBlank() } ?: return null
    val title = video.name?.takeIf { it.isNotBlank() } ?: slug
    val href = "$DOMAIN/videos/hentai/$slug"
    val poster = video.coverUrl?.takeIf { it.isNotBlank() }
        ?: video.posterUrl?.takeIf { it.isNotBlank() }

    return newAnimeSearchResponse(title, href, TvType.NSFW) {
        this.posterUrl = poster
    }
}
