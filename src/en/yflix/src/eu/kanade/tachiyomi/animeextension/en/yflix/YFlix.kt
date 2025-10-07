package eu.kanade.tachiyomi.animeextension.en.yflix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMapNotNull
import extensions.utils.addListPreference
import extensions.utils.addSetPreference
import extensions.utils.delegate
import extensions.utils.getPreferences
import extensions.utils.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class YFlix : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "YFlix"

    override val lang = "en"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override var baseUrl: String = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
        private set

    override val client: OkHttpClient = network.client

    private val apiClient by lazy {
        client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36",
                    )
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private val rapidShareExtractor by lazy {
        RapidShareExtractor(client, headers, preferences)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browser?sort=trending&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browser?page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/browser".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .also { builder ->
                YFlixFilters.getFilters(filters).forEach {
                    it.addQueryParameters(builder)
                }
            }.build()
        return GET(url.toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.film-section div.item").mapNotNull { item ->
            val poster = item.selectFirst("a.poster") ?: return@mapNotNull null
            val title = item.selectFirst("a.title")?.text() ?: return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(poster.attr("href"))
                this.title = title
                thumbnail_url = item.selectFirst("img")?.attr("data-src")
            }
        }
        val hasNextPage = document.selectFirst("li.page-item a[rel=next]") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst("h1.title")?.text().orEmpty()
        thumbnail_url = document.selectFirst("div.poster img")?.attr("src")
        val isMovie = document.selectFirst(".metadata > span:contains(Movie)") != null
        status = if (isMovie) SAnime.COMPLETED else SAnime.ONGOING
        genre = document.select("ul.mics li:has(a[href*=/genre/]) a").eachText().joinToString()
        author = document.select("ul.mics li:has(a[href*=/production/]) a").eachText().joinToString()

        description = buildString {
            document.selectFirst(".description")?.text()?.also { append("$it\n\n") }

            val type = if (isMovie) "Movie" else "TV Show"
            append("**Type:** $type\n")

            fun getInfo(label: String): String? =
                document.selectFirst("ul.mics li:contains($label:)")
                    ?.text()?.substringAfter(":")?.trim()

            getInfo("Country")?.let { append("**Country:** $it\n") }
            getInfo("Released")?.let { append("**Released:** $it\n") }
            getInfo("Casts")?.let { append("**Casts:** $it\n") }

            document.selectFirst(".metadata .IMDb")?.text()?.let {
                val rating = it.substringAfter("IMDb").trim()
                if (rating.isNotEmpty()) append("**IMDb:** $rating")
            }

            document.selectFirst("div.detail-bg")?.attr("style")?.let { style ->
                val coverUrl = style.substringAfter("url('", "").substringBefore("')", "")
                if (coverUrl.isNotEmpty()) {
                    // Append the cover URL in Markdown format for display in the app
                    append("\n\n![Cover]($coverUrl)")
                }
            }
        }
    }
    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = YFlixFilters.FILTER_LIST

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = baseUrl + anime.url
        val document = client.newCall(GET(animeUrl, headers)).awaitSuccess().asJsoup()
        val contentId = document.selectFirst("div.rating[data-id]")?.attr("data-id")
            ?: return emptyList()

        val encryptedId = encrypt(contentId)
        val ajaxUrl = "$baseUrl/ajax/episodes/list?id=$contentId&_=$encryptedId"

        val resultDoc = client.newCall(GET(ajaxUrl, apiHeaders(animeUrl)))
            .awaitSuccess()
            .parseAs<ResultResponse>(json = json)
            .toDocument()

        return resultDoc.select("ul.episodes[data-season]").flatMap { seasonElement ->
            val seasonNum = seasonElement.attr("data-season")
            seasonElement.select("li a").map { element ->
                if (element.selectFirst("span.num") != null) {
                    tvEpisodeFromElement(element, anime.url, seasonNum)
                } else {
                    movieEpisodeFromElement(element, anime.url)
                }
            }
        }.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        throw UnsupportedOperationException("Not used.")

    private fun tvEpisodeFromElement(element: Element, animeUrl: String, seasonNum: String): SEpisode = SEpisode.create().apply {
        val epNum = element.attr("num")
        url = "$animeUrl#${element.attr("eid")}"
        episode_number = epNum.toFloatOrNull() ?: 0F
        name = "S$seasonNum E$epNum: ${element.selectFirst("span:not(.num)")?.text()?.trim()}"
        date_upload = parseDate(element.attr("title"))
    }

    private fun movieEpisodeFromElement(element: Element, animeUrl: String): SEpisode = SEpisode.create().apply {
        url = "$animeUrl#${element.attr("eid")}"
        episode_number = 1F
        name = element.selectFirst("span")?.text()?.trim() ?: "Movie"
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (animeUrl, episodeId) = episode.url.split('#', limit = 2)
        val referer = baseUrl + animeUrl

        val encryptedId = encrypt(episodeId)
        val serversUrl = "$baseUrl/ajax/links/list?eid=$episodeId&_=$encryptedId"

        val serversDoc = client.newCall(GET(serversUrl, apiHeaders(referer)))
            .awaitSuccess()
            .parseAs<ResultResponse>(json = json)
            .toDocument()

        val enabledHosts = preferences.getStringSet(PREF_HOSTER_KEY, SERVERS.toSet())!!

        return serversDoc.select("li.server").parallelMapNotNull { serverElement ->
            val serverName = serverElement.selectFirst("span")?.text() ?: return@parallelMapNotNull null
            if (serverName !in enabledHosts) return@parallelMapNotNull null

            runCatching {
                val serverId = serverElement.attr("data-lid")
                val encryptedServerId = encrypt(serverId)
                val viewUrl = "$baseUrl/ajax/links/view?id=$serverId&_=$encryptedServerId"

                val encryptedIframeResult = client.newCall(GET(viewUrl, apiHeaders(referer)))
                    .awaitSuccess()
                    .parseAs<ResultResponse>(json = json)
                    .result

                val iframeUrl = decrypt(encryptedIframeResult)
                rapidShareExtractor.videosFromUrl(iframeUrl, serverName)
            }.getOrNull()
        }.flatten()
    }

    // ============================= Utilities ==============================

    private fun apiHeaders(referer: String) = headers.newBuilder()
        .set("Referer", referer)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .add("X-Requested-With", "XMLHttpRequest")
        .build()

    private suspend fun encrypt(text: String): String {
        val response = apiClient.newCall(GET("https://enc-dec.app/api/enc-movies-flix?text=$text")).awaitSuccess()
        return response.parseAs<ResultResponse>(json = json).result
    }

    private suspend fun decrypt(text: String): String {
        val response = apiClient.newCall(GET("https://enc-dec.app/api/dec-movies-flix?text=$text")).awaitSuccess()
        return response.parseAs<DecryptedIframeResponse>(json = json).result.url
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }.getOrNull() ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val qualities = QUALITIES.reversed()

        return sortedWith(
            // Prioritize videos that have the exact preferred quality and server
            compareByDescending<Video> {
                it.quality.contains(quality, true) && it.quality.startsWith(server, true)
            }
                // Then, prioritize videos with the preferred quality from any server
                .thenByDescending { it.quality.contains(quality, true) }
                // Then, prioritize videos from the preferred server with any quality
                .thenByDescending { it.quality.startsWith(server, true) }
                // Finally, sort by the quality list as a fallback
                .thenByDescending { video -> qualities.indexOfFirst { video.quality.contains(it) } },
        )
    }

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain (requires restart)"
            entries = DOMAIN_ENTRIES
            entryValues = DOMAIN_VALUES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"
        }

        val qualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITIES
            entryValues = QUALITIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }

        val subLangPref = ListPreference(screen.context).apply {
            key = PREF_SUB_LANG_KEY
            title = "Preferred sub language"
            entries = SUB_LANGS
            entryValues = SUB_LANGS
            setDefaultValue(PREF_SUB_LANG_DEFAULT)
            summary = "%s"
        }

        val serverPref = ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVERS
            entryValues = SERVERS
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }

        val hosterPref = MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/disable servers"
            entries = SERVERS
            entryValues = SERVERS
            setDefaultValue(SERVERS.toSet())
            summary = "Select which video server to show in the episode list"
        }

        screen.addPreference(domainPref)
        screen.addPreference(qualityPref)
        screen.addPreference(subLangPref)
        screen.addPreference(serverPref)
        screen.addPreference(hosterPref)
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain_key"
        private val DOMAIN_ENTRIES = arrayOf("yflix.to")
        private val DOMAIN_VALUES = arrayOf("https://yflix.to")
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES.first()

        const val PREF_QUALITY_KEY = "pref_quality_key"
        private val QUALITIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_DEFAULT = QUALITIES.first()

        const val PREF_SUB_LANG_KEY = "pref_sub_lang_key"
        private val SUB_LANGS = arrayOf(
            "English", "Arabic", "Chinese", "French", "German", "Indonesian",
            "Italian", "Japanese", "Korean", "Portuguese", "Russian",
            "Spanish", "Turkish", "Vietnamese",
        )
        internal val PREF_SUB_LANG_DEFAULT = SUB_LANGS.first()

        const val PREF_SERVER_KEY = "pref_server_key"
        private val SERVERS = arrayOf("Server 1", "Server 2")
        private val PREF_SERVER_DEFAULT = SERVERS.first()

        const val PREF_HOSTER_KEY = "pref_hoster_key"

        private val DATE_FORMATTER by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH) }
    }
}
