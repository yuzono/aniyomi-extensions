package eu.kanade.tachiyomi.animeextension.en.xprime

import android.content.SharedPreferences
import android.text.InputType
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelMapNotNull
import extensions.utils.addEditTextPreference
import extensions.utils.addListPreference
import extensions.utils.addSwitchPreference
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy
import extensions.utils.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class XPrime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "XPrime"

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val baseUrl by lazy { domainPref }
    private val apiUrl = "https://api.themoviedb.org/3"
    private val backendUrl = "https://backend.xprime.tv"
    private val decryptionApiUrl = "https://enc-dec.app/api/dec-xprime"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("trending")
            addPathSegment("all")
            addPathSegment("week")
            addQueryParameter("api_key", TMDB_API_KEY)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseMediaPage(response)
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val types = if (latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")

        return types.parallelMapNotNull { mediaType ->
            runCatching {
                latestUpdatesParse(client.newCall(latestUpdatesRequest(page, mediaType)).awaitSuccess())
            }.getOrNull()
        }.let { animePages ->
            val animes = animePages.flatMap { it.animes }
            val hasNextPage = animePages.any { it.hasNextPage }
            AnimesPage(animes, hasNextPage)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    private fun latestUpdatesRequest(page: Int, mediaType: String): Request {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(mediaType)
            addQueryParameter("api_key", TMDB_API_KEY)
            addQueryParameter("language", "en-US")
            addQueryParameter("sort_by", "primary_release_date.desc")
            addQueryParameter("page", page.toString())
            addQueryParameter("primary_release_date.lte", date)
        }.build()
        return GET(url)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseMediaPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val types = if (latestPref == "movie") listOf("movie", "tv") else listOf("tv", "movie")

            return types.parallelMapNotNull { mediaType ->
                runCatching {
                    searchAnimeParse(client.newCall(searchAnimeRequest(page, query, mediaType, adultPref)).awaitSuccess())
                }.getOrNull()
            }.let { animePages ->
                val animes = animePages.flatMap { it.animes }
                val hasNextPage = animePages.any { it.hasNextPage }
                AnimesPage(animes, hasNextPage)
            }
        } else {
            return super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeRequest(page: Int, query: String, mediaType: String, includeAdult: Boolean): Request {
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addPathSegment(mediaType)
            addQueryParameter("api_key", TMDB_API_KEY)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            addQueryParameter("query", query)
            addQueryParameter("include_adult", includeAdult.toString())
        }.build()
        return GET(url)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val type = filters.filterIsInstance<XPrimeFilters.TypeFilter>().first().state.let {
            if (it == 0) "movie" else "tv"
        }
        val sortFilter = filters.filterIsInstance<XPrimeFilters.SortFilter>().first()
        val sortBy = sortFilter.state?.run {
            when (index) {
                0 -> "popularity"
                1 -> "vote_average"
                else -> if (type == "movie") "primary_release_date" else "first_air_date"
            } + if (ascending) ".asc" else ".desc"
        } ?: "popularity.desc"

        val genreMap = if (type == "movie") XPrimeFilters.MOVIE_GENRE_MAP else XPrimeFilters.TV_GENRE_MAP
        val genres = filters.filterIsInstance<XPrimeFilters.GenreFilter>().first()
            .state.filter { it.state }.mapNotNull { genreMap[it.name] }.joinToString(",")

        val providers = filters.filterIsInstance<XPrimeFilters.WatchProviderFilter>()
            .firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.joinToString("|") { it.id }
            .orEmpty()

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("discover")
            addPathSegment(type)
            addQueryParameter("api_key", TMDB_API_KEY)
            addQueryParameter("sort_by", sortBy)
            addQueryParameter("language", "en-US")
            addQueryParameter("page", page.toString())
            if (genres.isNotBlank()) addQueryParameter("with_genres", genres)
            if (providers.isNotBlank()) {
                addQueryParameter("with_watch_providers", providers)
                addQueryParameter("watch_region", "US") // Region is required by TMDB
            }
        }.build()
        return GET(url)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseMediaPage(response)
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = XPrimeFilters.getFilterList()

    // ============================== Details ===============================
    override fun getAnimeUrl(anime: SAnime): String {
        return baseUrl + anime.url
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (type, id) = anime.url.substring(1).split("/").let {
            val rawId = it[1]
            if (rawId.startsWith("t")) "tv" to rawId.drop(1) else "movie" to rawId
        }

        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(type)
            addPathSegment(id)
            addQueryParameter("api_key", TMDB_API_KEY)
            addQueryParameter("append_to_response", "external_ids")
        }.build()
        return GET(url)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        return if ("/movie/" in response.request.url.toString()) {
            movieDetailsParse(response)
        } else {
            tvDetailsParse(response)
        }
    }

    private fun movieDetailsParse(response: Response): SAnime {
        val movie = response.parseAs<MovieDetailDto>()
        return SAnime.create().apply {
            title = movie.title
            url = "/title/${movie.id}"
            thumbnail_url = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = movie.productionCompanies.joinToString { it.name }
            genre = movie.genres.joinToString { it.name }
            status = parseStatus(movie.status)
            description = buildString {
                movie.overview?.also { append(it + "\n\n") }
                val details = listOfNotNull(
                    "**Type:** Movie",
                    movie.voteAverage.takeIf { it > 0f }?.let { "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}" },
                    movie.tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
                    movie.releaseDate?.takeIf(String::isNotBlank)?.let { "**Release Date:** $it" },
                    movie.runtime?.takeIf { it > 0 }?.let {
                        val hours = it / 60
                        val minutes = it % 60
                        "**Runtime:** ${if (hours > 0) "${hours}h " else ""}${minutes}m"
                    },
                    movie.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    movie.externalIds?.imdbId?.let { "**IMDB:** $it" },
                )
                if (details.isNotEmpty()) {
                    append(details.joinToString("\n"))
                }
                movie.backdropPath?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("![Backdrop](https://image.tmdb.org/t/p/w1280$it)")
                }
            }
        }
    }

    private fun tvDetailsParse(response: Response): SAnime {
        val tv = response.parseAs<TvDetailDto>()
        return SAnime.create().apply {
            title = tv.name
            url = "/title/t${tv.id}"
            thumbnail_url = tv.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            author = tv.productionCompanies.joinToString { it.name }
            artist = tv.networks.joinToString { it.name }
            genre = tv.genres.joinToString { it.name }
            status = parseStatus(tv.status)
            description = buildString {
                tv.overview?.also { append(it + "\n\n") }
                val details = listOfNotNull(
                    "**Type:** TV Show",
                    tv.voteAverage.takeIf { it > 0f }?.let { "**Score:** ★ ${String.format(Locale.US, "%.1f", it)}" },
                    tv.tagline?.takeIf(String::isNotBlank)?.let { "**Tagline:** *$it*" },
                    tv.firstAirDate?.takeIf(String::isNotBlank)?.let { "**First Air Date:** $it" },
                    tv.lastAirDate?.takeIf(String::isNotBlank)?.let { "**Last Air Date:** $it" },
                    tv.homepage?.takeIf(String::isNotBlank)?.let { "**[Official Site]($it)**" },
                    tv.externalIds?.imdbId?.let { "**IMDB:** $it" },
                )
                if (details.isNotEmpty()) {
                    append(details.joinToString("\n"))
                }
                tv.backdropPath?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("![Backdrop](https://image.tmdb.org/t/p/w1280$it)")
                }
            }
        }
    }

    // ========================== Related Titles ============================
    override val supportsRelatedAnimes: Boolean = true

    override fun relatedAnimeListRequest(anime: SAnime): Request {
        val (type, id) = anime.url.substring(1).split("/").let {
            val rawId = it[1]
            if (rawId.startsWith("t")) "tv" to rawId.drop(1) else "movie" to rawId
        }
        val url = apiUrl.toHttpUrl().newBuilder().apply {
            addPathSegment(type)
            addPathSegment(id)
            addPathSegment("recommendations")
            addQueryParameter("api_key", TMDB_API_KEY)
        }.build()
        return GET(url)
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        return response.parseAs<PageDto<MediaItemDto>>().results.map(::mediaItemToSAnime)
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        return animeDetailsRequest(anime)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return if ("/tv/" in response.request.url.toString()) {
            val tv = response.parseAs<TvDetailDto>()
            val extraData = Triple(tv.name, tv.firstAirDate?.take(4) ?: "", tv.externalIds?.imdbId ?: "")
            val extraDataEncoded = json.encodeToString(extraData)
            tv.seasons
                .filter { it.seasonNumber > 0 }
                .sortedByDescending { it.seasonNumber } // Sort seasons descending
                .flatMap { season ->
                    val seasonDetail = client.newCall(
                        GET("$apiUrl/tv/${tv.id}/season/${season.seasonNumber}?api_key=$TMDB_API_KEY"),
                    ).execute().parseAs<TvSeasonDetailDto>()
                    seasonDetail.episodes
                        .sortedByDescending { it.episodeNumber } // Sort episodes descending
                        .map { episode ->
                            SEpisode.create().apply {
                                name = "S${season.seasonNumber} E${episode.episodeNumber} - ${episode.name}"
                                episode_number = episode.episodeNumber.toFloat()
                                scanlator = "Season ${season.seasonNumber}"
                                date_upload = parseDate(episode.airDate)
                                url = "tv/${tv.id}/${season.seasonNumber}/${episode.episodeNumber}#$extraDataEncoded"
                            }
                        }
                }
        } else {
            val movie = response.parseAs<MovieDetailDto>()
            val extraData = Triple(movie.title, movie.releaseDate?.take(4) ?: "", movie.externalIds?.imdbId ?: "")
            val extraDataEncoded = json.encodeToString(extraData)
            listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1.0f
                    date_upload = parseDate(movie.releaseDate)
                    url = "movie/${movie.id}#$extraDataEncoded"
                },
            )
        }
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (path, extraDataEncoded) = episode.url.split("#")
        val (title, year, imdbId) = json.decodeFromString<Triple<String, String, String>>(extraDataEncoded)

        val pathParts = path.split("/")
        val isMovie = pathParts.first() == "movie"

        val servers = client.newCall(GET("$backendUrl/servers")).awaitSuccess()
            .parseAs<ServerListDto>().servers

        val videoList = servers.parallelCatchingFlatMap { server ->
            val serverUrl = backendUrl.toHttpUrl().newBuilder().apply {
                addPathSegment(server.name)
                addQueryParameter("name", title)
                addQueryParameter("year", year)
                addQueryParameter("id", pathParts[1])
                if (imdbId.isNotBlank()) addQueryParameter("imdb", imdbId)
                if (!isMovie) {
                    addQueryParameter("season", pathParts[2])
                    addQueryParameter("episode", pathParts[3])
                }
            }.build()

            val backendHeaders = headers.newBuilder().add("Referer", baseUrl).build()
            val encryptedText = client.newCall(GET(serverUrl.toString(), backendHeaders)).awaitSuccess().body.string()

            val decryptionPayload = json.encodeToString(mapOf("text" to encryptedText))
            val requestBody = decryptionPayload.toRequestBody("application/json".toMediaType())
            val decrypted = client.newCall(POST(decryptionApiUrl, body = requestBody)).awaitSuccess()
                .parseAs<XprimeDecryptionDto>().result

            val subLimit = subLimitPref.toIntOrNull() ?: PREF_SUB_LIMIT_DEFAULT.toInt()
            val subtitles = decrypted.subtitles.take(subLimit).map {
                Track(it.url, it.language)
            }

            val videoHeaders = headers.newBuilder()
                .add("Origin", baseUrl.removeSuffix("/")) // Required for loading streams
                .build()

            when {
                // Multi-quality streams (e.g., primebox)
                decrypted.streams != null -> {
                    decrypted.streams.map { (quality, url) ->
                        Video(url = "", quality = "Server: ${server.name} - $quality", videoUrl = url, headers = videoHeaders, subtitleTracks = subtitles)
                    }
                }
                // Single HLS playlist (e.g., fox, primesrc)
                decrypted.url != null -> {
                    playlistUtils.extractFromHls(
                        playlistUrl = decrypted.url,
                        videoNameGen = { quality -> "Server: ${server.name} - $quality" },
                        subtitleList = subtitles,
                        masterHeaders = videoHeaders,
                        videoHeaders = videoHeaders,
                    )
                }
                else -> emptyList()
            }
        }

        return videoList.sortedByDescending { it.quality.contains(qualityPref, true) }
    }

    // ============================== Settings ==============================
    private val domainPref by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)
    private val adultPref by preferences.delegate(PREF_ADULT_KEY, PREF_ADULT_DEFAULT)
    private val qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val latestPref by preferences.delegate(PREF_LATEST_KEY, PREF_LATEST_DEFAULT)
    private val subLimitPref by preferences.delegate(PREF_SUB_LIMIT_KEY, PREF_SUB_LIMIT_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred Domain (requires restart)",
            entries = DOMAIN_ENTRIES.toList(),
            entryValues = DOMAIN_VALUES.toList(),
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
            restartRequired = true,
        )

        screen.addSwitchPreference(
            key = PREF_ADULT_KEY,
            title = "Show Adult Content (18+)",
            summary = "Show adult content in search and browse results",
            default = PREF_ADULT_DEFAULT,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_LATEST_KEY,
            title = "Preferred 'Latest' Page",
            entries = listOf("Movies", "TV Shows"),
            entryValues = listOf("movie", "tv"),
            default = PREF_LATEST_DEFAULT,
            summary = "%s",
        )

        screen.addEditTextPreference(
            key = PREF_SUB_LIMIT_KEY,
            title = "Subtitle Search Limit",
            summary = "Limit the number of subtitles fetched. Current: $subLimitPref",
            default = PREF_SUB_LIMIT_DEFAULT,
            inputType = InputType.TYPE_CLASS_NUMBER,
        ) {
            it.toIntOrNull()?.let { value ->
                preferences.edit().putString(PREF_SUB_LIMIT_KEY, value.toString()).apply()
            }
            true
        }
    }

    // ============================= Utilities ==============================
    private fun parseMediaPage(response: Response): AnimesPage {
        val pageDto = response.parseAs<PageDto<MediaItemDto>>()
        val hasNextPage = pageDto.page < pageDto.totalPages
        val animeList = pageDto.results.map(::mediaItemToSAnime)
        return AnimesPage(animeList, hasNextPage)
    }

    private fun mediaItemToSAnime(media: MediaItemDto): SAnime {
        return SAnime.create().apply {
            title = media.realTitle
            val type = media.mediaType ?: if (media.title != null) "movie" else "tv"

            // URL format: /title/{id} for movie, /title/t{id} for TV
            url = if (type == "tv") "/title/t${media.id}" else "/title/${media.id}"
            thumbnail_url = media.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
        }
    }

    private fun parseStatus(status: String?): Int {
        return when (status) {
            "Released", "Ended" -> SAnime.COMPLETED
            "Returning Series", "In Production" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun parseDate(dateStr: String?): Long {
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr ?: "")?.time ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private val TMDB_API_KEY = BuildConfig.TMDB_API

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://xprime.tv"
        private val DOMAIN_ENTRIES = arrayOf("xprime.tv", "xprime.today")
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }.toTypedArray()

        private const val PREF_ADULT_KEY = "pref_adult"
        private const val PREF_ADULT_DEFAULT = false

        private const val PREF_LATEST_KEY = "pref_latest"
        private const val PREF_LATEST_DEFAULT = "movie"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SUB_LIMIT_KEY = "pref_sub_limit"
        private const val PREF_SUB_LIMIT_DEFAULT = "25"
    }
}
