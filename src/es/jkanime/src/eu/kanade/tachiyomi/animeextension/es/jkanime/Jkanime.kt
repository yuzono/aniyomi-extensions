package eu.kanade.tachiyomi.animeextension.es.jkanime

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.jkanime.extractors.JkanimeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.addListPreference
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Jkanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Jkanime"

    override val baseUrl = "https://jkanime.net"

    override val lang = "es"

    override val supportsLatest = true

    private val noRedirectClient = network.client.newBuilder()
        .followRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.isRedirect) {
                val location = response.header("Location")
                    ?: return@addInterceptor response

                val originalParams = request.url.queryParameterNames
                    .associateWith { request.url.queryParameter(it) }

                val redirectUrl = location.toHttpUrl().newBuilder().apply {
                    originalParams.forEach { (key, value) ->
                        if (value != null) addQueryParameter(key, value)
                    }
                }.build()

                val newRequest = request.newBuilder()
                    .url(redirectUrl)
                    .build()

                response.close()

                return@addInterceptor chain.proceed(newRequest)
            }
            response
        }.build()

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.pathSegments.first() == "buscar") {
                return@addInterceptor noRedirectClient.newCall(request).execute()
            }
            chain.proceed(request)
        }.build()

    private val preferences by getPreferencesLazy {
        clearOldPrefs()
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[JAP]"
        private val LANGUAGE_LIST = listOf("[JAP]", "[LAT]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = listOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private val SERVER_LIST = listOf(
            "Okru",
            "Mixdrop",
            "StreamWish",
            "Filemoon",
            "Mp4Upload",
            "StreamTape",
            "Desuka",
            "Nozomi",
            "Desu",
        )
        private val PREF_SERVER_DEFAULT = SERVER_LIST.first()
    }

    private fun parseAnimeItem(element: Element): SAnime? {
        val itemText = element.selectFirst("div.anime__item__text a") ?: return null
        return SAnime.create().apply {
            title = itemText.text()
            thumbnail_url = element.select("div.g-0").attr("abs:data-setbg")
            setUrlWithoutDomain(itemText.attr("href"))
        }
    }

    override fun popularAnimeSelector(): String = "div.row div.row.page_mirando div.anime__item"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ranking/", headers)

    override fun popularAnimeFromElement(element: Element): SAnime = parseAnimeItem(element)!!

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = super.popularAnimeParse(response)
        val distinctList = document.animes.distinctBy { it.url }
        return AnimesPage(distinctList, false)
    }

    override fun popularAnimeNextPageSelector(): String = "uwu"

    override fun latestUpdatesSelector(): String = "div.trending_div div.custom_thumb_home a"

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("a").attr("abs:href").trim('/'))
            title = element.select("img").attr("alt")
            thumbnail_url = element.select("img").attr("abs:src")
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val vista = super.latestUpdatesParse(response)
        val distinctList = vista.animes.distinctBy { it.title }
        return AnimesPage(distinctList, false)
    }
    override fun latestUpdatesNextPageSelector(): String? = null

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val dayFilter = filters.find { it is DayFilter } as DayFilter
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            when {
                dayFilter.state != 0 -> {
                    addPathSegment("horario")
                    addPathSegment("")
                    fragment(dayFilter.toValue())
                }
                query.isNotBlank() -> {
                    addPathSegment("buscar")
                    addPathSegment(query.replace(" ", "_"))
                }
                else -> {
                    addPathSegment("directorio")
                    filterList.filterIsInstance<UriPartFilterInterface>()
                        .joinToString("&") { it.toUriPart() }
                        .takeIf { it.isNotBlank() }
                        ?.let { params ->
                            encodedQuery("page=$page&$params")
                        }
                        ?: run {
                            addQueryParameter("page", "page")
                        }
                }
            }
        }

        return GET(url.build(), headers)
    }

    private fun parseJsonFromString(text: String): String? {
        // Try to capture a JSON object assigned to `var animes = {...};` using a DOTALL regex.
        // Use greedy matching so nested braces inside the JSON are included.
        val pattern = Regex("""var\s+animes\s*=\s*(\{.*\})\s*;""", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(text)
        if (match != null) {
            return match.groups[1]?.value
        }

        // Fallback:
        val startChar = '{'
        val endChar = '}'
        val start = text.indexOf(startChar).takeIf { it != -1 } ?: return null

        var inString = false
        var escapeChar = false
        var countChar = 0
        for (i in start until text.length) {
            val c = text[i]
            if (escapeChar) {
                escapeChar = false
            } else if (c == '\\') {
                escapeChar = true
            } else if (c == '"') {
                inString = !inString
            } else if (!inString) {
                if (c == startChar) countChar++
                if (c == endChar) countChar--
                if (countChar == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun searchAnimeParseDirectory(document: Document): AnimesPage {
        val animePageJson = document.selectFirst("script:containsData(var animes = )")?.data()
            ?.let(::parseJsonFromString)
            ?.takeIf { it.isNotBlank() }
            ?.let { jsonStr -> json.decodeFromString<AnimePageDto>(jsonStr) }
            ?: return AnimesPage(emptyList(), false)

        val animeList = animePageJson.data.map { animeDto ->
            SAnime.create().apply {
                setUrlWithoutDomain(animeDto.url)
                title = animeDto.title
                description = animeDto.description
                thumbnail_url = animeDto.thumbnailUrl
                author = animeDto.author
                status = parseStatus(animeDto.status)
            }
        }
        return AnimesPage(animeList, !animePageJson.nextPageUrl.isNullOrBlank())
    }

    private fun searchAnimeParseSearch(document: Document): AnimesPage {
        val animes = document.select("div.row div.row.page_directorio div.anime__item")
            .mapNotNull(::parseAnimeItem)
        return AnimesPage(animes, false)
    }

    private fun searchAnimeParseSchedule(document: Document): AnimesPage {
        val day = document.location().substringAfterLast("#")
        val animeBox = document.selectFirst("h2:contains($day) ~ div.cajas")

        val animeList = animeBox?.select("div.boxx")?.mapNotNull { elm ->
            SAnime.create().apply {
                val url = elm.selectFirst("a")?.attr("abs:href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                setUrlWithoutDomain(url)
                title = elm.selectFirst("img")?.attr("title") ?: return@mapNotNull null
                thumbnail_url = elm.selectFirst("img")?.attr("abs:src")
            }
        } ?: emptyList()

        return AnimesPage(animeList, false)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val location = document.location().toHttpUrl().encodedPath
        when {
            location.startsWith("/directorio") -> return searchAnimeParseDirectory(document)
            location.startsWith("/buscar") -> return searchAnimeParseSearch(document)
            location.startsWith("/horario") -> return searchAnimeParseSchedule(document)
        }
        return AnimesPage(emptyList(), false)
    }

    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector(): String = throw UnsupportedOperationException()
    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        document.selectFirst("div.anime__details__content div.anime_pic img")?.attr("abs:src")?.let { anime.thumbnail_url = it }
        document.selectFirst("div.anime__details__content div.anime_info h3")?.text()?.let { anime.title = it }
        document.selectFirst("div.anime__details__content div.anime_info p")?.text()?.let { anime.description = it }
        document.select("div.anime__details__content div.anime_data.pc li").forEach { animeData ->
            val data = animeData.select("span").text()
            if (data.contains("Genero:")) {
                anime.genre = animeData.select("a").joinToString { it.text() }
            }
            if (data.contains("Estado")) {
                anime.status = parseStatus(animeData.select("div").text())
            }
            if (data.contains("Studios")) {
                anime.author = animeData.select("a").text()
            }
        }

        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeUrl = response.request.url.toString().trim('/')
        val pageBody = response.asJsoup()
        val token = pageBody.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return emptyList()
        val formData = FormBody.Builder().add("_token", token).build()
        val animeId = pageBody.select("div.anime__details__content div.pc div#guardar-anime")
            .attr("data-anime")

        val episodesPage = client.newCall(POST("$baseUrl/ajax/episodes/$animeId/1", headers, formData))
            .execute().body.string()
            .let { jsonStr -> json.decodeFromString<EpisodesPageDto>(jsonStr) }

        val firstEp = episodesPage.data.firstOrNull()?.number ?: 1
        val lastEp = if (firstEp == 0) (episodesPage.total - 1) else episodesPage.total

        val episodes = mutableListOf<SEpisode>()
        for (i in firstEp..lastEp) {
            val episode = SEpisode.create().apply {
                setUrlWithoutDomain("$animeUrl/$i")
                name = "Episodio $i"
                episode_number = i.toFloat()
            }
            episodes.add(episode)
        }

        return episodes.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    private val languages = arrayOf(
        Pair("1", "[JAP]"),
        Pair("3", "[LAT]"),
        Pair("4", "[CHIN]"),
    )

    private fun String.getLang(): String {
        return languages.firstOrNull { it.first == this }?.second ?: ""
    }

    private fun getVideoLinks(document: Document): List<Pair<String, String>> {
        val scriptServers = document.selectFirst("script:containsData(var video = [];)")?.data() ?: return emptyList()
        val isRemote = scriptServers.contains("= remote+'", true)
        val jsServer = scriptServers.substringAfter("var remote = '").substringBefore("'")
        val jsPath = scriptServers.substringAfter("= remote+'").substringBefore("'")

        val jsLinks = if (isRemote && jsServer.isNotEmpty()) {
            client.newCall(GET(jsServer + jsPath)).execute().body.string()
        } else {
            scriptServers.substringAfter("var servers = ").substringBefore(";").substringBefore("var")
        }.parseAs<Array<JsLinks>>().map {
            Pair(String(Base64.decode(it.remote, Base64.DEFAULT)), "${it.lang}".getLang())
        }

        val htmlLinks = document.select("div.col-lg-12.rounded.bg-servers.text-white.p-3.mt-2 a").map {
            val serverId = it.attr("data-id")
            val lang = it.attr("class").substringAfter("lg_").substringBefore(" ").getLang()
            val url = scriptServers
                .substringAfter("video[$serverId] = '<iframe class=\"player_conte\" src=\"")
                .substringBefore("\"")
                .replace("/jkokru.php?u=", "http://ok.ru/videoembed/")
                .replace("/jkvmixdrop.php?u=", "https://mixdrop.ag/e/")
                .replace("/jksw.php?u=", "https://sfastwish.com/e/")
                .replace("/jk.php?u=", "$baseUrl/")
            Pair(if (url.contains("um2.php") || url.contains("um.php")) baseUrl + url else url, lang)
        }

        return jsLinks + htmlLinks
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val jkanimeExtractor by lazy { JkanimeExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return getVideoLinks(document).parallelCatchingFlatMapBlocking { (url, lang) ->
            when {
                "ok" in url -> okruExtractor.videosFromUrl(url, "$lang ")
                "voe" in url -> voeExtractor.videosFromUrl(url, "$lang ")
                "filemoon" in url || "moonplayer" in url -> filemoonExtractor.videosFromUrl(url, "$lang Filemoon:")
                "streamtape" in url || "stp" in url || "stape" in url -> listOf(streamTapeExtractor.videoFromUrl(url, quality = "$lang StreamTape")!!)
                "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, prefix = "$lang ", headers = headers)
                "mixdrop" in url || "mdbekjwqa" in url -> mixDropExtractor.videosFromUrl(url, prefix = "$lang ")
                "sfastwish" in url || "wishembed" in url || "streamwish" in url || "strwish" in url || "wish" in url
                -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$lang StreamWish:$it" })
                "stream/jkmedia" in url -> jkanimeExtractor.getDesukaFromUrl(url, "$lang ")
                "um2.php" in url -> jkanimeExtractor.getNozomiFromUrl(url, "$lang ")
                "um.php" in url -> jkanimeExtractor.getDesuFromUrl(url, "$lang ")
                else -> universalExtractor.videosFromUrl(url, headers, prefix = lang)
            }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.qualityPref
        val server = preferences.serverPref
        val lang = preferences.langPref
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Por estrenar") -> SAnime.ONGOING
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Concluido") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto no incluye filtros"),
        DayFilter(),
        GenreFilter(),
        TypeFilter(),
        StateFilter(),
        SeasonFilter(),
        AnimeFilter.Header("Busqueda por año"),
        YearFilter(),
        AnimeFilter.Header("Filtros de ordenamiento"),
        OrderByFilter(),
        SortModifiers(),
    )

    private val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val SharedPreferences.serverPref by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val SharedPreferences.langPref by preferences.delegate(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)

    private fun SharedPreferences.clearOldPrefs(): SharedPreferences {
        val server = getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        if (server !in SERVER_LIST) {
            edit()
                .putString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
                .apply()
        }
        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_LANGUAGE_KEY,
            title = "Preferred language",
            entries = LANGUAGE_LIST,
            entryValues = LANGUAGE_LIST,
            default = PREF_LANGUAGE_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = QUALITY_LIST,
            entryValues = QUALITY_LIST,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            entries = SERVER_LIST,
            entryValues = SERVER_LIST,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )
    }
}
