package com.hanime1

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newAnimeSearchResponse
import org.jsoup.nodes.Element

/**
 * Hanime1 video card markup follows this shape on every search/listing page:
 *
 * <div class="search-doujin-videos hidden-xs">
 *   <a class="overlay" href="https://hanime1.me/watch?v=XXXXX"></a>
 *   <div class="home-rows-videos-div">
 *     <img class="home-rows-videos-img" src="...thumb.jpg">
 *   </div>
 *   <div class="card-mobile-title">標題</div>
 *   <a class="home-rows-videos-title">標題</a>
 *   <div class="home-rows-videos-info">
 *     <span class="card-mobile-duration">10:30</span>
 *   </div>
 * </div>
 *
 * Mobile responsive variant is `div.search-doujin-videos.visible-xs`. We accept both.
 */
internal fun MainAPI.toSearchResult(element: Element): AnimeSearchResponse? {
    val link = element.selectFirst("a.overlay[href]")
        ?: element.selectFirst("a.home-rows-videos-title[href]")
        ?: element.selectFirst("a[href*='/watch?v=']")
        ?: return null
    val href = fixUrlOrNull(link.attr("href")) ?: return null

    val title =
        element.selectFirst("div.card-mobile-title")?.text()?.trim()
            ?: element.selectFirst("a.home-rows-videos-title")?.text()?.trim()
            ?: element.selectFirst("div.home-rows-videos-title")?.text()?.trim()
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: element.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
    val cleanedTitle = cleanTitle(title) ?: title

    val poster =
        element.selectFirst("img.home-rows-videos-img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: element.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
    val posterUrl = fixUrlOrNull(poster)

    return newAnimeSearchResponse(cleanedTitle, href, TvType.NSFW) {
        this.posterUrl = posterUrl
    }
}

/**
 * The homepage uses banner-style cards in some sections (e.g. "本週排行").
 * Layout: <div class="home-rows-titles-padding"><a href><img></a></div>
 */
internal fun MainAPI.toBannerResult(element: Element): AnimeSearchResponse? {
    val link = element.selectFirst("a[href*='/watch?v=']") ?: return null
    val href = fixUrlOrNull(link.attr("href")) ?: return null
    val img = element.selectFirst("img")
    val title =
        link.attr("title").takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: element.selectFirst(".home-rows-titles, .video-title")?.text()?.trim()
            ?: return null
    val poster =
        img?.attr("src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
    return newAnimeSearchResponse(cleanTitle(title) ?: title, href, TvType.NSFW) {
        this.posterUrl = fixUrlOrNull(poster)
    }
}

/**
 * Generic fallback parser for unknown markup variations: any anchor that points to
 * a /watch?v= URL paired with an image.
 */
internal fun MainAPI.toGenericResult(element: Element): AnimeSearchResponse? {
    val link = element.selectFirst("a[href*='/watch?v=']") ?: return null
    val href = fixUrlOrNull(link.attr("href")) ?: return null
    val title =
        element.selectFirst("h2, h3, .title, .home-rows-videos-title, .card-mobile-title")
            ?.text()
            ?.trim()
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: element.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
    val poster =
        element.selectFirst("img")?.let {
            it.attr("data-src").takeIf { s -> s.isNotBlank() }
                ?: it.attr("src").takeIf { s -> s.isNotBlank() }
        }
    return newAnimeSearchResponse(cleanTitle(title) ?: title, href, TvType.NSFW) {
        this.posterUrl = fixUrlOrNull(poster)
    }
}
