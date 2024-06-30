package eu.kanade.tachiyomi.animeextension.en.anilist

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class AniList : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AniList"

    override val baseUrl = "https://anilist.co"

    private val apiUrl = "https://graphql.anilist.co"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.client

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val mappings by lazy {
        client.newCall(
            GET("https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json", headers),
        ).execute().parseAs<List<Mapping>>()
    }

    // ============================== Popular ===============================

    private fun createSortRequest(
        sort: String,
        page: Int,
        extraVar: Pair<String, String>? = null,
    ): Request {
        val variablesObject = buildJsonObject {
            put("page", page)
            put("perPage", PER_PAGE)
            put("sort", sort)
            put("type", "ANIME")
            extraVar?.let { put(extraVar.first, extraVar.second) }
            if (!preferences.allowAdult) put("isAdult", false)
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getSortQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    override fun popularAnimeRequest(page: Int): Request =
        createSortRequest("TRENDING_DESC", page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val titleLang = preferences.titleLang
        val page = response.parseAs<PagesResponse>().data.Page
        val hasNextPage = page.pageInfo.hasNextPage
        val animeList = page.media.map { it.toSAnime(titleLang) }

        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        createSortRequest("START_DATE_DESC", page, Pair("status", "RELEASING"))

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AniListFilters.getSearchParameters(filters)

        val variablesObject = buildJsonObject {
            put("page", page)
            put("perPage", PER_PAGE)
            put("sort", params.sort)
            if (query.isNotBlank()) put("search", query)

            if (params.genres.isNotEmpty()) {
                putJsonArray("genres") {
                    params.genres.forEach { add(it) }
                }
            }

            if (params.format.isNotEmpty()) {
                putJsonArray("format") {
                    params.format.forEach { add(it) }
                }
            }

            if (params.season.isBlank() && params.year.isNotBlank()) {
                put("year", "${params.year}%")
            }

            if (params.season.isNotBlank() && params.year.isBlank()) {
                throw Exception("Year cannot be blank if season is set")
            }

            if (params.season.isNotBlank() && params.year.isNotBlank()) {
                put("season", params.season)
                put("seasonYear", params.year)
            }

            if (params.status.isNotBlank()) {
                put("status", params.status)
            }

            put("type", "ANIME")
            if (!preferences.allowAdult) put("isAdult", false)
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getSortQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AniListFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String {
        return "$baseUrl/anime/${anime.url}"
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return if (currentAnime == anime.url) {
            SAnime.create().apply {
                thumbnail_url = coverList[coverIndex]
                coverIndex = (coverIndex + 1) % coverList.size
            }
        } else {
            super.getAnimeDetails(anime)
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val variablesObject = buildJsonObject {
            put("id", anime.url.toInt())
            put("type", "ANIME")
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getDetailsQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    private var coverList = emptyList<String>()
    private var coverIndex = 0
    private var currentAnime = ""
    private var currentEpisodeList = emptyList<SEpisode>()

    private val coverProviders by lazy { CoverProviders(client, headers) }

    override fun animeDetailsParse(response: Response): SAnime {
        val titleLang = preferences.titleLang
        val animeData = response.parseAs<DetailsResponse>().data.Media
        val anime = animeData.toSAnime(titleLang)

        if (currentAnime != anime.url) {
            val type = if (animeData.format == "MOVIE") "movies" else "tv"

            val data = mappings.firstOrNull {
                it.anilist_id == anime.url.toInt()
            }
            val malId = data?.mal_id?.toString()
            val tvdbId = data?.thetvdb_id?.toString()

            coverList = buildList {
                add(anime.thumbnail_url ?: "")
                malId?.let {
                    addAll(coverProviders.getMALCovers(malId))
                }
                tvdbId?.let {
                    addAll(coverProviders.getFanartCovers(tvdbId, type))
                }
            }.filter { it.isNotEmpty() }
            coverIndex = 0
            currentAnime = anime.url
        }

        return anime
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return if (currentAnime == anime.url) {
            currentEpisodeList
        } else {
            val episodeList = super.getEpisodeList(anime)
            currentEpisodeList = episodeList
            return episodeList
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val variablesObject = buildJsonObject {
            put("id", anime.url.toInt())
            put("type", "ANIME")
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getMalIdQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<AnilistToMalResponse>().data.Media
        val malId = data.idMal
        val anilistId = data.id

        val episodeData = client.newCall(
            anilistEpisodeRequest(anilistId),
        ).execute().parseAs<AniListEpisodeResponse>().data.Media
        val episodeCount = episodeData.nextAiringEpisode?.let { it.episode - 1 } ?: episodeData.episodes ?: 1

        if (malId != null) {
            val episodeList = runCatching {
                getFromMal(malId, episodeCount)
            }.getOrNull()

            if (episodeList != null) {
                return episodeList
            }
        }

        return (1..episodeCount).map {
            SEpisode.create().apply {
                name = "Episode $it"
                episode_number = it.toFloat()
                url = "$it"
            }
        }
    }

    private fun anilistEpisodeRequest(anilistId: Int): Request {
        val variablesObject = buildJsonObject {
            put("id", anilistId)
            put("type", "ANIME")
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getEpisodeQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    private fun getFromMal(id: Int, episodeCount: Int): List<SEpisode> {
        val docHeaders = headers.newBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            add("Host", "myanimelist.net")
        }.build()

        val document = client.newCall(
            GET("https://myanimelist.net/anime/$id", headers = docHeaders),
        ).execute().use { it.asJsoup() }

        val episodesElement = document.selectFirst("div#horiznav_nav > ul > li:has(a:matchesWholeOwnText(Episodes))")
        if (episodesElement == null) {
            val airedOn = document.selectFirst("div.spaceit_pad:has(>span:matchesWholeOwnText(Aired:))")?.let {
                parseDate(it.ownText().trim())
            } ?: -1L
            return listOf(
                SEpisode.create().apply {
                    name = "Episode 1"
                    episode_number = 1F
                    date_upload = airedOn
                    url = "1"
                },
            )
        }
        val episodesHeader = docHeaders.newBuilder().apply {
            add("Referer", "https://myanimelist.net/anime/$id")
        }.build()

        var episodesUrl = episodesElement.selectFirst("a")!!.attr("abs:href")
        var hasNextPage = true
        val episodeList = mutableListOf<SEpisode>()
        val markFillers = preferences.markFiller

        while (hasNextPage) {
            Thread.sleep(1000)

            val episodeDocument = client.newCall(
                GET(episodesUrl, headers = episodesHeader),
            ).execute().use { it.asJsoup() }

            episodeList.addAll(
                episodeDocument.select("table.episode_list tr.episode-list-data").map {
                    val number = it.selectFirst("td.episode-number[data-raw]")!!.attr("data-raw").toInt()
                    val airedOn = it.selectFirst("td.episode-aired")?.let {
                        parseDate(it.ownText().trim())
                    } ?: -1L
                    val epName = it.selectFirst("td.episode-title a")?.ownText()
                    val fullName = epName?.let { "Ep. $number - $epName" } ?: "Episode $number"
                    val scanlatorText = if (markFillers) {
                        it.selectFirst(".episode-title > span:containsWholeText(Filler)")?.let { "Filler Episode" }
                    } else {
                        null
                    }

                    SEpisode.create().apply {
                        date_upload = airedOn
                        episode_number = number.toFloat()
                        url = "$number"
                        name = SANITY_REGEX.replace(fullName) { m -> m.groupValues[1] }
                        scanlator = scanlatorText
                    }
                },
            )

            val nextPageElement = episodeDocument.selectFirst("div.pagination > a.link.current + a")
            if (nextPageElement == null) {
                hasNextPage = false
            } else {
                episodesUrl = nextPageElement.attr("abs:href")
            }
        }

        (episodeList.size + 1..episodeCount).forEach {
            episodeList.add(
                SEpisode.create().apply {
                    episode_number = it.toFloat()
                    url = "$it"
                    name = "Ep. $it"
                },
            )
        }

        return episodeList.filter { it.episode_number <= episodeCount }.sortedBy { -it.episode_number }
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = throw Exception("Why")

    override fun videoListParse(response: Response): List<Video> = throw Exception("No videos")

    // ============================= Utilities ==============================

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
        }

        private val SANITY_REGEX by lazy { Regex("""^Ep. \d+ - (Episode \d+)${'$'}""") }

        private const val PER_PAGE = 20

        private const val MARK_FILLERS_KEY = "preferred_mark_fillers"
        private const val MARK_FILLERS_DEFAULT = true

        private const val PREF_ALLOW_ADULT_KEY = "preferred_allow_adult"
        private const val PREF_ALLOW_ADULT_DEFAULT = false

        private const val PREF_TITLE_LANG_KEY = "preferred_title"
        private const val PREF_TITLE_LANG_DEFAULT = "romaji"
    }

    private val SharedPreferences.markFiller
        get() = getBoolean(MARK_FILLERS_KEY, MARK_FILLERS_DEFAULT)

    private val SharedPreferences.allowAdult
        get() = getBoolean(PREF_ALLOW_ADULT_KEY, PREF_ALLOW_ADULT_DEFAULT)

    private val SharedPreferences.titleLang
        get() = getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)!!

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALLOW_ADULT_KEY
            title = "Allow adult content"
            setDefaultValue(PREF_ALLOW_ADULT_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred title language"
            entries = arrayOf("Romaji", "English", "Native")
            entryValues = arrayOf("romaji", "english", "native")
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = MARK_FILLERS_KEY
            title = "Mark filler episodes"
            setDefaultValue(MARK_FILLERS_DEFAULT)
        }.also(screen::addPreference)
    }
}
