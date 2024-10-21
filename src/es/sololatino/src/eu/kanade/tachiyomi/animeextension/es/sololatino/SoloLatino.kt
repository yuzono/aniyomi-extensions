package eu.kanade.tachiyomi.animeextension.es.sololatino

import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class SoloLatino : DooPlay(
    "es",
    "SoloLatino",
    "https://sololatino.net",
) {
    private val json by lazy { Json { ignoreUnknownKeys = true } }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/tendencias/page/$page")

    override fun popularAnimeSelector() = "article.item"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/pelicula/estrenos/page/$page", headers)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val img = element.selectFirst("img")!!
            val url = element.selectFirst("a")?.attr("href") ?: element.attr("href")
            setUrlWithoutDomain(url)
            title = img.attr("alt")
            thumbnail_url = img.attr("data-srcset")
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagMovidy a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val seasonList = doc.select("div#seasons div.se-c")
        return if (seasonList.isEmpty()) {
            SEpisode.create().apply {
                setUrlWithoutDomain(doc.location())
                episode_number = 1F
                name = episodeMovieText
                date_upload = doc.selectFirst("span.date")?.text()?.toDate() ?: 0L
            }.let(::listOf)
        } else {
            seasonList.flatMap(::getSeasonEpisodes).reversed()
        }
    }

    override fun getSeasonEpisodes(season: Element): List<SEpisode> {
        val seasonName = season.attr("data-season")
        return season.select("ul.episodios li").mapNotNull { element ->
            runCatching {
                episodeFromElement(element, seasonName)
            }.onFailure { it.printStackTrace() }.getOrNull()
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        return SEpisode.create().apply {
            val epNum = element.selectFirst("div.numerando")?.text()
                ?.trim()
                ?.let { episodeNumberRegex.find(it)?.groupValues?.last() } ?: "0"

            val href = element.selectFirst("a[href]")!!.attr("href")
            val episodeName = element.selectFirst("div.epst")?.text() ?: "Sin título"

            episode_number = epNum.toFloatOrNull() ?: 0F
            date_upload = element.selectFirst("span.date")?.text()?.toDate() ?: 0L

            name = "T$seasonName - Episodio $epNum: $episodeName"
            setUrlWithoutDomain(href)
        }
    }

    override fun videoListSelector() = "li.dooplay_player_option"

    override val episodeMovieText = "Película"

    override val episodeSeasonPrefix = "Temporada"
    override val prefQualityTitle = "Calidad preferida"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val path = response.request.url.toString()
        val links = mutableListOf<String>()

        runBlocking {
            getLinks({ videoLinks -> links.addAll(videoLinks) }, { error ->
                println("Error al obtener los enlaces: $error")
            }, path)
        }

        if (links.isEmpty()) {
            return emptyList()
        }

        val lang = identifyLanguage(path)

        return links.flatMap { link ->
            extractVideosSafely(link, lang)
        }
    }

    private fun identifyLanguage(path: String): String {
        return when {
            "latino" in path -> "Lat"
            "castellano" in path -> "Esp"
            "subtitulado" in path -> "Sub"
            else -> "Otro"
        }
    }

    private fun extractVideosSafely(link: String, lang: String): List<Video> {
        return runCatching {
            extractVideos(link, lang).sort()
        }.onFailure { it.printStackTrace() }.getOrDefault(emptyList())
    }

    private suspend fun getLinks(after: (List<String>) -> Unit, onError: (Throwable) -> Unit, path: String) {
        try {
            val result = httpGet(path)
            val links = mutableListOf<String>()

            val linkPages = Regex("""data-type=["'](.+?)["'] data-post=["'](.+?)["'] data-nume=["'](.+?)["']""")
                .findAll(result)
                .toList()

            coroutineScope {
                val deferredResults = linkPages.map { matchResult ->
                    async {
                        processLinkPage(matchResult, path)
                    }
                }
                deferredResults.awaitAll().forEach { newLinks ->
                    links.addAll(newLinks)
                }
            }

            if (links.isEmpty()) {
                handleEmptyLinks(result, links)
            }

            after(links)
        } catch (error: Throwable) {
            onError(error)
        }
    }

    private fun processLinkPage(matchResult: MatchResult, path: String): List<String> {
        return try {
            val postParams = mapOf(
                "action" to "doo_player_ajax",
                "post" to (matchResult.groups[2]?.value ?: ""),
                "nume" to (matchResult.groups[3]?.value ?: ""),
                "type" to (matchResult.groups[1]?.value ?: ""),
            )
            val presp = httpPost("$baseUrl/wp-admin/admin-ajax.php", postParams, path)
            val iframeUrl = getFirstMatch("""<iframe class='[^']+' src='([^']+)""".toRegex(), presp)
            val bData = httpGet(iframeUrl)

            parseLinks(bData)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun handleEmptyLinks(result: String, links: MutableList<String>) {
        val iframeUrl = Regex("""pframe"><iframe class="[^"]+" src="([^"]+)""").find(result)?.groups?.get(1)?.value
        iframeUrl?.let { web ->
            val newResult = httpGet(web)
            links.addAll(parseLinks(newResult))

            if (links.isEmpty() && web.contains("xyz")) {
                links.add(web)
            }
        }
    }

    private fun httpRequest(
        url: String,
        method: String,
        params: Map<String, String>? = null,
        referer: String? = null,
    ): String {
        val urlObj = URL(url)
        val connection = urlObj.openConnection() as HttpURLConnection
        return try {
            with(connection) {
                requestMethod = method
                setRequestProperty("User-Agent", "Mozilla/5.0")
                referer?.let { setRequestProperty("Referer", it) }

                if (method == "POST") {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    params?.let {
                        val postData = it.map { entry -> "${entry.key}=${entry.value}" }.joinToString("&")
                        outputStream.bufferedWriter().use { writer -> writer.write(postData) }
                    }
                }

                inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGet(url: String): String {
        return httpRequest(url, "GET")
    }

    private fun httpPost(url: String, params: Map<String, String>, referer: String): String {
        return httpRequest(url, "POST", params, referer)
    }

    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client) }

    private fun extractVideos(url: String, lang: String): List<Video> {
        val vidHideDomains = listOf("vidhide", "VidHidePro", "luluvdo", "vidhideplus")
        try {
            val videos = vidHideDomains.firstOrNull { it in url }?.let { domain ->
                streamHideVidExtractor.videosFromUrl(url, videoNameGen = { "$lang - $domain : $it" })
            } ?: emptyList()
            return when {
                videos.isNotEmpty() -> videos
                "streamwish" in url -> streamWishExtractor.videosFromUrl(url, lang)
                "uqload" in url -> uqloadExtractor.videosFromUrl(url, lang)
                "vidguard" in url -> vidGuardExtractor.videosFromUrl(url, lang)
                "dood" in url -> doodExtractor.videosFromUrl(url, "$lang - ")
                "voe" in url -> voeExtractor.videosFromUrl(url, "$lang - ")
                else -> emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    private fun getFirstMatch(regex: Regex, input: String): String {
        return regex.find(input)?.groupValues?.get(1) ?: ""
    }

    private fun parseLinks(htmlContent: String): List<String> {
        val links = mutableListOf<String>()
        val doc: Document = Jsoup.parse(htmlContent)

        extractNewExtractorLinks(doc, htmlContent)?.let { links.addAll(it) }
        extractOldExtractorLinks(doc)?.let { links.addAll(it) }

        return links
    }

    private fun extractNewExtractorLinks(doc: Document, htmlContent: String): List<String>? {
        val links = mutableListOf<String>()
        val jsLinksMatch = getFirstMatch("""dataLink = (\[.+?\]);""".toRegex(), htmlContent)

        if (jsLinksMatch.isEmpty()) {
            return null
        }

        val items = json.decodeFromString<List<Item>>(jsLinksMatch)

        items.forEach { item ->
            item.sortedEmbeds.forEach { embed ->
                val decryptedLink = CryptoAES.decrypt(embed.link, "Ak7qrvvH4WKYxV2OgaeHAEg2a5eh16vE")
                links.add(decryptedLink)
                Log.d("SoloLatino", "Decrypted link: $decryptedLink")
            }
        }
        return links.ifEmpty { null }
    }

    private fun extractOldExtractorLinks(doc: Document): List<String>? {
        val links = mutableListOf<String>()
        doc.getElementsByTag("li").forEach { link ->
            runCatching {
                val onclickAttr = link.attr("onclick")
                val decoded = getFirstMatch("""\.php\?link=(.+?)&servidor=""".toRegex(), onclickAttr)
                links.add(String(Base64.decode(decoded, Base64.DEFAULT)))

                extractPlayerLink(onclickAttr, """go_to_playerVast\('(.+?)'""")?.let { links.add(it) }
                extractPlayerLink(onclickAttr, """go_to_player\('(.+?)'""")?.let { links.add(it) }
            }.onFailure {
                Log.e("SoloLatino", "Error al procesar enlace antiguo: ${it.message}")
            }
        }
        return links.ifEmpty { null }
    }

    private fun extractPlayerLink(onclickAttr: String, pattern: String): String? {
        return pattern.toRegex().find(onclickAttr)?.groupValues?.get(1)
    }

    // ============================== Filters ===============================
    override val fetchGenres = false
    override fun getFilterList() = SoloLatinoFilters.FILTER_LIST

    // ============================== Search ================================

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = SoloLatinoFilters.getSearchParameters(filters)
        val path = when {
            params.genre.isNotBlank() -> {
                when (params.genre) {
                    "animes" -> "/genres_animes"
                    "peliculas" -> "/genres"
                    "series" -> "/genres_series"
                    "tendencias", "ratings", "genre_series/toons" -> "/${params.genre}"
                    else -> "/genres/${params.genre}"
                }
            }
            params.platform.isNotBlank() -> "/network/${params.platform}"
            params.year.isNotBlank() -> "/year/${params.year}"
            else -> buildString {
                append(
                    when {
                        query.isNotBlank() -> "/?s=$query"
                        else -> "/"
                    },
                )

                append(
                    when (params.type) {
                        "serie" -> "series"
                        "pelicula" -> "peliculas"
                        "anime" -> "animes"
                        "toon" -> "genre_series/toons"
                        "todos" -> ""
                        else -> "tendencias"
                    },

                )

                if (params.isInverted) append("&orden=asc")
            }
        }

        return if (path.startsWith("/?s=")) {
            GET("$baseUrl/page/$page$path")
        } else {
            GET("$baseUrl$path/page/$page")
        }
    }

    // ============================= Details ================================
    override val additionalInfoSelector = "#single > div.content > div.wp-content"

    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }
            }

            genre = sheader.select("div.data > div.sgeneros > a")
                .eachText()
                .joinToString()

            doc.selectFirst(additionalInfoSelector)?.let { info ->
                description = buildString {
                    append(doc.getDescription())
                    additionalInfoItems.forEach {
                        info.getInfo(it)?.let(::append)
                    }
                }
            }
        }
    }

    // ============================= Serialization ===========================
    @Serializable
    data class Item(
        val sortedEmbeds: List<Embed>,
    )

    @Serializable
    data class Embed(
        val link: String,
    )

    // ============================= Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preference

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        val langPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(langPref)
    }

    // ============================= Utilities ==============================

    override fun String.toDate(): Long {
        return try {
            val dateFormat = SimpleDateFormat("MMM. dd, yyyy", Locale.ENGLISH)
            val date = dateFormat.parse(this)
            date?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override val prefQualityValues = arrayOf("480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "Lat"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf("StreamWish", "Uqload", "VidGuard", "Dood", "StreamHideVid", "Voe, VidHide, Luluvdo, VidHidePro, VidHidePlus")
        private val PREF_LANG_ENTRIES = arrayOf("Lat", "Esp", "Sub", "Otro")
        private val PREF_LANG_VALUES = arrayOf("Lat", "Esp", "Sub, Otro")
    }
}
