// NekopoiUtils.kt - Shared URL, text, and embed helper functions.
package com.nekopoi

import java.net.URI

internal fun fixUrl(
    url: String,
    domain: String,
): String {
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

internal fun getBaseUrl(url: String): String =
    URI(url).let {
        "${it.scheme}://${it.host}"
    }

internal fun extractTitleFromText(text: String?): String? {
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

internal fun getProperAnimeLink(uri: String): String =
    if (uri.contains("-episode-")) {
        val title =
            uri
                .substringAfter("$DOMAIN/")
                .substringBefore("-episode-")
                .removePrefix("new-release-")
                .removePrefix("uncensored-")
        "$DOMAIN/hentai/$title"
    } else {
        uri
    }

internal fun parseSearchPrefix(query: String): Pair<String?, String> {
    val trimmed = query.trim()
    val colon = trimmed.indexOf(':').takeIf { it in 1..15 } ?: return null to trimmed
    val prefix = trimmed.substring(0, colon).lowercase()
    val rest = trimmed.substring(colon + 1).trim()
    val slug = rest.lowercase().replace(' ', '-').ifBlank { null }
    return when (prefix) {
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

internal fun fixEmbed(url: String?): String? {
    if (url == null) return null
    val host = getBaseUrl(url)
    return when {
        url.contains("streamsb", true) -> url.replace("$host/", "$host/e/")
        else -> url
    }
}

internal fun mirrorIsBlackList(host: String?): Boolean = mirrorBlackList.any { it.equals(host, true) }
