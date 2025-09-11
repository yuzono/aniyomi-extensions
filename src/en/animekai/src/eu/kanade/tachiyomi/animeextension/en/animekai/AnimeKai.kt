package eu.kanade.tachiyomi.animeextension.en.animekai

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.en.animekai.AnimeKaiFilters.CountriesFilter
import eu.kanade.tachiyomi.animeextension.en.animekai.AnimeKaiFilters.GenresFilter
import eu.kanade.tachiyomi.animeextension.en.animekai.AnimeKaiFilters.LanguagesFilter
import eu.kanade.tachiyomi.animeextension.en.animekai.AnimeKaiFilters.RatingFilter
import eu.kanade.tachiyomi.animeextension.en.animekai.AnimeKaiFilters.SeasonsFilter
import eu.kanade.tachiyomi.animeextension.en.animekai.AnimeKaiFilters.SortByFilter
import eu.kanade.tachiyomi.animeextension.en.animekai.AnimeKaiFilters.StatusFilter
import eu.kanade.tachiyomi.animeextension.en.animekai.AnimeKaiFilters.TypesFilter
import eu.kanade.tachiyomi.animeextension.en.animekai.AnimeKaiFilters.YearsFilter
import eu.kanade.tachiyomi.animeextension.en.animekai.AnimeKaiFilters.getFirstOrNull
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMapNotNull
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.LazyMutable
import extensions.utils.addEditTextPreference
import extensions.utils.addListPreference
import extensions.utils.addSetPreference
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.hours

