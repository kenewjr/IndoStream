package com.hanime1

import java.net.URI
import java.net.URLEncoder

internal fun fixUrl(
    url: String,
    domain: String = DOMAIN,
): String {
    if (url.isEmpty()) return ""
    if (url.startsWith("http")) return url
    if (url.startsWith("//")) return "https:$url"
    if (url.startsWith("/")) return domain + url
    return "$domain/$url"
}

internal fun fixUrlOrNull(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return runCatching { fixUrl(url) }.getOrNull()
}

internal fun getBaseUrl(url: String): String =
    runCatching {
        URI(url).let { "${it.scheme}://${it.host}" }
    }.getOrDefault(DOMAIN)

internal fun encodeQuery(value: String): String =
    URLEncoder.encode(value, "UTF-8")

/**
 * Cleans up a hanime1 title text by stripping promotional prefixes and trailing
 * site/section names so it displays nicely in CloudStream/Kototoro lists.
 */
internal fun cleanTitle(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return raw
        .trim()
        .removeSuffix(" - Hanime1.me")
        .removeSuffix(" - hanime1.me")
        .removeSuffix(" | hanime1.me")
        .removeSuffix(" | Hanime1.me")
        .removeSuffix(" - hanime1")
        .trim()
        .ifBlank { null }
}

/**
 * Hanime1 search supports prefix-based filtering for power-users:
 *   genre:裏番      -> filter by genre query string
 *   tag:巨乳        -> filter by tag query string
 *   sort:本週排行   -> filter by sort query string
 *   year:2024       -> filter by year query string
 *   uncensored:foo  -> tag shortcut for 無碼
 */
internal data class SearchPrefix(
    val key: String? = null,
    val value: String? = null,
    val keyword: String,
)

internal fun parseSearchPrefix(query: String): SearchPrefix {
    val trimmed = query.trim()
    val colon = trimmed.indexOf(':').takeIf { it in 1..15 } ?: return SearchPrefix(keyword = trimmed)
    val prefix = trimmed.substring(0, colon).lowercase()
    val rest = trimmed.substring(colon + 1).trim()
    return when (prefix) {
        "genre", "genres", "category" -> SearchPrefix(key = "genre", value = rest, keyword = "")
        "tag", "tags" -> SearchPrefix(key = "tags%5B%5D", value = rest, keyword = "")
        "sort" -> SearchPrefix(key = "sort", value = rest, keyword = "")
        "year" -> SearchPrefix(key = "year", value = rest, keyword = "")
        "month" -> SearchPrefix(key = "month", value = rest, keyword = "")
        "uncensored", "无码", "無碼" -> SearchPrefix(key = "tags%5B%5D", value = "無碼", keyword = rest)
        else -> SearchPrefix(keyword = trimmed)
    }
}

/**
 * Some embedded video paths inside hanime1's player JS use protocol-relative URLs;
 * normalize them so CloudStream/Kototoro can fetch directly.
 */
internal fun normalizeStreamUrl(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val unescaped = raw.replace("\\/", "/")
    return when {
        unescaped.startsWith("//") -> "https:$unescaped"
        unescaped.startsWith("http") -> unescaped
        unescaped.startsWith("/") -> "$DOMAIN$unescaped"
        else -> null
    }
}

internal fun extractVideoIdFromUrl(url: String): String? {
    val watchSegment =
        Regex("""/watch\?v=([\w\-]+)""").find(url)?.groupValues?.getOrNull(1)
    if (watchSegment != null) return watchSegment
    return Regex("""/(?:videos|video)/([\w\-]+)""").find(url)?.groupValues?.getOrNull(1)
}

/**
 * Extract leading episode number out of titles like "勇者佔有慾 EP3" or "Episode 03".
 */
internal fun parseEpisodeNumber(title: String?): Int? {
    if (title.isNullOrBlank()) return null
    return Regex("""(?i)(?:ep|episode|第)\s*(\d{1,3})""")
        .find(title)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}
