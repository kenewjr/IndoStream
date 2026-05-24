package com.animeindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicInteger

class AnimeIndo : MainAPI() {
    // [FIXED]: gomunime.top sekarang pakai layout Tailwind/Laravel modern,
    // bukan WordPress dengan class .bs/.tt/.thumb seperti dulu.
    override var mainUrl = "https://gomunime.top"
    override var name = "AnimeIndo"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        fun getType(t: String?): TvType {
            if (t == null) return TvType.Anime
            return when {
                t.contains("OVA", true) || t.contains("Special", true) -> TvType.OVA
                t.contains("Movie", true) -> TvType.AnimeMovie
                t.contains("ONA", true) -> TvType.OVA
                else -> TvType.Anime
            }
        }

        fun getStatus(t: String?): ShowStatus {
            if (t == null) return ShowStatus.Completed
            return when {
                t.contains("Ongoing", true) || t.contains("Currently", true) ->
                    ShowStatus.Ongoing
                t.contains("Completed", true) || t.contains("Finished", true) ->
                    ShowStatus.Completed
                else -> ShowStatus.Completed
            }
        }
    }

    // [FIXED]: URL endpoint disesuaikan dengan struktur baru:
    //   /            -> homepage (sections: Episode Terbaru, Trending, Top, Movies, Ongoing, Rating)
    //   /status/ongoing, /status/completed
    //   /type/movie
    //   /koleksi/anime-skor-mal-tertinggi
    //   /genre/<slug>  (singular, bukan /genres/)
    override val mainPage = mainPageOf(
        "$mainUrl/status/ongoing" to "Anime Ongoing",
        "$mainUrl/status/completed" to "Anime Tamat",
        "$mainUrl/type/movie" to "Movies",
        "$mainUrl/koleksi/anime-skor-mal-tertinggi" to "Rating Tertinggi",
        "$mainUrl/genre/action" to "Genre: Action",
        "$mainUrl/genre/adventure" to "Genre: Adventure",
        "$mainUrl/genre/fantasy" to "Genre: Fantasy",
        "$mainUrl/genre/comedy" to "Genre: Comedy",
        "$mainUrl/genre/romance" to "Genre: Romance",
        "$mainUrl/genre/isekai" to "Genre: Isekai",
        "$mainUrl/genre/shounen" to "Genre: Shounen",
        "$mainUrl/genre/school" to "Genre: School",
        "$mainUrl/genre/drama" to "Genre: Drama",
        "$mainUrl/genre/supernatural" to "Genre: Supernatural",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // [FIXED]: pagination dilakukan via query string ?page=N untuk
        // listing pages (status/type/koleksi/genre menggunakan paginator standar).
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = runCatching { app.get(url).document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList())

        // [FIXED]: kartu anime sekarang <a class="card-netflix ...">
        // dengan h3 title, img poster, dan p.text-[11px] berisi type/year.
        val home = document.select("a.card-netflix").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        // [FIXED]: title dari h3, link dari href, poster dari img.
        val link = this.attr("href").takeIf { it.isNotBlank() } ?: return null
        val href = if (link.startsWith("http")) link else "$mainUrl${link.removePrefix("/").let { "/$it" }}"
        val title = this.selectFirst("h3")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("src").takeIf { it.isNotBlank() }
                ?: img.attr("data-src").takeIf { it.isNotBlank() }
        }
        // Episode badge sometimes shown as "EP N" or "EPISODE N" inside the card.
        val epNum = this.selectFirst("[class*=badge]")?.text()
            ?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }
        // Rating shown as "★ 8.5" — ignore for search response.
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // [FIXED]: search endpoint baru = /search?q=...
        val document = runCatching {
            app.get("$mainUrl/search?q=${query.replace(" ", "+")}").document
        }.getOrNull() ?: return emptyList()
        return document.select("a.card-netflix").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // [FIXED]: title sekarang di <h1> generik dengan class display.
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.removeSuffix("Subtitle Indonesia")?.trim()
            ?: return null

        // [FIXED]: poster dari og:image meta atau <img> di hero section.
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("img[alt][src*=poster]")?.attr("src")
            ?: document.selectFirst("article img")?.attr("src")

        // [FIXED]: metadata sekarang dalam struktur <dl><div><dt>Label</dt><dd>Value</dd>...
        // Helper untuk lookup value berdasarkan label text.
        fun getMetaValue(label: String): String? {
            return document.select("dl div").firstOrNull { div ->
                div.selectFirst("dt")?.text()?.trim()?.equals(label, ignoreCase = true) == true
            }?.selectFirst("dd")?.text()?.trim()?.takeIf { it.isNotBlank() }
        }

        // [FIXED]: genre dari <a href*=/genre/> di dalam article header.
        val tags = document.select("a[href*=/genre/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && it.length < 30 }
            .distinct()
            .takeIf { it.isNotEmpty() }

        // [FIXED]: type dari TvType badge (TV/Movie/ONA/OVA) — terlihat di
        // span sebelum h1, atau dari kartu detail.
        val typeText = document.select("span").map { it.text().trim() }
            .firstOrNull { it.uppercase() in listOf("TV", "MOVIE", "ONA", "OVA", "SPECIAL") }
        val type = getType(typeText)

        // [FIXED]: status dari label "ONGOING/COMPLETED" badge.
        val statusText = document.select("span").map { it.text().trim() }
            .firstOrNull { it.uppercase() in listOf("ONGOING", "COMPLETED", "TBA") }
        val status = getStatus(statusText)

        // [FIXED]: year dari link <a href*=/tahun/> atau dari <span>2025</span> di header.
        val year = document.selectFirst("a[href*=/tahun/]")?.text()?.trim()?.toIntOrNull()
            ?: document.select("span").map { it.text().trim() }
                .firstOrNull { it.matches(Regex("(19|20)\\d{2}")) }?.toIntOrNull()

        // [FIXED]: durasi dari Durasi field.
        val duration = getMetaValue("Durasi")
            ?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }

        // [FIXED]: score dari ★ rating di header — text "★ 8.5".
        val score = document.text().let { full ->
            Regex("★\\s*(\\d+(?:\\.\\d+)?)").find(full)?.groupValues?.get(1)
        }

        // [FIXED]: synopsis dari <h2>Sinopsis</h2> diikuti div.prose.
        val description = document.selectFirst("div.prose")?.text()?.trim()
            ?: document.selectFirst("section:has(h2:contains(Sinopsis)) div")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        // [FIXED]: episode list di section#episode-list, setiap episode adalah <a>
        // dengan <div class="text-sm font-bold">Episode N</div>.
        val episodes = document.select("section#episode-list a[href]")
            .ifEmpty { document.select("a[href]:has(div:matchesOwn(^Episode\\s*\\d+))") }
            .mapNotNull { ep ->
                val epHref = ep.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val epLink = if (epHref.startsWith("http")) epHref else "$mainUrl${epHref.removePrefix("/").let { "/$it" }}"
                val epTitle = ep.selectFirst("div")?.text()?.trim()
                    ?: ep.text().trim().substringBefore("\n").trim()
                val epNum = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    ?: epTitle.filter { it.isDigit() }.toIntOrNull()
                newEpisode(epLink) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }
            .distinctBy { it.data }
            .reversed()

        // [FIXED]: rekomendasi dari section "Anime Mirip".
        val recommendations = document.select("section:has(h2:contains(Anime Mirip)) a.card-netflix")
            .ifEmpty { document.select("section:has(h2:contains(Mirip)) a[href]") }
            .mapNotNull { it.toSearchResult() }
            .take(20)

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year)

        return newAnimeLoadResponse(title, url, type) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            this.duration = duration
            score?.let { addScore(it) }
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
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
        // [FIXED]: tidak ada lagi mirror dropdown base64-encoded. Halaman episode
        // sekarang merender langsung beberapa <iframe> dengan src ke pixeldrain,
        // mega.nz, dan provider lain. Ambil semua iframe, dedup, lalu serahkan
        // ke loadExtractor — pixeldrain dengan ?embed perlu dikonversi ke direct
        // streaming URL agar CloudStream dapat memutar.
        val document = runCatching { app.get(data).document }.getOrNull() ?: return false

        val iframes = document.select("iframe[src]")
            .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
            .distinct()

        if (iframes.isEmpty()) return false

        iframes.amap { rawSrc ->
            val src = httpsify(rawSrc)
            runCatching {
                when {
                    // pixeldrain.com/u/<id>?embed → langsung jadi /api/file/<id>
                    src.contains("pixeldrain.com", ignoreCase = true) -> {
                        val id = Regex("pixeldrain\\.com/u/([\\w-]+)").find(src)
                            ?.groupValues?.get(1)
                        if (id != null) {
                            callback.invoke(
                                newExtractorLink(
                                    "Pixeldrain",
                                    "Pixeldrain",
                                    "https://pixeldrain.com/api/file/$id?download"
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                    // [FIXED]: gomunime sekarang banyak pakai custom embed host
                    // (xtwap.top, gdplayer.to, dll.) yang tidak punya extractor
                    // di CloudStream. Stock loadExtractor() return 0 sources →
                    // user lihat "Link loading failed". Fallback: register
                    // iframe URL sebagai ExtractorLink langsung supaya
                    // CloudStream pakai WebView player.
                    else -> {
                        val resolvedCount = AtomicInteger(0)
                        loadExtractor(src, "$mainUrl/", subtitleCallback) { link ->
                            resolvedCount.incrementAndGet()
                            callback.invoke(link)
                        }
                        if (resolvedCount.get() == 0) {
                            // Extractor tidak resolve — daftarkan iframe sebagai-adanya.
                            val host = runCatching { java.net.URI(src).host }.getOrNull()
                                ?.removePrefix("www.") ?: "Embed"
                            callback.invoke(
                                newExtractorLink(host, host, src) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    }
                }
            }
        }

        return true
    }
}
