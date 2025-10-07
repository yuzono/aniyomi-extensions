package eu.kanade.tachiyomi.animeextension.en.hexawatch

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale

class HexaWatch : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "HexaWatch"

    override val baseUrl = "https://hexa.watch"
    private val apiUrl = "https://themoviedb.hexa.watch/api/tmdb"
    private val subtitleUrl = "https://sub.wyzie.ru"
    private val decryptionApiUrl = "https://enc-dec.app/api/dec-hexa"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$apiUrl/trending/all/day?language=en-US&page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseMediaPage(response)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val preferredLatest = preferences.getString(PREF_LATEST_KEY, "movie")
        return GET("$apiUrl/$preferredLatest/popular?language=en-US&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseMediaPage(response)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$apiUrl/search/multi?query=$query&language=en-US&page=$page", headers)
        } else {
            val type = filters.filterIsInstance<HexaWatchFilters.TypeFilter>().first().state.let {
                if (it == 0) "movie" else "tv"
            }
            val sortFilter = filters.filterIsInstance<HexaWatchFilters.SortFilter>().first()
            val sortBy = sortFilter.state?.run {
                when (index) {
                    0 -> "popularity"
                    1 -> "vote_average"
                    else -> if (type == "movie") "primary_release_date" else "first_air_date"
                } + if (ascending) ".asc" else ".desc"
            } ?: "popularity.desc"

            val genreMap = if (type == "movie") HexaWatchFilters.MOVIE_GENRE_MAP else HexaWatchFilters.TV_GENRE_MAP
            val genres = filters.filterIsInstance<HexaWatchFilters.GenreFilter>().first()
                .state.filter { it.state }.mapNotNull { genreMap[it.name] }.joinToString(",")

            val url = buildString {
                append("$apiUrl/discover/$type?sort_by=$sortBy&language=en-US&page=$page")
                if (genres.isNotBlank()) {
                    append("&with_genres=$genres")
                }
            }
            GET(url, headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseMediaPage(response)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = HexaWatchFilters.getFilterList()

    // ============================== Details ===============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET(apiUrl + anime.url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseBody = response.body.string()
        return try {
            if ("/movie/" in response.request.url.toString()) {
                movieDetailsParse(responseBody)
            } else {
                tvDetailsParse(responseBody)
            }
        } catch (e: Exception) {
            throw Exception("Failed to parse details. The API might have returned an error page.", e)
        }
    }

    private fun movieDetailsParse(responseBody: String): SAnime {
        val movie = json.decodeFromString<MovieDetailDto>(responseBody)
        return SAnime.create().apply {
            title = movie.title
            url = "/movie/${movie.id}"
            thumbnail_url = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = movie.productionCompanies.joinToString { it.name }
            val score = String.format(Locale.US, "%.1f", movie.voteAverage)
            description = movie.overview + "\n\n**Score:** $score"
            genre = movie.genres.joinToString { it.name }
            status = statusParser(movie.status)
            initialized = true
        }
    }

    private fun tvDetailsParse(responseBody: String): SAnime {
        val tv = json.decodeFromString<TvDetailDto>(responseBody)
        return SAnime.create().apply {
            title = tv.name
            url = "/tv/${tv.id}"
            thumbnail_url = tv.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = tv.productionCompanies.joinToString { it.name }
            artist = tv.networks.joinToString { it.name }
            val score = String.format(Locale.US, "%.1f", tv.voteAverage)
            description = tv.overview + "\n\n**Score:** $score"
            genre = tv.genres.joinToString { it.name }
            status = statusParser(tv.status)
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        return animeDetailsRequest(anime)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseBody = response.body.string()
        return if ("/tv/" in response.request.url.toString()) {
            val tv = json.decodeFromString<TvDetailDto>(responseBody)
            tv.seasons.sortedByDescending { it.seasonNumber }
                .filter { it.seasonNumber > 0 }
                .flatMap { season ->
                    runCatching {
                        val seasonResponse = client.newCall(
                            GET("$apiUrl/tv/${tv.id}/season/${season.seasonNumber}", headers),
                        ).execute()
                        val episodes = seasonResponse.parseAs<TvSeasonDetailDto>().episodes.sortedByDescending { it.episodeNumber }
                        episodes.map { episode ->
                            SEpisode.create().apply {
                                name = "S${season.seasonNumber} E${episode.episodeNumber} - ${episode.name}"
                                episode_number = episode.episodeNumber.toFloat()
                                date_upload = parseDate(episode.airDate)
                                url = "/tv/${tv.id}/${season.seasonNumber}/${episode.episodeNumber}"
                            }
                        }
                    }.getOrElse { emptyList() }
                }.ifEmpty {
                    throw Exception("No episodes found.")
                }
        } else {
            val movie = json.decodeFromString<MovieDetailDto>(responseBody)
            listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1.0f
                    date_upload = parseDate(movie.releaseDate)
                    url = "/movie/${movie.id}"
                },
            )
        }
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        val key = ByteArray(32).apply { SECURE_RANDOM.nextBytes(this) }
            .joinToString("") { "%02x".format(it) }

        val videoHeaders = headers.newBuilder()
            .add("X-Api-Key", key)
            .build()

        val path = episode.url.split("/").drop(1)
        val requestUrl = when (path.first()) {
            "movie" -> "$apiUrl/movie/${path[1]}/images"
            "tv" -> "$apiUrl/tv/${path[1]}/season/${path[2]}/episode/${path[3]}/images"
            else -> throw Exception("Invalid media type for video request")
        }
        return GET(requestUrl, videoHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val encryptedText = response.body.string()
        val key = response.request.header("X-Api-Key") ?: throw Exception("API Key was not sent in the request")

        val decryptionPayload = json.encodeToString(mapOf("text" to encryptedText, "key" to key))
        val requestBody = decryptionPayload.toRequestBody("application/json".toMediaType())

        val decryptionResponse = client.newCall(
            Request.Builder().url(decryptionApiUrl).post(requestBody).build(),
        ).execute()

        if (!decryptionResponse.isSuccessful) {
            throw Exception("Decryption failed. HTTP ${decryptionResponse.code}: ${decryptionResponse.body.string()}")
        }

        val extractorData = decryptionResponse.parseAs<ExtractorResponseDto>()
        val subtitles = getSubtitles(response.request.url.toString())

        val videos = extractorData.result.sources.flatMap { source ->
            try {
                playlistUtils.extractFromHls(
                    playlistUrl = source.url,
                    videoNameGen = { quality -> "Server: ${source.server} - $quality" },
                    subtitleList = subtitles,
                )
            } catch (_: Exception) {
                emptyList()
            }
        }

        if (videos.isEmpty()) {
            throw Exception("No videos found after extraction. Check extractor API response.")
        }

        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, "1080")
        return videos.sortedByDescending { preferredQuality?.let(it.quality::contains) ?: false }
    }

    private fun getSubtitles(requestUrl: String): List<Track> {
        val match = GET_SUBTITLES_REGEX.find(requestUrl)
            ?: return emptyList()

        val (mediaType, mediaId, season, episode) = match.destructured

        val subtitleRequestUrl = when (mediaType) {
            "movie" -> "$subtitleUrl/search?id=$mediaId"
            "tv" -> "$subtitleUrl/search?id=$mediaId&season=$season&episode=$episode"
            else -> return emptyList()
        }

        return try {
            val preferredSubLang = preferences.getString(PREF_SUB_KEY, "en")

            val subLimit = preferences.getString(PREF_SUB_LIMIT_KEY, PREF_SUB_LIMIT_DEFAULT)?.toIntOrNull() ?: PREF_SUB_LIMIT_DEFAULT.toInt()
            val subtitleResponse = client.newCall(GET(subtitleRequestUrl, headers)).execute()
            subtitleResponse.parseAs<List<SubtitleDto>>()
                .take(subLimit)
                .map { sub ->
                    val langLabel = if (sub.isHearingImpaired) "${sub.language} (CC)" else sub.language
                    Track(sub.url, langLabel)
                }
                .sortedByDescending { preferredSubLang?.let(it.lang::startsWith) ?: false }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"
        }

        val latestPref = ListPreference(screen.context).apply {
            key = PREF_LATEST_KEY
            title = "Preferred 'Latest' Page"
            entries = arrayOf("Movies", "TV Shows")
            entryValues = arrayOf("movie", "tv")
            setDefaultValue("movie")
            summary = "%s"
        }

        val subLangPref = ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = "Preferred Subtitle Language"
            entries = SUB_LANGS.map { it.second }.toTypedArray()
            entryValues = SUB_LANGS.map { it.first }.toTypedArray()
            setDefaultValue("en")
            summary = "%s"
        }

        val subLimitPref = EditTextPreference(screen.context).apply {
            key = PREF_SUB_LIMIT_KEY
            title = "Subtitle Search Limit"
            summary = "Limit the number of subtitles fetched. Default: $PREF_SUB_LIMIT_DEFAULT"
            setDefaultValue(PREF_SUB_LIMIT_DEFAULT)
            dialogTitle = "Set subtitle limit"

            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
            }

            setOnPreferenceChangeListener { _, newValue ->
                val newAmount = (newValue as String).toIntOrNull()
                (newAmount != null && newAmount >= 0)
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(latestPref)
        screen.addPreference(subLangPref)
        screen.addPreference(subLimitPref)
    }

    companion object {

        private val SECURE_RANDOM by lazy { SecureRandom() }

        private val GET_SUBTITLES_REGEX by lazy { "/(movie|tv)/(\\d+)(?:/season/(\\d+)/episode/(\\d+))?".toRegex() }

        private const val PREF_QUALITY_KEY = "pref_quality"

        private const val PREF_LATEST_KEY = "pref_latest"

        private const val PREF_SUB_LIMIT_KEY = "pref_sub_limit"
        private const val PREF_SUB_LIMIT_DEFAULT = "25"

        private const val PREF_SUB_KEY = "pref_sub"

        private val SUB_LANGS = arrayOf(
            Pair("ar", "Arabic"),
            Pair("bn", "Bengali"),
            Pair("zh", "Chinese"),
            Pair("en", "English"),
            Pair("fr", "French"),
            Pair("de", "German"),
            Pair("hi", "Hindi"),
            Pair("id", "Indonesian"),
            Pair("it", "Italian"),
            Pair("ja", "Japanese"),
            Pair("ko", "Korean"),
            Pair("fa", "Persian"),
            Pair("pt", "Portuguese"),
            Pair("ru", "Russian"),
            Pair("es", "Spanish"),
            Pair("tr", "Turkish"),
            Pair("ur", "Urdu"),
            Pair("vi", "Vietnamese"),
        )

        fun statusParser(status: String?): Int {
            return when (status) {
                "Released", "Ended" -> SAnime.COMPLETED
                "Returning Series", "In Production" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }

        fun parseDate(dateStr: String?): Long {
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(dateStr ?: "")?.time ?: 0L
            }.getOrDefault(0L)
        }
    }

    // ============================= Utilities ==============================
    private fun parseMediaPage(response: Response): AnimesPage {
        val isMultiSearch = "/search/multi" in response.request.url.toString()
        val pageDto = response.parseAs<PageDto<MediaItemDto>>()
        val hasNextPage = pageDto.page < pageDto.totalPages

        val animeList = pageDto.results
            .filter { !isMultiSearch || (it.mediaType == "movie" || it.mediaType == "tv") }
            .map(::mediaItemToSAnime)

        return AnimesPage(animeList, hasNextPage)
    }

    private fun mediaItemToSAnime(media: MediaItemDto): SAnime {
        return SAnime.create().apply {
            title = media.realTitle
            val type = media.mediaType ?: if (media.title != null) "movie" else "tv"
            url = "/$type/${media.id}"
            thumbnail_url = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        }
    }

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString(this.body.string())
    }
}
