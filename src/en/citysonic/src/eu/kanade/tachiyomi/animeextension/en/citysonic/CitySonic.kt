package eu.kanade.tachiyomi.animeextension.en.citysonic

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.videostrextractor.VideoStrExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelFlatMap
import eu.kanade.tachiyomi.util.parallelMapNotNull
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.LazyMutable
import extensions.utils.addListPreference
import extensions.utils.addSetPreference
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CitySonic(
    override val lang: String = "en",
    override val name: String = "CitySonic",
    override val supportsLatest: Boolean = true,
) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    private val preferences by getPreferencesLazy {
        clearOldHosts()
    }

    private var docHeaders by LazyMutable {
        newHeaders()
    }

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    private fun newHeaders(): Headers {
        return headers.newBuilder().apply {
            add(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            add("Host", baseUrl.toHttpUrl().host)
            add("Referer", "$baseUrl/")
        }.build()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movie?page=$page", docHeaders)

    override fun popularAnimeSelector(): String = "div.flw-item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("div.film-detail a")!!.let {
            setUrlWithoutDomain(it.attr("href"))
            title = it.attr("title")
        }
        thumbnail_url = element.selectFirst("div.film-poster > img")!!.attr("data-src")
    }

    override fun popularAnimeNextPageSelector() = "li.page-item a[title=Next]"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/tv-show?page=$page", docHeaders)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = Filters.getSearchParameters(filters)
        val endpoint = if (query.isEmpty()) "filter" else "search"

        val url = "$baseUrl/$endpoint".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addIfNotBlank("keyword", query)
            addIfNotBlank("type", params.type)
            addIfNotBlank("status", params.status)
            addIfNotBlank("rated", params.rated)
            addIfNotBlank("score", params.score)
            addIfNotBlank("season", params.season)
            addIfNotBlank("language", params.language)
            addIfNotBlank("sort", params.sort)
            addIfNotBlank("sy", params.start_year)
            addIfNotBlank("sm", params.start_month)
            addIfNotBlank("sd", params.start_day)
            addIfNotBlank("ey", params.end_year)
            addIfNotBlank("em", params.end_month)
            addIfNotBlank("ed", params.end_day)
            addIfNotBlank("genres", params.genres)
        }.build()

        return GET(url, docHeaders)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // ============================== Filters ===============================

    override fun getFilterList() = Filters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.selectFirst("div.detail-infor")!!.run {
            thumbnail_url = selectFirst("div.film-poster img")!!.attr("src")
            author = getInfo(tag = "Production:", isList = true)
            genre = getInfo("Genre:", isList = true)

            val url = document.location()
            val type = if (url.contains("/tv/")) {
                status = SAnime.ONGOING
                update_strategy = AnimeUpdateStrategy.ALWAYS_UPDATE
                "TV Shows"
            } else {
                status = SAnime.COMPLETED
                update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
                "Movies"
            }

            val trailer = document.selectFirst("#modaltrailer iframe")?.let {
                val trailerUrl = it.attr("data-src")
                "[Trailer]($trailerUrl)"
            }
            description = buildString {
                selectFirst(".description")?.let { append("${it.text()}\n\n") }
                append("**Type:** $type")
                getInfo("Country:", true)?.let(::append)
                getInfo("Casts:", true, isList = true)?.let(::append)
                getInfo("Released:", true)?.let(::append)
                getInfo("Duration:", true)?.let(::append)
                selectFirst("div.dp-i-stats span.item:last-child")
                    ?.let { append("\n${it.text()}") }
                trailer?.let { append("\n\n$it") }
            }
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val relatedAnimeSelector = ".block_area_sidebar .block_area-header:contains(Related Anime) + .block_area-content ul > li"

        val document = response.asJsoup()
        return listOf(relatedAnimeSelector, relatedAnimeListSelector()).flatMap { selector ->
            document.select(selector).map { relatedAnimeFromElement(it) }
        }
    }

    private fun Element.getInfo(
        tag: String,
        includeTag: Boolean = false,
        isList: Boolean = false,
    ): String? {
        val value = if (isList) {
            select("div.row-line:contains($tag) > a").eachText().joinToString().ifBlank { null }
        } else {
            selectFirst("div.row-line:contains($tag)")
                ?.ownText()?.ifBlank { null }
        }
        return if (includeTag && value != null) "\n**$tag** $value" else value
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "a.eps-item"

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.substringAfterLast("-")
        if (anime.url.startsWith("/movie/")) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    setUrlWithoutDomain("/ajax/episode/list/$id")
                    episode_number = 1F
                },
            )
        }

        val seasonRequest = GET("$baseUrl/ajax/season/list/$id", apiHeaders(baseUrl + anime.url))
        return client.newCall(seasonRequest).awaitSuccess().use { response ->
            response.asJsoup().select(".ss-item")
                .parallelFlatMap(::seasonFromElement).reversed()
        }
    }

    private suspend fun seasonFromElement(element: Element): List<SEpisode> = client.runCatching {
        val season = element.elementSiblingIndex() + 1
        val seasonId = element.attr("data-id")
        newCall(GET("$baseUrl/ajax/season/episodes/$seasonId"))
            .awaitSuccess().use { response ->
                response.asJsoup().select(episodeListSelector()).map {
                    it.attr("data-season", "%02d".format(season)).let(::episodeFromElement)
                }
            }
    }.getOrElse {
        emptyList()
    }

    private val episodeRegex by lazy { """Eps (\d+)""".toRegex() }
    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        if (element.hasClass("link-item")) {
            val linkId = element.attr("data-linkid")
                .ifEmpty { element.attr("data-id") }
            setUrlWithoutDomain("/ajax/episode/sources/$linkId")
        } else {
            setUrlWithoutDomain("/ajax/episode/servers/" + element.attr("data-id"))
        }

        val ssNum = element.attr("data-season").ifEmpty { "0" }
        val title = element.attr("title").ifEmpty { element.text() }
        val epNum = episodeRegex.find(title)?.run { "%03d".format(groupValues[1].toInt()) } ?: "0"

        episode_number = "$ssNum.$epNum".toFloat()
        name = when {
            episode_number != 0.0F -> "S$ssNum E$epNum ${title.substringAfter(": ")}"
            else -> title
        }
    }

    // ============================ Video Links =============================

    override fun videoListSelector() = "a.link-item"

    override fun videoListRequest(episode: SEpisode): Request {
        return GET("$baseUrl${episode.url}", apiHeaders())
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        client.newCall(videoListRequest(episode)).awaitSuccess().use { response ->

            val episodeReferer = response.request.header("referer")!!
            val hosterSelection = preferences.hostToggle

            val serversDoc = response.asJsoup()

            val embedLinks = serversDoc.select(videoListSelector()).parallelMapNotNull {
                val id = it.attr("data-linkid")
                    .ifEmpty { it.attr("data-id") }
                val name = it.select("span").text()

                if (hosterSelection.contains(name, true).not()) return@parallelMapNotNull null

                val link = client.newCall(
                    GET("$baseUrl/ajax/episode/sources/$id", apiHeaders(episodeReferer)),
                ).await().parseAs<SourcesResponse>().link ?: ""

                VideoData(link, name)
            }

            return embedLinks.parallelCatchingFlatMap(::extractVideo)
        }
    }

    private var videoStrExtractor by LazyMutable { VideoStrExtractor(client, headers, BuildConfig.MEGACLOUD_API) }

    private fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "UpCloud", "MegaCloud", "AKCloud" -> videoStrExtractor.getVideosFromUrl(server.link, server.name)
            else -> emptyList()
        }
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun SharedPreferences.clearOldHosts(): SharedPreferences {
        val hostToggle = getStringSet(PREF_HOSTER_KEY, hosterNames.toSet()) ?: return this
        if (hostToggle.all { hosterNames.contains(it) }) {
            return this
        }

        edit()
            .remove(PREF_HOSTER_KEY)
            .putStringSet(PREF_HOSTER_KEY, hosterNames.toSet())
            .remove(PREF_SERVER_KEY)
            .putString(PREF_SERVER_KEY, hosterNames.first())
            .apply()
        return this
    }

    private fun Set<String>.contains(s: String, ignoreCase: Boolean): Boolean {
        return any { it.equals(s, ignoreCase) }
    }

    private fun apiHeaders(referer: String = "$baseUrl/"): Headers = headers.newBuilder().apply {
        add("Accept", "*/*")
        add("Host", baseUrl.toHttpUrl().host)
        add("Referer", referer)
        add("X-Requested-With", "XMLHttpRequest")
    }.build()

    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String): HttpUrl.Builder {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
        return this
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.prefQuality
        val server = preferences.prefServer
        val qualitiesList = PREF_QUALITY_ENTRIES.reversed()

        return sortedByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it) } }
            .sortedWith(
                compareByDescending<Video> { it.quality.contains(quality) }
                    .thenByDescending { it.quality.contains(server, true) },
            )
    }

    private var SharedPreferences.prefQuality
        by LazyMutable { preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!! }

    private var SharedPreferences.prefServer
        by LazyMutable { preferences.getString(PREF_SERVER_KEY, hosterNames.first())!! }

    private var SharedPreferences.hostToggle
        by LazyMutable { preferences.getStringSet(PREF_HOSTER_KEY, hosterNames.toSet())!! }

    companion object {
        private val hosterNames = listOf(
            "UpCloud",
            "MegaCloud",
            "AKCloud",
        )

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf(
            "citysonic.tv",
            "gomovies.sx",
            "himovies.sx",
            "fmoviesz.ms",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_ENTRIES.first()

        private const val PREF_SERVER_KEY = "preferred_server"

        private const val PREF_HOSTER_KEY = "hoster_selection"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        ) {
            preferences.prefQuality = it
        }

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = hosterNames,
            entryValues = hosterNames,
            default = hosterNames.first(),
            summary = "%s",
        ) {
            preferences.prefServer = it
        }

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Enable/Disable Hosts",
            summary = "Select which video hosts to show in the episode list",
            entries = hosterNames,
            entryValues = hosterNames,
            default = hosterNames.toSet(),
        ) {
            preferences.hostToggle = it
        }

        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            baseUrl = it
            docHeaders = newHeaders()
            videoStrExtractor = VideoStrExtractor(client, headers, BuildConfig.MEGACLOUD_API)
        }
    }
}
