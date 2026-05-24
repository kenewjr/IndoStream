package com.oploverz

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Element

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

    override val mainPage =
            mainPageOf(
                    "update" to "Latest Update",
                    "latest" to "Latest Added",
                    "popular" to "Popular Anime",
                    "rating" to "Top Rated",
            )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document =
                app.get("$mainUrl/anime-list/page/$page/?title&order=${request.data}&status&type")
                        .document
        val home = document.select("div.relat > article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/")
            title =
                    when {
                        (title.contains("-episode")) && !(title.contains("-movie")) ->
                                Regex("(.+)-episode").find(title)?.groupValues?.get(1).toString()
                        (title.contains("-movie")) ->
                                Regex("(.+)-movie").find(title)?.groupValues?.get(1).toString()
                        else -> title
                    }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("div.title")?.text()?.trim() ?: return null
        val href = getProperAnimeLink(this.selectFirst("a")!!.attr("href"))
        val posterUrl = this.select("img[itemprop=image]").attr("src")
        val type = getType(this.select("div.type").text().trim())
        val epNum =
                this.selectFirst("span.episode")
                        ?.ownText()
                        ?.replace(Regex("\\D"), "")
                        ?.trim()
                        ?.toIntOrNull()
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val anime = mutableListOf<SearchResponse>()
        (1..2).forEach { page ->
            val link = "$mainUrl/page/$page/?s=$query"
            val document = app.get(link).document
            val media =
                    document.select(".site-main.relat > article").mapNotNull {
                        val title = it.selectFirst("div.title > h2")!!.ownText().trim()
                        val href = it.selectFirst("a")!!.attr("href")
                        val posterUrl = it.selectFirst("img")!!.attr("src")
                        val type = getType(it.select("div.type").text().trim())
                        newAnimeSearchResponse(title, href, type) { this.posterUrl = posterUrl }
                    }
            if (media.isNotEmpty()) anime.addAll(media)
        }
        return anime
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title =
                document.selectFirst("h1.entry-title")
                        ?.text()
                        ?.replace("Subtitle Indonesia", "")
                        ?.trim()
                        ?: ""
        val type = getType(document.selectFirst("div.alternati span.type")?.text() ?: "")
        val year =
                document.selectFirst("div.alternati a")
                        ?.text()
                        ?.filter { it.isDigit() }
                        ?.toIntOrNull()
        // [UPDATED SELECTOR]: relax dari `div.lstepsiode.listeps ul li`
        // ke fallback chain yang menangani perubahan markup. Episode parsing
        // sekarang juga lebih robust pakai regex Episode/Eps.
        val episodes =
                document.select(
                    "div.lstepsiode.listeps ul li, " +
                    "div.listeps ul li, " +
                    "div.eplister ul li, " +
                    "ul.lstepsiode li, " +
                    "div.episodelist ul li"
                )
                        .mapNotNull {
                            val header = it.selectFirst("a") ?: return@mapNotNull null
                            val text = header.text().trim()
                            val episode = Regex("Episode\\s*(\\d+)|Eps\\s*(\\d+)", RegexOption.IGNORE_CASE)
                                .find(text)
                                ?.let { m -> m.groupValues.drop(1).firstOrNull { it.isNotBlank() } }
                                ?.toIntOrNull()
                                ?: text.toIntOrNull()
                                ?: text.filter { c -> c.isDigit() }.toIntOrNull()
                            val link = fixUrl(header.attr("href"))
                            newEpisode(link){
                                this.name = text
                                this.episode = episode
                            }
                        }
                        .distinctBy { it.data }
                        .reversed()

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: document.selectFirst("div.thumb > img")?.attr("src")
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus =
                    getStatus(
                            document.selectFirst("div.alternati span:nth-child(2)")?.text()?.trim()
                    )
            plot = document.selectFirst("div.entry-content > p")?.text()?.trim()
            this.tags = document.select("div.genre-info a").map { it.text() }
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
        // Oploverz is a SvelteKit SPA. The episode page ships every episode's
        // data as inline JS object literals (no <iframe>, no Dooplay AJAX).
        // Pattern looks like:
        //   episodes:[{id:..., episodeNumber:"6", downloadUrl:[...],
        //     streamUrl:[{source:"Nonton Online 360p", url:"https://..."}],
        //     content:null, ...}]
        // We pluck the slice for the active episode and hand each URL to
        // loadExtractor. Blogger video.g, acefile.co, akirabox, filedon, and
        // 4meplayer embeds are all resolved by CloudStream's stock extractors.
        val pageHtml = app.get(data, referer = "$mainUrl/").text
        val epNum = Regex("""/episode/(\d+)""").find(data)?.groupValues?.getOrNull(1)

        // Locate the episode object's bounds. We anchor on episodeNumber:"<n>"
        // (with non-greedy lookups around it) so we don't accidentally pull
        // sources from a neighbouring episode's record.
        val epBlock: String = if (epNum != null) {
            val anchor = Regex("""episodeNumber:"$epNum"""").find(pageHtml)
            if (anchor != null) {
                val start = anchor.range.first
                // Stop at the next episodeNumber:"..." OR end of episodes:[ array.
                val tail = pageHtml.substring(start)
                val nextAnchor = Regex("""episodeNumber:"(?!$epNum")""").find(tail, 1)
                tail.substring(0, nextAnchor?.range?.first ?: tail.length.coerceAtMost(60_000))
            } else pageHtml
        } else pageHtml

        // Stream URLs (player embeds).
        val streamArrayRegex = Regex(
            """streamUrl:\[(.*?)](?=,content)""",
            RegexOption.DOT_MATCHES_ALL
        )
        val streamArray = streamArrayRegex.find(epBlock)?.groupValues?.getOrNull(1).orEmpty()
        val streamObjRegex = Regex("""\{source:"([^"]+)",url:"([^"]+)"}""")
        val streamSources = streamObjRegex.findAll(streamArray).map {
            it.groupValues[1] to it.groupValues[2].replace("\\u0026", "&").replace("\\/", "/")
        }.toList()

        // Download URLs (host-by-host, per quality, per format).
        val downloadHostRegex = Regex("""\{host:"([^"]+)",url:"([^"]+)"}""")
        val downloadSources = downloadHostRegex.findAll(epBlock).map {
            it.groupValues[1] to it.groupValues[2].replace("\\u0026", "&").replace("\\/", "/")
        }.toList()

        runAllAsync(
            {
                streamSources.amap { (label, url) ->
                    runCatching {
                        loadFixedExtractor(
                            url,
                            label,
                            "$mainUrl/",
                            subtitleCallback,
                            callback
                        )
                    }
                }
            },
            {
                downloadSources.amap { (host, url) ->
                    runCatching {
                        loadFixedExtractor(
                            url,
                            host,
                            "$mainUrl/",
                            subtitleCallback,
                            callback
                        )
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
