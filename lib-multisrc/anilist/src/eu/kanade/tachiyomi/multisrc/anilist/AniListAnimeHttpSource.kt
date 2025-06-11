package eu.kanade.tachiyomi.multisrc.anilist

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

abstract class AniListAnimeHttpSource : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AniList"
    override val lang = "en"

    override val supportsLatest = true

    open val apiUrl = "https://graphql.anilist.co"

    open val networkBuilder = network.client.newBuilder()
        .rateLimitHost("https://graphql.anilist.co".toHttpUrl(), 90)

    override val client by lazy { networkBuilder.build() }

    val json by injectLazy<Json>()

    protected val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    /* =============================== Mapping AniList <> Source =============================== */
    abstract fun mapAnimeDetailUrl(animeId: Int): String

    abstract fun mapAnimeId(animeDetailUrl: String): Int

    open val SharedPreferences.preferredTitleLang: TitleLanguage
        get() {
            val preferredLanguage = preferences.getString(PREF_TITLE_LANGUAGE_KEY, PREF_TITLE_LANGUAGE_DEFAULT)

            return when (preferredLanguage) {
                "romaji" -> TitleLanguage.ROMAJI
                "english" -> TitleLanguage.ENGLISH
                "native" -> TitleLanguage.NATIVE
                else -> TitleLanguage.ROMAJI
            }
        }

    open val SharedPreferences.allowAdult
        get() = getBoolean(PREF_ALLOW_ADULT_KEY, PREF_ALLOW_ADULT_DEFAULT)

    open val SharedPreferences.isAdult
        get() = false.takeUnless { allowAdult }

    /* ===================================== Popular Anime ===================================== */
    override fun popularAnimeRequest(page: Int): Request {
        return buildAnimeListRequest(
            query = ANIME_LIST_QUERY,
            variables = AnimeListVariables(
                page = page,
                sort = AnimeListVariables.MediaSort.TRENDING_DESC,
                isAdult = preferences.isAdult,
            ),
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseAnimeListResponse(response)
    }

    /* ===================================== Latest Anime ===================================== */
    override fun latestUpdatesRequest(page: Int): Request {
        return buildAnimeListRequest(
            query = LATEST_ANIME_LIST_QUERY,
            variables = AnimeListVariables(
                page = page,
                sort = AnimeListVariables.MediaSort.START_DATE_DESC,
                isAdult = preferences.isAdult,
            ),
        )
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseAnimeListResponse(response)
    }

    /* ===================================== Search Anime ===================================== */
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AniListFilters.getSearchParameters(filters)

        val variablesObject = buildJsonObject {
            put("page", page)
            put("perPage", 30)
            put("isAdult", preferences.allowAdult)
            put("type", "ANIME")
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
        }
        val variables = json.encodeToString(variablesObject)

        return buildRequest(query = SORT_QUERY, variables = variables)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseAnimeListResponse(response)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AniListFilters.FILTER_LIST

    /* ===================================== Anime Details ===================================== */
    override fun animeDetailsRequest(anime: SAnime): Request {
        return buildRequest(
            query = ANIME_DETAILS_QUERY,
            variables = json.encodeToString(AnimeDetailsVariables(mapAnimeId(anime.url))),
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val media = response.parseAs<AniListAnimeDetailsResponse>().data.media

        return media.toSAnime()
    }

    override fun getAnimeUrl(anime: SAnime): String {
        return anime.url
    }

    /* ====================================== Preferences ====================================== */

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANGUAGE_KEY
            title = "Preferred title language"
            entries = PREF_TITLE_LANGUAGE_ENTRIES
            entryValues = PREF_TITLE_LANGUAGE_ENTRY_VALUES
            setDefaultValue(PREF_TITLE_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                Toast.makeText(screen.context, "Refresh your anime library to apply changes", Toast.LENGTH_LONG).show()
                preferences.edit().putString(key, entry).apply()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALLOW_ADULT_KEY
            title = "Allow adult content"
            setDefaultValue(PREF_ALLOW_ADULT_DEFAULT)
        }.also(screen::addPreference)
    }

    open fun addAdultPreferences(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALLOW_ADULT_KEY
            title = "Allow adult content"
            summary = "Enable to show anime with adult content"
            setDefaultValue(PREF_ALLOW_ADULT_DEFAULT)
        }.also(screen::addPreference)
    }

    /* ==================================== AniList Utility ==================================== */
    private fun buildAnimeListRequest(
        query: String,
        variables: AnimeListVariables,
    ): Request {
        return buildRequest(query, json.encodeToString(variables))
    }

    private fun buildRequest(query: String, variables: String): Request {
        val requestBody = FormBody.Builder()
            .add("query", query)
            .add("variables", variables)
            .build()

        return POST(url = apiUrl, body = requestBody)
    }

    private fun parseAnimeListResponse(response: Response): AnimesPage {
        val page = response.parseAs<AniListAnimeListResponse>().data.page

        return AnimesPage(
            animes = page.media.map { it.toSAnime() },
            hasNextPage = page.pageInfo.hasNextPage,
        )
    }

    open fun AniListMedia.toSAnime(): SAnime {
        return toSAnime(preferences.preferredTitleLang, ::mapAnimeDetailUrl)
    }

    companion object {
        enum class TitleLanguage {
            ROMAJI,
            ENGLISH,
            NATIVE,
        }

        const val PREF_TITLE_LANGUAGE_KEY = "preferred_title_lang"
        val PREF_TITLE_LANGUAGE_ENTRIES = arrayOf("Romaji", "English", "Native")
        val PREF_TITLE_LANGUAGE_ENTRY_VALUES = arrayOf("romaji", "english", "native")
        const val PREF_TITLE_LANGUAGE_DEFAULT = "romaji"

        const val PREF_ALLOW_ADULT_KEY = "preferred_allow_adult"
        const val PREF_ALLOW_ADULT_DEFAULT = false
    }
}
