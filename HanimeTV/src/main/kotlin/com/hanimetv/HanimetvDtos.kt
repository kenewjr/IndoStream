package com.hanimetv

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Top-level response from /api/v8/video?id=<slug>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HanimeVideoResponse(
    @JsonProperty("hentai_video") val hentaiVideo: HentaiVideo? = null,
    @JsonProperty("hentai_tags") val hentaiTags: List<HentaiTag>? = null,
    @JsonProperty("hentai_franchise") val hentaiFranchise: HentaiFranchise? = null,
    @JsonProperty("hentai_franchise_hentai_videos")
    val hentaiFranchiseHentaiVideos: List<HentaiVideo>? = null,
    @JsonProperty("videos_manifest") val videosManifest: VideosManifest? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HentaiVideo(
    val id: Long? = null,
    val slug: String? = null,
    val name: String? = null,
    val description: String? = null,
    val views: Long? = null,
    val likes: Long? = null,
    val dislikes: Long? = null,
    val downloads: Long? = null,
    @JsonProperty("released_at_unix") val releasedAtUnix: Long? = null,
    @JsonProperty("created_at_unix") val createdAtUnix: Long? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("cover_url") val coverUrl: String? = null,
    @JsonProperty("brand") val brand: String? = null,
    @JsonProperty("brand_id") val brandId: String? = null,
    @JsonProperty("hentai_tags") val tags: List<HentaiTag>? = null,
    @JsonProperty("monthly_rank") val monthlyRank: Int? = null,
    @JsonProperty("is_censored") val isCensored: Boolean? = null,
    @JsonProperty("rating") val rating: String? = null,
    @JsonProperty("episode") val episode: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HentaiTag(
    val text: String? = null,
    @JsonProperty("tag_type") val tagType: String? = null,
    val description: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HentaiFranchise(
    val id: Long? = null,
    val name: String? = null,
    val slug: String? = null,
    val title: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VideosManifest(
    val servers: List<HanimeServer>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HanimeServer(
    val id: Long? = null,
    val name: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    val streams: List<HanimeStream>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HanimeStream(
    val id: Long? = null,
    @JsonProperty("server_id") val serverId: Long? = null,
    val slug: String? = null,
    val kind: String? = null,
    val extension: String? = null,
    @JsonProperty("mime_type") val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val size: String? = null,
    val url: String? = null,
    @JsonProperty("is_guest_allowed") val isGuestAllowed: Boolean? = null,
    val duration: Long? = null,
)

/**
 * Response from /api/v8/browse-trending?time=week&page=N
 * and /api/v8/browse?...
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HanimeBrowseResponse(
    @JsonProperty("hentai_videos") val hentaiVideos: List<HentaiVideo>? = null,
    @JsonProperty("number_of_pages") val numberOfPages: Int? = null,
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("number_of_results") val numberOfResults: Int? = null,
)

/**
 * Response from search.htv-services.com (POST).
 * Body comes back as JSON with a `hits` field containing a JSON-encoded
 * list of HentaiVideo strings (yes, it's serialized again — we re-parse it).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class HanimeSearchResponse(
    val page: Int? = null,
    @JsonProperty("nbPages") val nbPages: Int? = null,
    @JsonProperty("nbHits") val nbHits: Int? = null,
    val hits: String? = null,
)
