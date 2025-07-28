package eu.kanade.tachiyomi.animeextension.all.sudatchi

import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.sudatchi.dto.AnimeDetailDto
import eu.kanade.tachiyomi.animeextension.all.sudatchi.dto.HomePageDto
import eu.kanade.tachiyomi.animeextension.all.sudatchi.dto.SeriesDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.getPreferencesLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class Sudatchi : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Sudatchi"

    override val baseUrl = "https://sudatchi.com"

    override val client = network.client.newBuilder()
        .rateLimitHost("$baseUrl/api/".toHttpUrl(), 5)
        .build()

    override val lang = "all"

    override val supportsLatest = true

    private val langCodeRegex by lazy { Regex("""\((.*)\)""") }

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addPathSegment("series")
            addQueryParameter("page", page.toString())
            addQueryParameter("matureMode", preferences.mature.toString())
            addQueryParameter("sort", "POPULARITY_DESC")
            addQueryParameter("status", "RELEASING")
        }
        return GET(url.build(), headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val titleLang = preferences.title
        return response.parseAs<SeriesDto>().let { series ->
            AnimesPage(
                series.results.map { it.toSAnime(titleLang) }
                    .filterNot { it.status == SAnime.LICENSED },
                series.hasNextPage,
            )
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/api/home?matureMode=${preferences.mature}", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val titleLang = preferences.title
        return AnimesPage(
            response.parseAs<HomePageDto>().latestEpisodes
                .map { it.toSAnime(titleLang, baseUrl) },
            false,
        )
    }

    // =============================== Search ===============================
    override fun getFilterList() = SudatchiFilters.getFilterList()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id", headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val anime = animeDetailsParse(response)
        return AnimesPage(listOf(anime), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("api")
            addPathSegment("series")
            addQueryParameter("page", page.toString())
            addQueryParameter("matureMode", preferences.mature.toString())

            filters.filterIsInstance<SudatchiFilters.QueryParameterFilter>().forEach {
                val (name, value) = it.toQueryParameter()
                if (value != null) addQueryParameter(name, value)
            }

            query.trim().takeUnless { it.isBlank() }
                ?.let { addQueryParameter("search", it) }
        }

        return GET(url.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val titleLang = preferences.title
        val result = response.parseAs<SeriesDto>()
        return AnimesPage(
            result.results
                .map { it.toSAnime(titleLang) }
                .filterNot { it.status == SAnime.LICENSED },
            result.hasNextPage,
        )
    }

    // =========================== Related Anime ============================
    override fun relatedAnimeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val data = response.parseAs<AnimeDetailDto>()
        return (data.related.orEmpty() + data.recommendations.orEmpty())
            .map { it.toSAnime(preferences.title) }
    }

    // =========================== Anime Details ============================
    override fun getAnimeUrl(anime: SAnime) = "$baseUrl${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$baseUrl/api${anime.url}", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = response.parseAs<AnimeDetailDto>()
        return data.toSAnime(preferences.title)
    }

    // ============================== Episodes ==============================
    /* Can also parsing the episode page directly, but this way is more convenient.
       The episode page is at: https://sudatchi.com/watch/{animeId}/{episodeNumber}.
       Find all <script>self.__next_f.push([...])</script>
       Then regex with "self\.__next_f\.push\(\[\d+,\s?"(.*?)"\]\)"
       Then concatenate all the captured together.
       Afterward, replace "\n" with '\n' (but except "\\n").
       Then process line by line, pick only the one with "{\"metadata\":\[.*?],"
       Finally, parse the JSON to get streams & subtitles. Audio should already be included in the streams.
     */
    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val anime = response.parseAs<AnimeDetailDto>()
        return anime.episodes.map { it.toEpisode(animeId = anime.id) }.reversed()
            .ifEmpty { throw Exception("No episodes found") }
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode) = GET("$baseUrl${episode.url}", headers)

    private val playlistUtils: PlaylistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val episodeId = response.request.url.queryParameter("episode")?.toIntOrNull()
            ?: throw Exception("Episode ID not found in request URL")
        val episode = response.parseAs<AnimeDetailDto>().episodes.firstOrNull { it.id == episodeId }
            ?: throw Exception("Episode not found")

        val videoUrl = "$baseUrl/api/streams?episodeId=$episodeId"

        fun buildSubtitleUrl(subUrl: String): String {
            return if (subUrl.startsWith("http://") || subUrl.startsWith("https://")) {
                subUrl
            } else {
                "$baseUrl/api/proxy/${subUrl.removePrefix("/ipfs/")}"
            }
        }

        return playlistUtils.extractFromHls(
            videoUrl,
            subtitleList = (
                episode.subtitlesDto
                    ?.map {
                        Track(
                            url = buildSubtitleUrl(it.url),
                            lang = "${it.subtitleLang?.name ?: "???"} (${it.subtitleLang?.language ?: "???"})",
                        )
                    }
                    ?: episode.subtitles?.map {
                        Track(
                            url = buildSubtitleUrl(it.url),
                            lang = "${it.language ?: "???"} (${it.language ?: "???"})",
                        )
                    }
                    ?: emptyList()
                ).sort(),
        )
    }

    @JvmName("trackSort")
    private fun List<Track>.sort(): List<Track> {
        val subtitles = preferences.subtitles
        return map { (langCodeRegex.find(it.lang)?.groupValues?.getOrNull(1) ?: it.lang) to it }
            .sortedWith(
                compareBy(
                    { it.first != subtitles },
                    { it.first != PREF_SUBTITLES_DEFAULT },
                    { it.first },
                ),
            )
            .map { it.second }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES.map { it.first }.toTypedArray()
            entryValues = PREF_QUALITY_ENTRIES.map { it.second }.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUBTITLES_KEY
            title = PREF_SUBTITLES_TITLE
            entries = PREF_SUBTITLES_ENTRIES.map { it.first }.toTypedArray()
            entryValues = PREF_SUBTITLES_ENTRIES.map { it.second }.toTypedArray()
            setDefaultValue(PREF_SUBTITLES_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_TITLE_KEY
            title = PREF_TITLE_TITLE
            entries = PREF_TITLE_ENTRIES.map { it.first }.toTypedArray()
            entryValues = PREF_TITLE_ENTRIES.map { it.second }.toTypedArray()
            setDefaultValue(PREF_TITLE_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = PREF_MATURE_KEY
            title = PREF_MATURE_TITLE
            summary = PREF_MATURE_SUMMARY
            setDefaultValue(PREF_MATURE_DEFAULT)
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private val SharedPreferences.quality get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
    private val SharedPreferences.subtitles get() = getString(PREF_SUBTITLES_KEY, PREF_SUBTITLES_DEFAULT)!!
    private val SharedPreferences.title get() = getString(PREF_TITLE_KEY, PREF_TITLE_DEFAULT)!!
    private val SharedPreferences.mature get() = getBoolean(PREF_MATURE_KEY, PREF_MATURE_DEFAULT)

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf(
            Pair("1080p", "1080"),
            Pair("720p", "720"),
            Pair("480p", "480"),
        )

        private const val PREF_SUBTITLES_KEY = "preferred_subtitles"
        private const val PREF_SUBTITLES_TITLE = "Preferred subtitles"
        private const val PREF_SUBTITLES_DEFAULT = "eng"
        private val PREF_SUBTITLES_ENTRIES = arrayOf(
            Pair("Arabic (Saudi Arabia)", "ara"),
            Pair("Brazilian Portuguese", "por"),
            Pair("Chinese", "chi"),
            Pair("Croatian", "hrv"),
            Pair("Czech", "cze"),
            Pair("Danish", "dan"),
            Pair("Dutch", "dut"),
            Pair("English", "eng"),
            Pair("European Spanish", "spa-es"),
            Pair("Filipino", "fil"),
            Pair("Finnish", "fin"),
            Pair("French", "fra"),
            Pair("German", "deu"),
            Pair("Greek", "gre"),
            Pair("Hebrew", "heb"),
            Pair("Hindi", "hin"),
            Pair("Hungarian", "hun"),
            Pair("Indonesian", "ind"),
            Pair("Italian", "ita"),
            Pair("Japanese", "jpn"),
            Pair("Korean", "kor"),
            Pair("Latin American Spanish", "spa-419"),
            Pair("Malay", "may"),
            Pair("Norwegian Bokmål", "nob"),
            Pair("Polish", "pol"),
            Pair("Romanian", "rum"),
            Pair("Russian", "rus"),
            Pair("Swedish", "swe"),
            Pair("Thai", "tha"),
            Pair("Turkish", "tur"),
            Pair("Ukrainian", "ukr"),
            Pair("Vietnamese", "vie"),
        )

        private const val PREF_TITLE_KEY = "preferred_title"
        private const val PREF_TITLE_TITLE = "Preferred title"
        private const val PREF_TITLE_DEFAULT = "english"
        private val PREF_TITLE_ENTRIES = arrayOf(
            Pair("English", "english"),
            Pair("Romaji", "romaji"),
            Pair("Japanese", "japanese"),
        )

        private const val PREF_MATURE_KEY = "mature_mode"
        private const val PREF_MATURE_TITLE = "Mature Content"
        private const val PREF_MATURE_DEFAULT = false
        private const val PREF_MATURE_SUMMARY = "Include NSFW or adult material"
    }
}
