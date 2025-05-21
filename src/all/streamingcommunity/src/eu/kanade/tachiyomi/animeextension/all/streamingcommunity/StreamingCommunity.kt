package eu.kanade.tachiyomi.animeextension.all.streamingcommunity

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.AgeFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.FeaturedFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.GenresFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.QualityFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.ScoreFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.ServiceFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.SortFilter
import eu.kanade.tachiyomi.animeextension.all.streamingcommunity.Filters.YearFilter
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class StreamingCommunity(override val lang: String, private val showType: String) : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "StreamingUnity (${showType.replaceFirstChar { it.uppercaseChar() }})"

    private val homepage = "https://streamingunity.to"
    override val baseUrl = "$homepage/$lang"
    private val apiUrl = "$homepage/api"

    override val supportsLatest = true

    private val intl = Intl(
        language = Locale(lang).language,
        baseLanguage = "en",
        availableLanguages = setOf("en", "it"),
        classLoader = this::class.java.classLoader!!,
    )

    override val client: OkHttpClient = network.client

    private val apiHeaders = headers.newBuilder()
        .add("Host", baseUrl.toHttpUrl().host)
        .add("Referer", baseUrl)
        .build()

    private val jsonHeaders = headers.newBuilder()
        .add("Host", baseUrl.toHttpUrl().host)
        .add("Referer", baseUrl)
        .add("Content-Type", "application/json")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("X-Inertia", "true")
        .add("x-inertia-version", "") // This requires an up-to-date `version`
        .build()

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return when (page) {
            1 -> GET("$apiUrl/browse/top10?lang=$lang&type=$showType", apiHeaders)
            2 -> GET("$apiUrl/browse/trending?lang=$lang&type=$showType", apiHeaders)
            else ->
                GET("$apiUrl/archive?lang=$lang&offset=${(page - 3) * 60}&sort=views&type=$showType", apiHeaders)
        }
    }

    private var imageCdn = "https://cdn.${baseUrl.toHttpUrl().host}/images/"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val path = response.request.url.encodedPath
        val isApiCall = path.startsWith("/api/")
        val isTop10Trending = path.contains(TOP10_TRENDING_REGEX)

        val parsed: PropObject = if (isApiCall) {
            json.decodeFromString<PropObject>(response.body.string())
        } else {
            json.decodeFromString<ShowsResponse>(response.body.string()).props
                .also { props -> props.cdn_url?.takeIf { it.isNotBlank() }?.let { imageCdn = "$it/images/" } }
        }

        val animeList = parsed.titles.map { it.toSAnime(imageCdn) }

        val hasNextPage = isTop10Trending || animeList.size == 60

        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return when (page) {
            in 1..2 -> if (showType == "movie") {
                GET("$apiUrl/browse/latest?lang=$lang&offset=${(page - 1) * 60}&type=$showType", apiHeaders)
            } else {
                GET("$apiUrl/browse/new-episodes?lang=$lang&offset=${(page - 1) * 60}&type=$showType", apiHeaders)
            }
            else ->
                GET("$apiUrl/archive?lang=$lang&offset=${(page - 3) * 60}&sort=created_at&type=$showType", apiHeaders)
        }
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val featuredFilter = filters.filterIsInstance<FeaturedFilter>().firstOrNull()
        if (query.isBlank() && featuredFilter?.isDefault() == false) {
            val httpUrl = apiUrl.toHttpUrl().newBuilder().apply {
                addPathSegments("browse/genre")
                addQueryParameter(featuredFilter.uri, featuredFilter.toUriPart())
            }.build()
            return client.newCall(GET(httpUrl, apiHeaders))
                .awaitSuccess()
                .use(::searchAnimeParse)
                .let {
                    // Limited to only 120 results (2 pages)
                    if (page == 2) {
                        it.copy(hasNextPage = false)
                    } else {
                        it
                    }
                }
        } else {
            val request = searchAnimeRequest(page, query, filters)
            return client.newCall(request)
                .awaitSuccess()
                .use(::searchAnimeParse)
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genresFilter = filters.filterIsInstance<GenresFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val yearFilter = filters.filterIsInstance<YearFilter>().firstOrNull()
        val scoreFilter = filters.filterIsInstance<ScoreFilter>().firstOrNull()
        val serviceFilter = filters.filterIsInstance<ServiceFilter>().firstOrNull()
        val qualityFilter = filters.filterIsInstance<QualityFilter>().firstOrNull()
        val ageFilter = filters.filterIsInstance<AgeFilter>().firstOrNull()

        val httpUrlBuilder = apiUrl.toHttpUrl().newBuilder()
        httpUrlBuilder.apply {
            addPathSegments("archive")
            addQueryParameter("search", query)
            if (sortFilter?.isDefault() == false) {
                addQueryParameter(sortFilter.uri, sortFilter.toUriPart())
            }
            if (yearFilter?.isDefault() == false) {
                addQueryParameter(yearFilter.uri, yearFilter.toUriPart())
            }
            if (scoreFilter?.isDefault() == false) {
                addQueryParameter(scoreFilter.uri, scoreFilter.toUriPart())
            }
            if (serviceFilter?.isDefault() == false) {
                addQueryParameter(serviceFilter.uri, serviceFilter.toUriPart())
            }
            if (qualityFilter?.isDefault() == false) {
                addQueryParameter(qualityFilter.uri, qualityFilter.toUriPart())
            }
            if (ageFilter?.isDefault() == false) {
                addQueryParameter(ageFilter.uri, ageFilter.toUriPart())
            }
        }

        genresFilter?.addToUri(httpUrlBuilder)

        httpUrlBuilder.apply {
            addQueryParameter("lang", lang)
            addQueryParameter("type", showType)
            addQueryParameter("offset", ((page - 1) * 60).toString())
        }

        return GET(httpUrlBuilder.build(), apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val path = response.request.url.encodedPath
        val isApiCall = path.startsWith("/api/")

        val parsed = if (isApiCall) {
            json.decodeFromString<PropObject>(response.getData()).titles
        } else {
            json.decodeFromString<ShowsResponse>(response.getData()).props
                .also { props -> props.cdn_url?.takeIf { it.isNotBlank() }?.let { imageCdn = "$it/images/" } }
                .titles
        }

        val animeList = parsed.map {
            it.toSAnime(imageCdn)
        }

        val hasNextPage = animeList.size == 60

        return AnimesPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/titles/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val title = json.decodeFromString<SingleShowResponse>(
            response.getData(),
        ).props.title
            ?: error("Anime details parsing error: title is null.")

        return title.toSAnimeUpdate(intl)
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val sliders = json.decodeFromString<SingleShowResponse>(
            response.getData(),
        ).props.sliders

        return sliders?.flatMap { slider -> slider.titles.map { it.toSAnime(imageCdn) } } ?: emptyList()
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val parsed = json.decodeFromString<SingleShowResponse>(
            response.getData(),
        )
        val data = parsed.props
        val episodeList = mutableListOf<SEpisode>()

        if (data.title == null) return emptyList()

        data.title.preview?.let {
            episodeList.add(
                SEpisode.create().apply {
                    name = "Preview"
                    episode_number = 0F
                    url = it.embed_url
                },
            )
        }

        if (data.loadedSeason == null) {
            episodeList.add(
                SEpisode.create().apply {
                    name = "Film"
                    episode_number = 1F
                    url = data.title.id.toString()
                    date_upload = with(data.title) {
                        (release_date ?: last_air_date)?.let(::parseDate)
                            ?: (created_at ?: updated_at)?.let(::parseDateTime)
                            ?: 0L
                    }
                },
            )
        } else {
            val inertiaHeaders = headers.newBuilder()
                .add("Host", baseUrl.toHttpUrl().host)
                .add("Referer", "${response.request.url}/")
                .add("Content-Type", "application/json")
                .add("X-Requested-With", "XMLHttpRequest")
                .add("X-Inertia", "true")
                .add("X-Inertia-Version", parsed.version ?: "")
                .add("X-Inertia-Partial-Component", "Titles/Title")
                .add("X-Inertia-Partial-Data", "loadedSeason,flash")
                .build()

            val seasonIntl = intl["season"]
            val episodeIntl = intl["episode"]

            // Concurrently fetch episode data for all seasons
            val allSeasonEpisodes = runBlocking { // Bridge to coroutine world
                coroutineScope { // Create a scope for structured concurrency
                    data.title.seasons.map { season ->
                        async { // Launch each season fetch asynchronously
                            val episodes = if (season.id == data.loadedSeason.id) {
                                data.loadedSeason.episodes
                            } else {
                                val seasonResponse = client.newCall(
                                    GET("${response.request.url}/season-${season.number}", inertiaHeaders),
                                ).awaitSuccess() // Suspend call for network request
                                json.decodeFromString<SingleShowResponse>(seasonResponse.body.string()).props.loadedSeason?.episodes
                                    ?: emptyList()
                            }
                            Pair(season, episodes) // Return season object and its episodes
                        }
                    }.awaitAll() // Wait for all async operations to complete
                }
            }

            // Process the fetched data
            allSeasonEpisodes.forEach { (season, episodeData) ->
                episodeData.forEach { episode ->
                    episodeList.add(
                        SEpisode.create().apply {
                            name = "$seasonIntl ${season.number} $episodeIntl ${episode.number} - ${episode.name}"
                            episode_number = episode.number.toFloat()
                            url = "${data.title.id}?episode_id=${episode.id}&next_episode=1"
                            date_upload = season.release_date?.let(::parseDate)
                                ?: (episode.created_at ?: episode.updated_at)?.let(::parseDateTime)
                                ?: 0L
                        },
                    )
                }
            }
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = episode.url
        return when {
            url.startsWith("https://") -> vixCloudExtractor(url)
            else -> {
                val iframeUrl = client.newCall(
                    GET("$baseUrl/iframe/$url", headers),
                ).awaitSuccess().use {
                    it.asJsoup()
                        .selectFirst("iframe[src]")?.attr("abs:src")
                        ?: error("Failed to extract iframe")
                }
                vixCloudExtractor(iframeUrl)
            }
        }
    }

    // https://vixcloud.co/embed/262817?token=321fd9c4f94fcd28b522d1f3ba2a8d77&expires=1752778149&canPlayFHD=1&canBypassAds=1
    private suspend fun vixCloudExtractor(iframeUrl: String): List<Video> {
        val iframeHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", iframeUrl.toHttpUrl().host)
            .add("Referer", "$baseUrl/")
            .build()

        val iframe = client.newCall(GET(iframeUrl, iframeHeaders)).awaitSuccess().asJsoup()
        val script = iframe.selectFirst("script:containsData(masterPlaylist)")?.data()
            ?: error("Failed to extract masterPlaylist script")
        val playlistUrl = PLAYLIST_URL_REGEX.find(script)?.groupValues?.get(1)
            ?: error("Failed to extract playlist URL")
        val token = TOKEN_REGEX.find(script)?.groupValues?.get(1)
            ?: error("Failed to extract token")
        val expires = EXPIRES_REGEX.find(script)?.groupValues?.get(1)
            ?: error("Failed to extract expires")

        val masterPlUrl = buildString {
            append(playlistUrl)
            append(if (playlistUrl.contains('?')) '&' else '?')
            append("h=1&token=")
            append(token)
            append("&expires=")
            append(expires)
            append("&lang=")
            append(lang)
        }

        return playlistUtils.extractFromHls(playlistUrl = masterPlUrl)
    }

    override fun videoListRequest(episode: SEpisode): Request = throw Exception("Not used")

    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    // ============================= Utilities ==============================

    private fun Response.getData(): String {
        return if (headers["content-type"]?.contains("application/json") == true) {
            body.string()
        } else {
            asJsoup().selectFirst("div#app[data-page]")
                ?.attr("data-page")
                ?.replace("&quot;", "\"")
                ?: error("Failed to extract data-page")
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains("${quality}p") },
                { QUALITY_REGEX.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private val TOP10_TRENDING_REGEX = Regex("""/browse/(top10|trending)""")
        private val PLAYLIST_URL_REGEX = Regex("""url: ?'(.*?)'""")
        private val EXPIRES_REGEX = Regex("""'expires': ?'(\d+)'""")
        private val TOKEN_REGEX = Regex("""'token': ?'([\w-]+)'""")
        private val QUALITY_REGEX = Regex("""(\d+)p""")
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private val DATE_TIME_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        }

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
        }

        internal fun parseStatus(statusString: String?): Int {
            return when (statusString) {
                "Ended" -> SAnime.COMPLETED
                "Released" -> SAnime.COMPLETED
                "Returning Series" -> SAnime.ONGOING
                "Canceled" -> SAnime.CANCELLED
                else -> SAnime.UNKNOWN
            }
        }

        private fun parseDateTime(dateStr: String): Long {
            return runCatching { DATE_TIME_FORMATTER.parse(dateStr)?.time }
                .getOrNull() ?: 0L
        }

        private fun parseDate(dateStr: String): Long {
            return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
                .getOrNull() ?: 0L
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        GenresFilter(intl),
        SortFilter(intl),
        ScoreFilter(intl),
        YearFilter(intl),
        ServiceFilter(intl),
        QualityFilter(intl),
        AgeFilter(intl),
        AnimeFilter.Separator(),
        FeaturedFilter(intl),
    )
}
