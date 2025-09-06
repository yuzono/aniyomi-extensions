package eu.kanade.tachiyomi.animeextension.en.animekai

import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
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
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.LazyMutable
import extensions.utils.addListPreference
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
            .rateLimitHost(baseUrl.toHttpUrl(), 5)
            .build()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/trending?page=$page")
    }

    override fun popularAnimeSelector() = "div.aitem-wrapper div.aitem"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            element.selectFirst("a.poster")?.attr("href")?.let {
                setUrlWithoutDomain(it)
            }
            title = element.select("a.title").text()
            thumbnail_url = element.select("a.poster img").attr("data-src")
        }
    }

    override fun popularAnimeNextPageSelector() = "nav > ul.pagination > li.active ~ li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/updates?page=$page")
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/browser?keyword=$query")
    }

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun relatedAnimeListSelector() = "div.aitem-col a.aitem"

    override fun relatedAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            title = element.select("div.title").text()
            thumbnail_url = element.attr("style").substringAfter("('").substringBefore("')")
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

            document.selectFirst("div#main-entity")!!.let { info ->
                title = info.selectFirst("h1.title")?.text().orEmpty()
                val jptitle = info.selectFirst("h1.title")?.attr("data-jp").orEmpty()
                val altTitles = info.selectFirst(".al-title")?.text().orEmpty()
                    .split(";").map { it.trim() }.distinctBy { it.lowercase() }
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
            }
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
        return selectFirst(coverSelector)?.let {
            val style = it.attr("style")
            coverUrlRegex.find(style)?.groupValues?.getOrNull(1)
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = client.newCall(animeDetailsRequest(anime))
            .execute().use {
                val document = it.asJsoup()
                document.selectFirst("div[data-id]")?.attr("data-id")
                    ?: throw IllegalStateException("Anime ID not found")
            }

        val decoded = client.newCall(GET("${BuildConfig.KAISVA}/?f=e&d=$animeId"))
            .execute().use { it.body.string() }
        return GET("$baseUrl/ajax/episodes/list?ani_id=$animeId&_=$decoded", docHeaders)
    }

    override fun episodeListSelector() = "div.eplist a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.parseAs<ResultResponse>().toDocument()

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

        val servers = client.newCall(GET("$baseUrl/ajax/links/list?token=$token&_=$decodedToken", docHeaders))
            .awaitSuccess().use { response ->
                val document = response.parseAs<ResultResponse>().toDocument()
                document.select("div.server-items[data-id]")
                    .flatMap { typeElm ->
                        val type = typeElm.attr("data-id")
                        typeElm.select("span.server[data-lid]")
                            .map { serverElm ->
                                val serverId = serverElm.attr("data-lid")
                                val serverName = serverElm.text()

                                VideoData(type, serverId, serverName)
                            }
                    }
            }

        return servers.flatMap { server ->
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

    private val universalExtractor by lazy { UniversalExtractor(client) }

    private suspend fun extractVideo(server: VideoData): List<Video> {
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
                if (url.contains("?")) {
                    "$url&autostart=true"
                } else {
                    "$url?autostart=true"
                }
            }

        val typeSuffix = when (type) {
            "sub" -> "Hard Sub"
            "softsub" -> "Soft Sub"
            "dub" -> "Dub & S-Sub"
            else -> type
        }
        val name = "$serverName | [$typeSuffix]"

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
                universalExtractor.videosFromUrl(iframe, docHeaders, name, withSub = false)
            } else {
                universalExtractor.videosFromUrl(iframe, docHeaders, name)
            }
        } catch (e: Exception) {
            Log.e("AnimeKai", "Failed to extract video from iframe: $iframe", e)
            emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(lang) }
                .thenByDescending { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains(server, true) },
        )
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Completed", "Finished Airing" -> SAnime.COMPLETED
            "Releasing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
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
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "[Soft Sub]"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Server 1"

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val HOSTERS = arrayOf(
            "Server 1",
            "Server 2",
        )
        private val PREF_HOSTER_DEFAULT = HOSTERS.toSet()

        private const val PREF_TYPE_TOGGLE_KEY = "type_selection"
        private val TYPES = arrayOf("[Hard Sub]", "[Soft Sub]", "[Dub & S-Sub]")
        private val PREF_TYPES_TOGGLE_DEFAULT = TYPES.toSet()
    }

    // ============================== Settings ==============================

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
        }

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

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Preferred Type"
            entries = TYPES
            entryValues = TYPES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = HOSTERS
            entryValues = HOSTERS
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/Disable Hosts"
            entries = HOSTERS
            entryValues = HOSTERS
            setDefaultValue(PREF_HOSTER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_TYPE_TOGGLE_KEY
            title = "Enable/Disable Types"
            entries = TYPES
            entryValues = TYPES
            setDefaultValue(PREF_TYPES_TOGGLE_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }
}
