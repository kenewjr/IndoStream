package com.nekopoi

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newAnimeSearchResponse
import org.jsoup.nodes.Element

internal fun MainAPI.toSearchResult(element: Element): AnimeSearchResponse? {
    val link =
        if (element.tagName() == "a" && element.hasAttr("href")) {
            element
        } else {
            element.selectFirst("a.nk-search-item") ?: element.selectFirst("a[href]") ?: return null
        }
    val title =
        link.selectFirst(".nk-search-info h2")?.text()?.trim()
            ?: link.selectFirst("h2")?.text()?.trim()
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: extractTitleFromText(link.text())
            ?: return null
    val href = getProperAnimeLink(link.attr("href"))

    val imgSrc = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
    val styleAttr =
        element
            .selectFirst("div.nk-search-thumb, div.nk-related-thumb-crop, div.ltd")
            ?.attr("style")
            .orEmpty()
    val posterFromStyle = backgroundImageRegex.find(styleAttr)?.groupValues?.getOrNull(1)
    val posterUrl = fixUrlNull(imgSrc ?: posterFromStyle)

    val epNum =
        element
            .selectFirst("i.dot, .nk-episode-badge")
            ?.text()
            ?.filter { it.isDigit() }
            ?.toIntOrNull()
    return newAnimeSearchResponse(title, href, TvType.NSFW) {
        this.posterUrl = posterUrl
        addSub(epNum)
    }
}

internal fun MainAPI.toGridThumbResult(element: Element): AnimeSearchResponse? {
    val metaLink = element.selectFirst("div.nk-jav-meta a[href]")
    val thumbLink = element.selectFirst("div.nk-grid-thumb a[href]")
    val link = metaLink ?: thumbLink ?: element.selectFirst("a[href]") ?: return null

    val title =
        element.selectFirst("div.nk-jav-meta h2")?.text()?.trim()
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: link.text().trim().ifBlank { null }
            ?: return null
    val href = getProperAnimeLink(link.attr("href"))

    val imgSrc = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
    val thumbStyle =
        element
            .selectFirst("div.nk-grid-thumb")
            ?.attr("style")
            .orEmpty()
    val posterFromStyle = backgroundImageRegex.find(thumbStyle)?.groupValues?.getOrNull(1)
    val poster = fixUrlNull(imgSrc ?: posterFromStyle)

    return newAnimeSearchResponse(title, href, TvType.NSFW) {
        this.posterUrl = poster
    }
}

internal fun MainAPI.toPostCardResult(element: Element): AnimeSearchResponse? {
    val titleLink =
        element.selectFirst("div.nk-post-meta h2 a[href]")
            ?: element.selectFirst("h2 a[href]")
            ?: element.selectFirst("a[href]")
            ?: return null
    val title =
        titleLink.text().trim().ifBlank { null }
            ?: titleLink.attr("title").takeIf { it.isNotBlank() }
            ?: return null
    val href = getProperAnimeLink(titleLink.attr("href"))

    val thumbStyle =
        element
            .selectFirst("div.nk-thumb-crop, div.nk-post-thumb [style*=background-image]")
            ?.attr("style")
            .orEmpty()
    val posterFromStyle = backgroundImageRegex.find(thumbStyle)?.groupValues?.getOrNull(1)
    val imgSrc = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
    val poster = fixUrlNull(posterFromStyle ?: imgSrc)

    return newAnimeSearchResponse(title, href, TvType.NSFW) {
        this.posterUrl = poster
    }
}

internal fun MainAPI.toBroadResult(element: Element): AnimeSearchResponse? {
    val h2El = element.selectFirst("h2") ?: return null
    val h2Link: Element? = h2El.selectFirst("a[href]") ?: h2El.parent()?.takeIf { it.tagName() == "a" && it.hasAttr("href") }
    val link = h2Link ?: element.selectFirst("a[href]") ?: return null

    val title =
        h2El.text().trim().ifBlank { null }
            ?: link.attr("title").takeIf { it.isNotBlank() }
            ?: return null
    val href = getProperAnimeLink(link.attr("href"))

    val imgSrc = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
    val styleAttr = element.selectFirst("[style*=background-image]")?.attr("style").orEmpty()
    val posterFromStyle = backgroundImageRegex.find(styleAttr)?.groupValues?.getOrNull(1)
    val poster = fixUrlNull(imgSrc ?: posterFromStyle)

    return newAnimeSearchResponse(title, href, TvType.NSFW) {
        this.posterUrl = poster
    }
}