class AnimeKai : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeKai"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "$baseUrl/")
    }

    private var docHeaders by LazyMutable {
        headersBuilder().build()
    }

    override var client by LazyMutable {
        network.client.newBuilder()
            .rateLimitHost(baseUrl.toHttpUrl(), RATE_LIMIT)
            .build()
    }

    private val cacheControl by lazy { CacheControl.Builder().maxAge(1.hours).build() }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/trending?page=$page", docHeaders, cacheControl)
    }

    override fun popularAnimeSelector() = "div.aitem-wrapper div.aitem"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            element.selectFirst("a.poster")?.attr("href")?.let {
                setUrlWithoutDomain(it)
            }
            title = element.selectFirst("a.title")?.getTitle() ?: ""
            thumbnail_url = element.select("a.poster img").attr("data-src")
        }
    }

    override fun popularAnimeNextPageSelector() = "nav > ul.pagination > li.active ~ li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/updates?page=$page", docHeaders, cacheControl)
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("browser")
            addQueryParameter("keyword", query)
            addQueryParameter("page", page.toString())

            if (filters.isNotEmpty()) {
                filters.getFirstOrNull<TypesFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<GenresFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<StatusFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<SortByFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<SeasonsFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<YearsFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<RatingFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<CountriesFilter>()?.addQueryParameters(this)
                filters.getFirstOrNull<LanguagesFilter>()?.addQueryParameters(this)
            }
        }.build().toString()

        return GET(url, docHeaders, cacheControl)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun relatedAnimeListSelector() = "div.aitem-col a.aitem"

    override fun relatedAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.selectFirst("div.title")?.getTitle() ?: ""
            thumbnail_url = element.getBackgroundImage()
        }
    }

    override fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        val seasons = document.select("#seasons div.season div.aitem div.inner").mapNotNull { season ->
            SAnime.create().apply {
                val url = season.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                setUrlWithoutDomain(url)
                thumbnail_url = season.selectFirst("img")?.attr("src")
                title = season.select("div.detail span").text()
            }
        }

        val related = document.select(relatedAnimeListSelector()).map { relatedAnimeFromElement(it) }
        return seasons + related
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeKaiFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            thumbnail_url = document.select(".poster img").attr("src")

            document.selectFirst("div#main-entity")?.let { info ->
                val titles = info.selectFirst("h1.title")
                    ?.also { title = it.getTitle() }
                    ?.let {
                        listOf(
                            it.attr("title"),
                            it.attr("data-jp"),
                            it.ownText(),
                        )
                    } ?: emptyList()

                val altTitles = (
                    info.selectFirst(".al-title")?.text()?.split(";").orEmpty() +
                        titles
                    )
                    .asSequence()
                    .map { it.trim() }.filterNot { it.isBlank() }.distinctBy { it.lowercase() }
                    .filterNot { it.lowercase() == title.lowercase() }.joinToString("; ")
                val rating = info.selectFirst(".rating")?.text().orEmpty()

                info.selectFirst("div.detail")?.let { detail ->
                    author = detail.getInfo("Studios:", isList = true)?.takeIf { it.isNotEmpty() }
                        ?: detail.getInfo("Producers:", isList = true)?.takeIf { it.isNotEmpty() }
                    status = detail.getInfo("Status:")?.run(::parseStatus) ?: SAnime.UNKNOWN
                    genre = detail.getInfo("Genres:", isList = true)

                    description = buildString {
                        info.selectFirst(".desc")?.text()?.let { append(it + "\n") }
                        detail.getInfo("Country:", full = true)?.run(::append)
                        detail.getInfo("Premiered:", full = true)?.run(::append)
                        detail.getInfo("Date aired:", full = true)?.run(::append)
                        detail.getInfo("Broadcast:", full = true)?.run(::append)
                        detail.getInfo("Duration:", full = true)?.run(::append)
                        if (rating.isNotBlank()) append("\n**Rating:** $rating")
                        detail.getInfo("MAL:", full = true)?.run(::append)
                        if (altTitles.isNotBlank()) { append("\n**Alternative Title:** $altTitles") }
                        detail.select("div div div:contains(Links:) a").forEach {
                            append("\n[${it.text()}](${it.attr("href")})")
                        }
                        document.getCover()?.let { append("\n\n![Cover]($it)") }
                    }
                }
            } ?: throw IllegalStateException("Invalid anime details page format")
        }
    }

    private fun Element.getInfo(
        tag: String,
        isList: Boolean = false,
        full: Boolean = false,
    ): String? {
        if (isList) {
            return select("div div div:contains($tag) a").eachText().joinToString()
        }
        val value = selectFirst("div div div:contains($tag)")
            ?.text()?.removePrefix(tag)?.trim()
        return if (full && value != null) "\n**$tag** $value" else value
    }

    private val coverUrlRegex by lazy { """background-image:\s*url\(["']?([^"')]+)["']?\)""".toRegex() }
    private val coverSelector by lazy { "div.watch-section-bg" }

    private fun Document.getCover(): String? {
        return selectFirst(coverSelector)?.getBackgroundImage()
    }

    private fun Element.getBackgroundImage(): String? {
        val style = attr("style")
        return coverUrlRegex.find(style)?.groupValues?.getOrNull(1)
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "div.eplist a"

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = client.newCall(animeDetailsRequest(anime))
            .awaitSuccess().use {
                val document = it.asJsoup()
                document.selectFirst("div[data-id]")?.attr("data-id")
                    ?: throw IllegalStateException("Anime ID not found")
            }

        val decoded = client.newCall(GET("${BuildConfig.KAISVA}/?f=e&d=$animeId"))
            .awaitSuccess().use { it.body.string() }

        val chapterListRequest = GET("$baseUrl/ajax/episodes/list?ani_id=$animeId&_=$decoded", docHeaders)
        val document = client.newCall(chapterListRequest)
            .awaitSuccess().use { it.parseAs<ResultResponse>().toDocument() }

        val episodeElements = document.select(episodeListSelector())
        return episodeElements.mapNotNull {
            runCatching {
                episodeFromElement(it)
            }.getOrNull()
        }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val token = element.attr("token").takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Token not found")
        val epNum = element.attr("num")
        val subdubType = element.attr("langs").toIntOrNull() ?: 0
        val subdub = when (subdubType) {
            1 -> "Sub"
            3 -> "Dub & Sub"
            else -> ""
        }

        val namePrefix = "Episode $epNum"
        val name = element.selectFirst("span")?.text()
            ?.takeIf { it.isNotBlank() && it != namePrefix }
            ?.let { ": $it" }
            .orEmpty()

        return SEpisode.create().apply {
            this.name = namePrefix + name
            this.url = token
            episode_number = epNum.toFloat()
            scanlator = subdub
        }
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val token = episode.url
        val decodedToken = client.newCall(GET("${BuildConfig.KAISVA}/?f=e&d=$token"))
            .awaitSuccess().use { it.body.string() }

        val typeSelection = preferences.typeToggle
        val hosterSelection = preferences.hostToggle

        val servers = client.newCall(GET("$baseUrl/ajax/links/list?token=$token&_=$decodedToken", docHeaders))
            .awaitSuccess().use { response ->
                val document = response.parseAs<ResultResponse>().toDocument()
                document.select("div.server-items[data-id]")
                    .flatMap { typeElm ->
                        val type = typeElm.attr("data-id") // sub, softsub, dub
                        if (type !in typeSelection) return@flatMap emptyList()

                        typeElm.select("span.server[data-lid]")
                            .mapNotNull { serverElm ->
                                val serverId = serverElm.attr("data-lid")
                                val serverName = serverElm.text()
                                if (serverName !in hosterSelection) return@mapNotNull null

                                VideoCode(type, serverId, serverName)
                            }
                    }
            }

        return servers.parallelMapNotNull { server ->
            try {
                extractIframe(server)
            } catch (e: Exception) {
                Log.e("AnimeKai", "Failed to extract iframe from server: $server", e)
                null
            }
        }.flatMap { server ->
            try {
                extractVideo(server)
            } catch (e: Exception) {
                Log.e("AnimeKai", "Failed to extract video from server: $server", e)
                emptyList()
            }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private var universalExtractor by LazyMutable { UniversalExtractor(client, preferences.prefTimeout.toLong()) }

    private suspend fun extractIframe(server: VideoCode): VideoData {
        val (type, lid, serverName) = server

        val decodedLid = client.newCall(GET("${BuildConfig.KAISVA}/?f=e&d=$lid"))
            .awaitSuccess().use { it.body.string() }

        val encodedLink = client.newCall(GET("$baseUrl/ajax/links/view?id=$lid&_=$decodedLid", docHeaders))
            .awaitSuccess().use { json ->
                json.parseAs<ResultResponse>().result
            }

        val iframe = client.newCall(GET("${BuildConfig.KAISVA}/?f=d&d=$encodedLink"))
            .awaitSuccess().use { json ->
                val url = json.parseAs<IframeResponse>().url
                url.toHttpUrl().newBuilder()
                    .addQueryParameter("autostart", "true")
                    .build().toString()
            }

        val typeSuffix = when (type) {
            "sub" -> "Hard Sub"
            "softsub" -> "Soft Sub"
            "dub" -> "Dub & S-Sub"
            else -> type
        }
        val name = "$serverName | [$typeSuffix]"

        return VideoData(type, iframe, name)
    }

    private fun extractVideo(server: VideoData): List<Video> {
        val (type, iframe, serverName) = server

        return try {
            /*
             * Server 2:
             *  - Playlist like: `list.m3u8` with .ts file;
             *  - The Dub will load separated sub .vtt file;
             *  - The S-Sub seems using embedded subs;
             * Server 1:
             *  - Playlist like: `list,Z3r-aM6peKE-ic4lJkPfnljqs9Q0UQ.m3u8`, all the segments are
             *    using random file extension but can be replaced to .ts;
             *  - Dub & S-Sub are similar to Server 2;
             */
            if (type == "sub") {
                universalExtractor.videosFromUrl(iframe, docHeaders, serverName, withSub = false)
            } else {
                universalExtractor.videosFromUrl(iframe, docHeaders, serverName)
            }
        } catch (e: Exception) {
            Log.e("AnimeKai", "Failed to extract video from iframe: $iframe", e)
            emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.prefQuality
        val server = preferences.prefServer
        val type = preferences.prefType
        val qualitiesList = PREF_QUALITY_ENTRIES.reversed()

        return sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { video -> qualitiesList.indexOfLast { video.quality.contains(it) } }
                .thenByDescending { it.quality.contains(server, true) }
                .thenByDescending { it.quality.contains(type, true) },
        )
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Completed", "Finished Airing" -> SAnime.COMPLETED
            "Releasing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getTitle(): String {
        val enTitle = attr("title")
        val jpTitle = attr("data-jp")
        return if (useEnglish) {
            enTitle.ifBlank { text() }
        } else {
            jpTitle.ifBlank { text() }
        }
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf(
            "animekai.to",
            "animekai.bz",
            "animekai.cc",
            "animekai.ac",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES.first()

        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "English"
        private val PREF_TITLE_LANG_LIST = listOf("Romaji", "English")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_DEFAULT = PREF_QUALITY_ENTRIES.first()

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val HOSTERS = listOf(
            "Server 1",
            "Server 2",
        )

        private const val PREF_SERVER_KEY = "preferred_server"
        private val PREF_SERVER_DEFAULT = HOSTERS.first()

        private const val PREF_TYPE_TOGGLE_KEY = "type_selection"
        private val TYPES_ENTRIES = listOf("[Hard Sub]", "[Soft Sub]", "[Dub & S-Sub]")
        private val TYPES_VALUES = listOf("sub", "softsub", "dub")
        private val DEFAULT_TYPES = setOf("sub")

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "[Soft Sub]"

        private const val PREF_TIMEOUT_KEY = "parsing_timeout"
        private const val PREF_TIMEOUT_DEFAULT = "10"

        private const val RATE_LIMIT = 5
    }

    // ============================== Settings ==============================

    private var useEnglish by LazyMutable { preferences.getTitleLang == "English" }

    private val SharedPreferences.getTitleLang
        by preferences.delegate(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT)

    private val SharedPreferences.prefQuality
        by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    private val SharedPreferences.prefServer
        by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    private val SharedPreferences.prefType
        by preferences.delegate(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)

    private val SharedPreferences.hostToggle: Set<String>
        by preferences.delegate(PREF_HOSTER_KEY, HOSTERS.toSet())

    private val SharedPreferences.typeToggle: Set<String>
        by preferences.delegate(PREF_TYPE_TOGGLE_KEY, DEFAULT_TYPES)

    private val SharedPreferences.prefTimeout
        by preferences.delegate(PREF_TIMEOUT_KEY, PREF_TIMEOUT_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            baseUrl = it
            docHeaders = headersBuilder().build()
            client = network.client.newBuilder()
                .rateLimitHost(baseUrl.toHttpUrl(), RATE_LIMIT)
                .build()
        }

        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred title language",
            entries = PREF_TITLE_LANG_LIST,
            entryValues = PREF_TITLE_LANG_LIST,
            default = PREF_TITLE_LANG_DEFAULT,
            summary = "%s",
        ) {
            useEnglish = it == "English"
        }

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = HOSTERS,
            entryValues = HOSTERS,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Type",
            entries = TYPES_ENTRIES,
            entryValues = TYPES_ENTRIES, // Using entries directly for parsing Quality string
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Enable/Disable Hosts",
            summary = "Select which video hosts to show in the episode list",
            entries = HOSTERS,
            entryValues = HOSTERS,
            default = HOSTERS.toSet(),
        )

        screen.addSetPreference(
            key = PREF_TYPE_TOGGLE_KEY,
            title = "Enable/Disable Types",
            summary = "Select which video types to show in the episode list",
            entries = TYPES_ENTRIES,
            entryValues = TYPES_VALUES,
            default = DEFAULT_TYPES,
        )

        screen.addEditTextPreference(
            key = PREF_TIMEOUT_KEY,
            default = PREF_TIMEOUT_DEFAULT,
            title = "Timeout for slow network",
            summary = timeoutSummary(preferences.prefTimeout),
            getSummary = timeoutSummary,
            dialogMessage = "Set timeout to wait for parsing video in seconds",
            validate = { it.isNotBlank() && (it.toIntOrNull() ?: 0) > 0 },
            validationMessage = { "The value is invalid. It must be a natural number." },
        ) {
            universalExtractor = UniversalExtractor(client, it.toLongOrNull() ?: PREF_TIMEOUT_DEFAULT.toLong())
        }
    }

    val timeoutSummary: (String) -> String = { "Current: $it seconds" }
}
