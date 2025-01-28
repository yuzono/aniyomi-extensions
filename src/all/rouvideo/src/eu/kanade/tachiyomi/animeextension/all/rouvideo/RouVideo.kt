package eu.kanade.tachiyomi.animeextension.all.rouvideo

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoDto.toAnimePage
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.ALL_VIDEOS
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.FEATURED
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.SORT_LATEST_KEY
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.SORT_LIKE_KEY
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.WATCHING
import eu.kanade.tachiyomi.animeextension.all.rouvideo.RouVideoFilter.categories
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
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Locale

class RouVideo(
    override val lang: String = "all",
) : AnimeHttpSource() {

    override val name = "肉視頻"

    override val baseUrl = "https://rou.video/home"
    private val videoUrl = "https://rou.video"

    private val apiUrl = "https://rou.video/api"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val intl = Intl(
        language = Locale.getDefault().language.takeIf { lang == "all" } ?: lang,
        baseLanguage = "en",
        availableLanguages = setOf("en", "zh", "vi"),
        classLoader = this::class.java.classLoader!!,
    )

    private val apiHeaders = headers.newBuilder().apply {
        add("Accept", "application/json, text/plain, */*")
        add("Host", apiUrl.toHttpUrl().host)
        add("Origin", videoUrl)
        add("Referer", "$videoUrl/")
    }.build()

    private val docHeaders = headers.newBuilder().apply {
        add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        add("Host", videoUrl.toHttpUrl().host)
    }.build()

    private val videoHeaders by lazy {
        headers.newBuilder().apply {
            add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            add("Host", apiUrl.toHttpUrl().host)
            add("Referer", "$videoUrl/")
        }.build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        fetchTagsList()
        fetchHotSearch()

        return GET(
            videoUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("v")
                addQueryParameter("order", SORT_LIKE_KEY)
                addQueryParameter("page", page.toString())
            }.build(),
            docHeaders,
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val data = document.selectFirst("script#__NEXT_DATA__")?.data()
            ?: return AnimesPage(emptyList(), false)

        return json.decodeFromString<RouVideoDto.VideoList>(data)
            .props.pageProps.toAnimePage()
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        fetchTagsList()
        fetchHotSearch()

        return GET(
            videoUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("v")
                addQueryParameter("order", SORT_LATEST_KEY)
                addQueryParameter("page", page.toString())
            }.build(),
            docHeaders,
        )
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return when {
            query.startsWith(PREFIX_ID) -> {
                val id = query.removePrefix(PREFIX_ID)
                handleSearchAnime(animeUrl(id), docHeaders) {
                    AnimesPage(listOf(parseAnimeDetails(this)), false)
                }
            }
            query.startsWith(PREFIX_TAG) -> {
                val tag = query.removePrefix(PREFIX_TAG)
                val url = videoUrl.toHttpUrl().newBuilder().apply {
                    addPathSegments("$CATEGORY_SLUG/$tag")
                    addQueryParameter("page", page.toString())
                }.build()
                handleSearchAnime(url.toString(), docHeaders, ::popularAnimeParse)
            }
            else -> {
                fetchTagsList()
                fetchHotSearch()

                val categoryFilter = filters.filterIsInstance<RouVideoFilter.CategoryFilter>().firstOrNull()
                val sortFilter = filters.filterIsInstance<RouVideoFilter.SortFilter>().firstOrNull()
                val tagFilter = filters.filterIsInstance<RouVideoFilter.TagFilter>().firstOrNull()
                val hotSearchFilter = filters.filterIsInstance<RouVideoFilter.HotSearchFilter>().firstOrNull()

                when {
                    query.isBlank() && categoryFilter?.toUriPart() == WATCHING ->
                        handleSearchAnime(watchingURL, apiHeaders) {
                            val jsonStr = body.string()
                            json.decodeFromString<List<RouVideoDto.Video>>(jsonStr)
                                .toAnimePage()
                        }

                    categoryFilter == null || categoryFilter.toUriPart() == FEATURED ->
                        handleSearchAnime(featuredURL, docHeaders) {
                            val document = asJsoup()
                            document.selectFirst("script#__NEXT_DATA__")?.data()
                                ?.let {
                                    json.decodeFromString<RouVideoDto.HotVideoList>(it)
                                        .props.pageProps.toAnimePage(sortFilter?.toUriPart())
                                }
                                ?: AnimesPage(emptyList(), false)
                        }

                    else -> {
                        val url = videoUrl.toHttpUrl().newBuilder().apply {
                            if (query.isBlank()) {
                                when {
                                    categoryFilter.toUriPart() != ALL_VIDEOS ->
                                        addPathSegments("$CATEGORY_SLUG/${categoryFilter.toUriPart()}")

                                    hotSearchFilter != null && !hotSearchFilter.isEmpty() -> {
                                        addPathSegment("search")
                                        addQueryParameter("q", hotSearchFilter.toUriPart())
                                        if (tagFilter != null && !tagFilter.isEmpty()) {
                                            addQueryParameter(CATEGORY_SLUG, tagFilter.toUriPart())
                                        }
                                    }

                                    tagFilter != null && !tagFilter.isEmpty() ->
                                        addPathSegments("$CATEGORY_SLUG/${tagFilter.toUriPart()}")

                                    else ->
                                        addPathSegment(VIDEO_SLUG)
                                }
                                sortFilter?.let { addQueryParameter("order", it.toUriPart()) }
                            } else {
                                addPathSegment("search")
                                addQueryParameter("q", query)
                                addQueryParameter(CATEGORY_SLUG, categoryFilter.toUriPart())
                            }
                            addQueryParameter("page", page.toString())
                        }.build()
                        handleSearchAnime(url.toString(), docHeaders, ::popularAnimeParse)
                    }
                }
            }
        }
    }

    private suspend fun handleSearchAnime(url: String, headers: Headers, parse: Response.() -> AnimesPage): AnimesPage {
        return client.newCall(GET(url, headers))
            .awaitSuccess()
            .use(parse)
    }

    private val featuredURL = baseUrl
    private val watchingURL by lazy { "$apiUrl/$VIDEO_SLUG/watching" }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            RouVideoFilter.SortFilter(intl),
            AnimeFilter.Header(intl["sort_filter_note"]),
            RouVideoFilter.CategoryFilter(intl),
            AnimeFilter.Separator(),
            RouVideoFilter.TagFilter(
                intl,
                if (!this::tagsArray.isInitialized && savedTags.isEmpty()) {
                    arrayOf(Tag(intl["reset_filter_to_load"], ""))
                } else {
                    setOf(Tag(intl["set_video_all_to_filter_tag"], ""))
                        .plus(if (this::tagsArray.isInitialized) tagsArray.toSet() else emptySet())
                        .plus(savedTags.minus(categories(intl)))
                        .toTypedArray()
                },
            ),
            RouVideoFilter.HotSearchFilter(
                intl,
                if (!this::hotSearch.isInitialized || hotSearch.isEmpty()) {
                    setOf(Pair(intl["reset_filter_to_load"], ""))
                } else {
                    setOf(Pair(intl["set_video_all_to_filter_tag"], ""))
                        .plus(hotSearch.map { it to it })
                },
            ),
        )
    }

    /**
     * Automatically fetched tags from the source to be used in the filters.
     */
    private lateinit var tagsArray: Tags

    /**
     * The request to the page that have the tags list.
     */
    private fun tagsListRequest() = GET("$videoUrl/cat", docHeaders)

    /**
     * Fetch the genres from the source to be used in the filters.
     */
    private fun fetchTagsList() {
        if (!this::tagsArray.isInitialized) {
            runCatching {
                client.newCall(tagsListRequest())
                    .execute()
                    .asJsoup()
                    .let(::tagsListParse)
                    .let { tags ->
                        if (tags.isNotEmpty()) {
                            tagsArray = tags
                        }
                    }
            }.onFailure { it.printStackTrace() }
        }
    }

    /**
     * Get the genres from the document.
     */
    private fun tagsListParse(document: Document): Tags {
        return document.selectFirst("script#__NEXT_DATA__")?.data()
            ?.let {
                json.decodeFromString<RouVideoDto.TagList>(it)
                    .props.pageProps.toTagList()
            }
            ?: emptyArray<Tag>()
    }

    private var savedTags: Set<Tag> = loadTagListFromPreferences()
        set(value) {
            preferences.edit().putStringSet(
                TAG_LIST_PREF,
                value.map { it.first }.toSet(),
            ).apply()
            field = value
        }

    private fun loadTagListFromPreferences(): Set<Tag> =
        preferences.getStringSet(TAG_LIST_PREF, emptySet())
            ?.mapNotNull { Tag(it, it) }
            ?.toSet()
            ?: emptySet()

    private lateinit var hotSearch: Set<String>

    private fun hotSearchRequest() = GET("$videoUrl/search", docHeaders)

    private fun fetchHotSearch() {
        runCatching {
            client.newCall(hotSearchRequest())
                .execute()
                .asJsoup()
                .let(::hotSearchParse)
                .let {
                    hotSearch = if (!this::hotSearch.isInitialized) {
                        it
                    } else {
                        hotSearch.plus(it)
                    }
                }
        }.onFailure { it.printStackTrace() }
    }

    private fun hotSearchParse(document: Document): Set<String> {
        return document.selectFirst("script#__NEXT_DATA__")?.data()
            ?.let {
                val hotSearches = json.decodeFromString<RouVideoDto.VideoList>(it)
                    .props.pageProps.hotSearches
                hotSearches?.toSet()
            }
            ?: emptySet()
    }

    // =========================== Anime Details ============================

    private fun animeUrl(id: String) = "$videoUrl/$VIDEO_SLUG/$id"
    override fun getAnimeUrl(anime: SAnime) = animeUrl(anime.url)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val resolution = anime.description?.let { resolutionRegex.find(it) }
            ?.groupValues?.get(1)
        return client.newCall(animeDetailsRequest(anime))
            .execute()
            .let { response ->
                parseAnimeDetails(response, resolution)
            }
    }

    override fun animeDetailsRequest(anime: SAnime) = GET(getAnimeUrl(anime), docHeaders)

    override fun animeDetailsParse(response: Response): SAnime = parseAnimeDetails(response)

    private fun parseAnimeDetails(response: Response, resolution: String? = null): SAnime {
        val document = response.asJsoup()
        val data = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return SAnime.create()
        val video = json.decodeFromString<RouVideoDto.VideoDetails>(data).props.pageProps.video

        savedTags = savedTags.plus(video.getTagList())

        return video.toSAnime()
            .apply {
                // Search & RelatedVideos doesn't have likeCount while AnimeDetails doesn't have resolution
                val resolutionSet = description?.matches(resolutionRegex) ?: false
                if (!resolutionSet && !resolution.isNullOrBlank()) {
                    description = "${resolutionDesc(resolution)}\n$description"
                }
            }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$videoUrl/$VIDEO_SLUG/${anime.url}", docHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val data = document.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val video = json.decodeFromString<RouVideoDto.VideoDetails>(data).props.pageProps.video

        return listOf(video.toEpisode())
    }

    override fun getEpisodeUrl(episode: SEpisode) = "$videoUrl/$VIDEO_SLUG/${episode.url}"

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode) = GET("$apiUrl/$VIDEO_SLUG/${episode.url}", apiHeaders)

    override fun videoListParse(response: Response): List<Video> {
        val jsonStr = response.body.string()
        val data = json.decodeFromString<RouVideoDto.VideoData>(jsonStr).video

        return playlistUtils.extractFromHls(
            playlistUrl = data.videoUrl,
            referer = "$videoUrl/",
        )
    }

    // Sorts by quality
    override fun List<Video>.sort(): List<Video> {
        return sortedByDescending { it.quality }
    }

    // ============================= Utilities ==============================

    private val resolutionRegex = Regex("""Resolution: (\d+)p""")
    companion object {
        internal fun resolutionDesc(resolution: String) = "Resolution: ${resolution}p"

        private const val VIDEO_SLUG = "v"
        private const val CATEGORY_SLUG = "t"

        private const val TAG_LIST_PREF = "TAG_LIST"

        const val PREFIX_ID = "$VIDEO_SLUG:"
        const val PREFIX_TAG = "$CATEGORY_SLUG:"
    }
}
