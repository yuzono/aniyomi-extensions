package eu.kanade.tachiyomi.multisrc.anilist

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.multisrc.anilist.AniListQueries.ANIME_DETAILS_QUERY
import eu.kanade.tachiyomi.multisrc.anilist.AniListQueries.SORT_QUERY
import eu.kanade.tachiyomi.multisrc.anilist.AniListQueries.TRENDING_ANIME_LIST_QUERY
import eu.kanade.tachiyomi.multisrc.anilist.AniListQueries.latestAnilistQuery
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    open val SharedPreferences.isAdult
        get() = (
            getString(PREF_ADULT_CONTENT_KEY, PREF_ADULT_CONTENT_DEFAULT)
                ?: PREF_ADULT_CONTENT_DEFAULT
            )
            .toBooleanStrictOrNull()

    open val countryOfOrigin: String? = null

    open val extraLatestMediaFields = ""

    /* ===================================== Popular Anime ===================================== */
    override fun popularAnimeRequest(page: Int): Request {
        return buildAnimeListRequest(
            query = TRENDING_ANIME_LIST_QUERY,
            variables = AniListQueries.AnimeListVariables(
                page = page,
                sort = AniListQueries.AnimeListVariables.MediaSort.TRENDING_DESC.toString(),
                isAdult = preferences.isAdult,
                countryOfOrigin = countryOfOrigin,
            ),
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseAnimeListResponse(response)
    }

    /* ===================================== Latest Anime ===================================== */
    override fun latestUpdatesRequest(page: Int): Request {
        return buildAnimeListRequest(
            query = latestAnilistQuery(extraLatestMediaFields),
            variables = AniListQueries.AnimeListVariables(
                page = page,
                sort = AniListQueries.AnimeListVariables.MediaSort.START_DATE_DESC.toString(),
                isAdult = preferences.isAdult,
                countryOfOrigin = countryOfOrigin,
            ),
        )
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseAnimeListResponse(response)
    }

    /* ===================================== Search Anime ===================================== */
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AniListFilters.getSearchParameters(filters)

        val variables = AniListQueries.AnimeListVariables(
            page = page,
            sort = params.sort,
            isAdult = preferences.isAdult,
            countryOfOrigin = params.country.takeIf { it.isNotBlank() },
            search = query.takeIf { it.isNotBlank() },
            genres = params.genres[0].takeIf { it.isNotEmpty() },
            excludedGenres = params.genres[1].takeIf { it.isNotEmpty() },
            tags = params.tags[0].takeIf { it.isNotEmpty() },
            excludedTags = params.tags[1].takeIf { it.isNotEmpty() },
            format = params.format.takeIf { it.isNotEmpty() },
            year = params.year.takeIf { it.isNotBlank() && params.season.isBlank() }?.let { "$it%" },
            season = params.season.takeIf { it.isNotBlank() && params.year.isNotBlank() },
            seasonYear = params.year.takeIf { it.isNotBlank() && params.season.isNotBlank() },
            status = params.status.takeIf { it.isNotBlank() },
        )

        return buildAnimeListRequest(
            query = SORT_QUERY,
            variables = variables,
        )
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
            variables = json.encodeToString(AniListQueries.AnimeDetailsVariables(mapAnimeId(anime.url))),
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
    }

    open fun addAdultPreferences(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_ADULT_CONTENT_KEY
            title = "Allow adult content"
            entries = PREF_ADULT_CONTENT_ENTRIES
            entryValues = PREF_ADULT_CONTENT_ENTRY_VALUES
            setDefaultValue(PREF_ADULT_CONTENT_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    /* ==================================== AniList Utility ==================================== */
    fun buildAnimeListRequest(
        query: String,
        variables: AniListQueries.AnimeListVariables,
    ): Request {
        return buildRequest(query, json.encodeToString(variables))
    }

    fun buildRequest(query: String, variables: String): Request {
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

        const val PREF_ADULT_CONTENT_KEY = "preferred_adult_content"
        val PREF_ADULT_CONTENT_ENTRIES = arrayOf("No Adult Content", "Allow Adult Content", "Only Adult Content")
        val PREF_ADULT_CONTENT_ENTRY_VALUES = arrayOf("false", "null", "true")
        const val PREF_ADULT_CONTENT_DEFAULT = "false"
    }
}
