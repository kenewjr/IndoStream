package com.oploverz

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class Oploverz : MainAPI() {
    override var mainUrl = "https://vip.oploverz.ltd"
    override var name = "Oploverz"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Finished Airing" -> ShowStatus.Completed
                "Completed" -> ShowStatus.Completed
                "Currently Airing" -> ShowStatus.Ongoing
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private val apiUrl = "https://backapi.oploverz.ac/api"

    override val mainPage =
            mainPageOf(
                    "?orderBy=updated_at&direction=desc" to "Latest Update",
                    "?orderBy=created_at&direction=desc" to "Latest Added",
                    "?hot=true&orderBy=updated_at&direction=desc" to "Popular Anime",
                    "?orderBy=score&direction=desc" to "Top Rated",
                    "?status=Ongoing&orderBy=updated_at&direction=desc" to "Ongoing",
                    "?status=Completed&orderBy=updated_at&direction=desc" to "Completed",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Site is a SvelteKit SPA; data is served from backapi.oploverz.ac/api.
        val url = "$apiUrl/series${request.data}&page=$page&perPage=20"
        val response = app.get(url).parsedSafe<ApiSeriesList>()
            ?: throw ErrorLoadingException("Empty Oploverz API response")
        val home = response.data.orEmpty().mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun ApiSeries.toSearchResult(): AnimeSearchResponse? {
        val seriesSlug = slug ?: return null
        val seriesTitle = title ?: return null
        val href = "$mainUrl/series/$seriesSlug"
        val seriesType = getType(releaseType ?: "")
        val epNum = totalEpisodes
        return newAnimeSearchResponse(seriesTitle, href, seriesType) {
            this.posterUrl = poster
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<AnimeSearchResponse>()
        // Iterate up to 5 pages or until the API runs out of results.
        for (page in 1..5) {
            val url = "$apiUrl/series?title=$query&page=$page&perPage=20"
            val response = app.get(url).parsedSafe<ApiSeriesList>() ?: break
            val items = response.data.orEmpty().mapNotNull { it.toSearchResult() }
            if (items.isEmpty()) break
            results.addAll(items)
            val meta = response.meta
            if (meta != null && meta.currentPage != null && meta.lastPage != null
                && meta.currentPage >= meta.lastPage) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        // Slug is the last path segment of /series/<slug>.
        val seriesSlug = url.substringAfterLast("/series/").substringBefore("/")
            .ifBlank { throw ErrorLoadingException("Bad Oploverz URL: $url") }

        val detail = app.get("$apiUrl/series/$seriesSlug").parsedSafe<ApiSeriesDetail>()
            ?.data ?: throw ErrorLoadingException("Series not found: $seriesSlug")

        val seriesTitle = detail.title ?: seriesSlug
        val seriesType = getType(detail.releaseType ?: "")
        val seriesYear = detail.releaseDate
            ?.let { Regex("(\\d{4})").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }

        // Episode list is paginated. We grab perPage=100 and walk pages until done.
        val episodeList = mutableListOf<ApiEpisode>()
        for (page in 1..50) {
            val ep = app.get("$apiUrl/series/$seriesSlug/episodes?page=$page&perPage=100")
                .parsedSafe<ApiEpisodeList>() ?: break
            val items = ep.data.orEmpty()
            if (items.isEmpty()) break
            episodeList.addAll(items)
            val meta = ep.meta
            if (meta?.currentPage != null && meta.lastPage != null
                && meta.currentPage >= meta.lastPage) break
        }
        val episodes = episodeList
            .mapNotNull { e ->
                val epNum = e.episodeNumber?.toIntOrNull() ?: return@mapNotNull null
                // Pass slug + episode number to loadLinks via the data field; loadLinks
                // re-fetches the episode payload from the API so we don't have to
                // serialise the whole download tree here.
                newEpisode("$seriesSlug|$epNum") {
                    this.name = e.title ?: "Episode $epNum"
                    this.episode = epNum
                }
            }
            .sortedBy { it.episode ?: 0 }

        val tracker = APIHolder.getTracker(listOf(seriesTitle), TrackerType.getTypes(seriesType), seriesYear, true)

        return newAnimeLoadResponse(seriesTitle, url, seriesType) {
            posterUrl = tracker?.image ?: detail.poster
            backgroundPosterUrl = tracker?.cover
            this.year = seriesYear
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(detail.status)
            plot = detail.description
            this.tags = detail.genres.orEmpty().mapNotNull { it.name }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
            data: String,
            isCasting: Boolean,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ): Boolean {
        // load() encodes data as "<slug>|<epNum>" so we re-fetch the episode
        // payload from the API. Episode payload includes streamUrl (player
        // iframes) and downloadUrl (host mirrors per quality per format).
        val parts = data.split("|", limit = 2)
        if (parts.size != 2) return false
        val (seriesSlug, epNumStr) = parts
        val epNum = epNumStr.toIntOrNull() ?: return false

        // Walk pages to locate the episode (cheaper than fetching all and filtering).
        var match: ApiEpisode? = null
        for (page in 1..50) {
            val resp = app.get("$apiUrl/series/$seriesSlug/episodes?page=$page&perPage=100")
                .parsedSafe<ApiEpisodeList>() ?: break
            val items = resp.data.orEmpty()
            if (items.isEmpty()) break
            match = items.firstOrNull { it.episodeNumber?.toIntOrNull() == epNum }
            if (match != null) break
            val meta = resp.meta
            if (meta?.currentPage != null && meta.lastPage != null
                && meta.currentPage >= meta.lastPage) break
        }
        val episode = match ?: return false

        val streamSources = episode.streamUrl.orEmpty().mapNotNull { s ->
            val u = s.url ?: return@mapNotNull null
            (s.source ?: "Stream") to u
        }
        // Flatten downloadUrl tree: format -> resolutions[] -> download_links[].
        val downloadSources = episode.downloadUrl.orEmpty().flatMap { fmt ->
            fmt.resolutions.orEmpty().flatMap { res ->
                res.downloadLinks.orEmpty().mapNotNull { dl ->
                    val u = dl.url ?: return@mapNotNull null
                    val label = "${dl.host ?: "?"} ${res.quality ?: ""}".trim()
                    label to u
                }
            }
        }

        runAllAsync(
            {
                streamSources.amap { (label, url) ->
                    runCatching {
                        loadFixedExtractor(url, label, "$mainUrl/", subtitleCallback, callback)
                    }
                }
            },
            {
                downloadSources.amap { (label, url) ->
                    runCatching {
                        loadFixedExtractor(url, label, "$mainUrl/", subtitleCallback, callback)
                    }
                }
            }
        )

        return streamSources.isNotEmpty() || downloadSources.isNotEmpty()
    }

    private suspend fun loadFixedExtractor(
            url: String,
            name: String,
            referer: String? = null,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        loadExtractor(url, referer, subtitleCallback) { link ->
			launch(Dispatchers.IO) {
				callback.invoke(
					newExtractorLink(
						link.name,
						link.name,
						link.url,						
						link.type
					){
						this.referer = link.referer
						this.quality = name.fixQuality()
						this.headers = link.headers
						this.extractorData = link.extractorData
					}
				)
			}
		}
    }        

    private fun String.fixQuality(): Int {
        return when (this) {
            "MP4HD" -> Qualities.P720.value
            "FULLHD" -> Qualities.P1080.value
            else -> Regex("(\\d{3,4})p").find(this)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Qualities.Unknown.value
        }
    }
}

// ----- backapi.oploverz.ac data classes -----

data class ApiMeta(
    @JsonProperty("currentPage") val currentPage: Int? = null,
    @JsonProperty("lastPage") val lastPage: Int? = null,
    @JsonProperty("perPage") val perPage: Int? = null,
    @JsonProperty("total") val total: Int? = null,
)

data class ApiGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("slug") val slug: String? = null,
)

data class ApiSeries(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("releaseType") val releaseType: String? = null,
    @JsonProperty("score") val score: Double? = null,
    @JsonProperty("genres") val genres: List<ApiGenre>? = null,
    @JsonProperty("totalEpisodes") val totalEpisodes: Int? = null,
)

data class ApiSeriesList(
    @JsonProperty("meta") val meta: ApiMeta? = null,
    @JsonProperty("data") val data: List<ApiSeries>? = null,
)

data class ApiSeriesDetail(
    @JsonProperty("data") val data: ApiSeries? = null,
)

data class ApiStream(
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class ApiDownloadLink(
    @JsonProperty("host") val host: String? = null,
    @JsonProperty("url") val url: String? = null,
)

data class ApiResolution(
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("download_links") val downloadLinks: List<ApiDownloadLink>? = null,
)

data class ApiDownloadFormat(
    @JsonProperty("format") val format: String? = null,
    @JsonProperty("resolutions") val resolutions: List<ApiResolution>? = null,
)

data class ApiEpisode(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("subbed") val subbed: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("episodeNumber") val episodeNumber: String? = null,
    @JsonProperty("downloadUrl") val downloadUrl: List<ApiDownloadFormat>? = null,
    @JsonProperty("streamUrl") val streamUrl: List<ApiStream>? = null,
)

data class ApiEpisodeList(
    @JsonProperty("meta") val meta: ApiMeta? = null,
    @JsonProperty("data") val data: List<ApiEpisode>? = null,
)
