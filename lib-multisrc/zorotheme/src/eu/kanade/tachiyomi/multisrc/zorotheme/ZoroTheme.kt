package eu.kanade.tachiyomi.multisrc.zorotheme

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.multisrc.zorotheme.dto.HtmlResponse
import eu.kanade.tachiyomi.multisrc.zorotheme.dto.SourcesResponse
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelMapNotNull
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.LazyMutable
import extensions.utils.addListPreference
import extensions.utils.addSetPreference
import extensions.utils.addSwitchPreference
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.getValue

abstract class ZoroTheme(
    override val lang: String,
    override val name: String,
    override val baseUrl: String,
    private val hosterNames: List<String>,
) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val supportsLatest = true

    protected val preferences by getPreferencesLazy {
        clearOldHosts()
    }

    protected var docHeaders by LazyMutable {
        newHeaders()
    }

    protected fun newHeaders(): Headers {
        return headers.newBuilder().apply {
            add(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            add("Host", baseUrl.toHttpUrl().host)
            add("Referer", "$baseUrl/")
        }.build()
    }

    protected open val ajaxRoute = ""

    private var useEnglish by LazyMutable { preferences.getTitleLang == "English" }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/most-popular?page=$page", docHeaders)

    override fun popularAnimeSelector(): String = "div.flw-item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("div.film-detail a")!!.let {
            setUrlWithoutDomain(it.attr("href"))
            title = if (useEnglish && it.hasAttr("title")) {
                it.attr("title")
            } else {
                it.attr("data-jname")
            }
        }
        thumbnail_url = element.selectFirst("div.film-poster > img")!!.attr("data-src")
    }

    override fun popularAnimeNextPageSelector() = "li.page-item a[title=Next]"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/top-airing?page=$page", docHeaders)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = ZoroThemeFilters.getSearchParameters(filters)
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

    override fun getFilterList() = ZoroThemeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.anisc-poster img")!!.attr("src")

        document.selectFirst("div.anisc-info")!!.let { info ->
            author = info.getInfo("Studios:")
            status = parseStatus(info.getInfo("Status:"))
            genre = info.getInfo("Genres:", isList = true)

            description = buildString {
                info.getInfo("Overview:")?.also { append(it + "\n") }
                info.getInfo("Aired:", full = true)?.also(::append)
                info.getInfo("Premiered:", full = true)?.also(::append)
                info.getInfo("Synonyms:", full = true)?.also(::append)
                info.getInfo("Japanese:", full = true)?.also(::append)
            }
        }
    }

    open fun Element.getInfo(
        tag: String,
        isList: Boolean = false,
        full: Boolean = false,
    ): String? {
        if (isList) {
            return select("div.item-list:contains($tag) > a").eachText().joinToString()
        }
        val value = selectFirst("div.item-title:contains($tag)")
            ?.selectFirst("*.name, *.text")
            ?.text()
        return if (full && value != null) "\n$tag $value" else value
    }

    protected fun parseStatus(statusString: String?): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("-")
        return GET("$baseUrl/ajax$ajaxRoute/episode/list/$id", apiHeaders(baseUrl + anime.url))
    }

    override fun episodeListSelector() = "a.ep-item"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.parseAs<HtmlResponse>().getHtml()

        return document.select(episodeListSelector())
            .map(::episodeFromElement)
            .reversed()
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        episode_number = element.attr("data-number").toFloatOrNull() ?: 1F
        name = "Ep. ${element.attr("data-number")}: ${element.attr("title")}"
        setUrlWithoutDomain(element.attr("href"))
        if (element.hasClass("ssl-item-filler") && preferences.markFiller) {
            scanlator = "Filler Episode"
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val id = episode.url.substringAfterLast("?ep=")
        return GET("$baseUrl/ajax$ajaxRoute/episode/servers?episodeId=$id", apiHeaders(baseUrl + episode.url))
    }

    data class VideoData(
        val type: String,
        val link: String,
        val name: String,
    )

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).await()

        val episodeReferer = response.request.header("referer")!!
        val typeSelection = preferences.typeToggle
        val hosterSelection = preferences.hostToggle

        val serversDoc = response.parseAs<HtmlResponse>().getHtml()

        val embedLinks = listOf("servers-sub", "servers-dub", "servers-mixed", "servers-raw").map { type ->
            if (type !in typeSelection) return@map emptyList()

            serversDoc.select("div.$type div.item").parallelMapNotNull {
                val id = it.attr("data-id")
                val type = it.attr("data-type")
                val name = it.text()

                if (hosterSelection.contains(name, true).not()) return@parallelMapNotNull null

                val link = client.newCall(
                    GET("$baseUrl/ajax$ajaxRoute/episode/sources?id=$id", apiHeaders(episodeReferer)),
                ).await().parseAs<SourcesResponse>().link ?: ""

                VideoData(type, link, name)
            }
        }.flatten()

        return embedLinks.parallelCatchingFlatMap(::extractVideo)
    }

    abstract fun extractVideo(server: VideoData): List<Video>

    override fun videoListSelector() = throw UnsupportedOperationException()

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

    private fun apiHeaders(referer: String): Headers = headers.newBuilder().apply {
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
        val lang = preferences.prefLang
        val server = preferences.prefServer

        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains(server, true) }
                .thenByDescending { it.quality.contains(lang, true) },
        )
    }

    private var SharedPreferences.getTitleLang
        by preferences.delegate(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)

    private var SharedPreferences.markFiller
        by preferences.delegate(MARK_FILLERS_KEY, MARK_FILLERS_DEFAULT)

    private var SharedPreferences.prefQuality
        by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private var SharedPreferences.prefServer
        by preferences.delegate(PREF_SERVER_KEY, hosterNames.first())

    private var SharedPreferences.prefLang
        by preferences.delegate(PREF_LANG_KEY, PREF_LANG_DEFAULT)

    private var SharedPreferences.hostToggle
        by preferences.delegate(PREF_HOSTER_KEY, hosterNames.toSet())

    private var SharedPreferences.typeToggle
        by preferences.delegate(PREF_TYPE_TOGGLE_KEY, PREF_TYPES_TOGGLE_DEFAULT)

    companion object {
        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "Romaji"
        private val PREF_TITLE_LANG_LIST = listOf("Romaji", "English")

        private const val MARK_FILLERS_KEY = "mark_fillers"
        private const val MARK_FILLERS_DEFAULT = true

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "Sub"

        private const val PREF_SERVER_KEY = "preferred_server"

        private const val PREF_HOSTER_KEY = "hoster_selection"

        private const val PREF_TYPE_TOGGLE_KEY = "type_selection"
        private val TYPES_ENTRIES = listOf("Sub", "Dub", "Mixed", "Raw")
        private val TYPES_ENTRY_VALUES = listOf("servers-sub", "servers-dub", "servers-mixed", "servers-raw")
        private val PREF_TYPES_TOGGLE_DEFAULT = TYPES_ENTRY_VALUES.toSet()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred title language",
            entries = PREF_TITLE_LANG_LIST,
            entryValues = PREF_TITLE_LANG_LIST,
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
        ) {
            preferences.getTitleLang = it
            useEnglish = it == "English"
        }

        screen.addSwitchPreference(
            key = MARK_FILLERS_KEY,
            title = "Mark filler episodes",
            summary = "Mark filler episodes in the episode list",
            default = MARK_FILLERS_DEFAULT,
        ) {
            preferences.markFiller = it
        }

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
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

        screen.addListPreference(
            key = PREF_LANG_KEY,
            title = "Preferred Type",
            entries = TYPES_ENTRIES,
            entryValues = TYPES_ENTRIES,
            default = PREF_LANG_DEFAULT,
            summary = "%s",
        ) {
            preferences.prefLang = it
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

        screen.addSetPreference(
            key = PREF_TYPE_TOGGLE_KEY,
            title = "Enable/Disable Types",
            summary = "Select which video types to show in the episode list",
            entries = TYPES_ENTRIES,
            entryValues = TYPES_ENTRY_VALUES,
            default = PREF_TYPES_TOGGLE_DEFAULT,
        ) {
            preferences.typeToggle = it
        }
    }
}
