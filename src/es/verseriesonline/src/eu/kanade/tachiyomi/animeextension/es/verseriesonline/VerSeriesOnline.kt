package eu.kanade.tachiyomi.animeextension.es.verseriesonline

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class VerSeriesOnline : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "VerSeriesOnline"

    override val baseUrl = "https://www.verseriesonline.net"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }

    private val extractorMap: Map<String, (String, String) -> List<Video>> = mapOf(
        "streamwish" to { url, name -> streamwishExtractor.videosFromUrl(url, name) },
        "filemoon" to { url, name -> filemoonExtractor.videosFromUrl(url, name) },
        "voe" to { url, name -> voeExtractor.videosFromUrl(url, name) },
        "uqload" to { url, name -> uqloadExtractor.videosFromUrl(url, name) },
        "vudeo" to { url, name -> vudeoExtractor.videosFromUrl(url, name) },
        "streamtape" to { url, name -> streamtapeExtractor.videosFromUrl(url, name) },
        "dood" to { url, name -> doodExtractor.videosFromUrl(url, name) },
        "doodstream" to { url, name -> doodExtractor.videosFromUrl(url, name) },
        "vidmoly" to { url, name -> doodExtractor.videosFromUrl(url, name) },
        "vidhidepro" to { url, name -> doodExtractor.videosFromUrl(url, name) },
        "waaw" to { url, name -> doodExtractor.videosFromUrl(url, name) },
    )

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/series-online/page/$page", headers)

    override fun popularAnimeSelector() = "div.short.gridder-list"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.short_img")!!.attr("href"))
        title = element.selectFirst("div.short_title a")!!.text()
        thumbnail_url = "$baseUrl/" + element.selectFirst("a.short_img img")!!.attr("data-src")
    }

    override fun popularAnimeNextPageSelector() = ".navigation a:last-of-type"

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector(): String? = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/recherche?q=$id", headers)).awaitSuccess().use(::searchAnimeByIdParse)
        } else {
            val url = buildSearchUrl(query, page, filters)
            client.newCall(GET(url, headers)).awaitSuccess().use { response ->
                val document = response.asJsoup()
                val animeList = document.select(searchAnimeSelector()).map { searchAnimeFromElement(it) }
                val hasNextPage = document.select(searchAnimeNextPageSelector()).isNotEmpty()
                AnimesPage(animeList, hasNextPage)
            }
        }
    }

    private fun buildSearchUrl(query: String, page: Int, filters: AnimeFilterList): String {
        val genre = (filters.find { it is GenreFilter } as? GenreFilter)?.toUriPart().orEmpty()
        val year = (filters.find { it is YearFilter } as? YearFilter)?.toUriPart().orEmpty()

        return when {
            query.isNotEmpty() -> "$baseUrl/recherche?q=$query&page=$page"
            year.isNotEmpty() -> "$baseUrl/series-online/ano/$year/page/$page"
            else -> "$baseUrl/series-online/genero/$genre/page/$page"
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/recherche?q=$query&page=$page", headers)
    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        thumbnail_url = document.selectFirst("img.lazy-loaded")?.attr("data-src")
        description = document.selectFirst("div.full_content-desc p span")?.text() ?: "Descripción no encontrada"
        genre = document.select("ul#full_info li.vis span:contains(Genre:) + a").joinToString(", ") { it.text() }
        author = document.select("ul#full_info li.vis span:contains(Director:) + a").text()
        status = SAnime.UNKNOWN
    }

    override fun episodeListSelector() = "div.seasontab div.floats a.th-hover"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun seasonListSelector() = "div.floats a"
    private fun seasonEpisodesSelector() = "#dle-content > article > div > div:nth-child(3) > div > div > a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        document.select(seasonListSelector()).forEach { seasonElement ->
            val seasonUrl = seasonElement.attr("href")
            val seasonNumber = Regex("temporada-(\\d+)").find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val seasonDocument = client.newCall(GET(seasonUrl)).execute().asJsoup()

            seasonDocument.select(seasonEpisodesSelector()).forEach { episodeElement ->
                val episode = SEpisode.create()
                episode.setUrlWithoutDomain(episodeElement.attr("href"))
                val name = episodeElement.selectFirst("span.name")?.text()?.trim() ?: "Episodio desconocido"
                episode.name = "Temporada $seasonNumber - $name"
                episode.episode_number = Regex("Cap[íi]tulo (\\d+)").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0F
                episodeList.add(episode)
            }
        }

        return episodeList
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val ignoredServers = mutableListOf<String>()

        val csrfToken = document.select("meta[name=csrf-token]").firstOrNull()?.attr("content") ?: return videoList

        document.select(".player-list li div.lien").forEach { element ->
            try {
                val dataHash = element.attr("data-hash")
                if (dataHash.isBlank()) return@forEach

                val language = when {
                    element.select("img[src*=lat]").isNotEmpty() -> "Latino"
                    element.select("img[src*=esp]").isNotEmpty() -> "Castellano"
                    element.select("img[src*=subesp]").isNotEmpty() -> "VOSE"
                    else -> "Desconocido"
                }

                val serverText = element.select(".serv").text().lowercase().trim()
                val serverName = when {
                    serverText.contains("streamwish") -> "Streamwish"
                    serverText.contains("filemoon") -> "Filemoon"
                    serverText.contains("voe") -> "Voe"
                    serverText.contains("uqload") -> "Uqload"
                    serverText.contains("vudeo") -> "Vudeo"
                    serverText.contains("streamtape") -> "Streamtape"
                    serverText.contains("dood") -> "Doodstream"
                    serverText.contains("doodstream") -> "Doodstream"
                    serverText.contains("vidmoly") -> "Doodstream"
                    serverText.contains("vidhidepro") -> "Doodstream"
                    serverText.contains("waaw") -> "Doodstream"
                    else -> "Unknown"
                }

                val request = Request.Builder()
                    .url("$baseUrl/hashembedlink")
                    .post(FormBody.Builder().add("hash", dataHash).add("_token", csrfToken).build())
                    .headers(headers)
                    .build()

                Thread.sleep(1000)

                client.newCall(request).execute().use { videoResponse ->
                    val videoUrl = JSONObject(videoResponse.body.string()).getString("link")

                    val extractedVideos = when (serverName) {
                        "Streamwish" -> streamwishExtractor.videosFromUrl(videoUrl, "$language - Streamwish")
                        "Filemoon" -> filemoonExtractor.videosFromUrl(videoUrl, "$language - Filemoon")
                        "Voe" -> voeExtractor.videosFromUrl(videoUrl, "$language - Voe")
                        "Uqload" -> uqloadExtractor.videosFromUrl(videoUrl, "$language - Uqload")
                        "Vudeo" -> vudeoExtractor.videosFromUrl(videoUrl, "$language - Vudeo")
                        "Streamtape" -> streamtapeExtractor.videosFromUrl(videoUrl, "$language - Streamtape")
                        "Doodstream" -> doodExtractor.videosFromUrl(videoUrl, "$language - Doodstream")
                        else -> {
                            println("\uD83D\uDEA9 Servidor no reconocido: '$serverText' -> $videoUrl")
                            ignoredServers.add(serverText)
                            emptyList()
                        }
                    }

                    videoList.addAll(extractedVideos)
                }
            } catch (e: Exception) {
                println("\u274C Error extrayendo video: ${e.message}")
                e.printStackTrace()
            }
        }

        if (ignoredServers.isNotEmpty()) println("\u26A0\uFE0F Servidores ignorados: ${ignoredServers.joinToString()}")

        return videoList
    }

    override fun videoListSelector() = ".undervideo .player-list li"
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La búsqueda por texto ignora los filtros"),
        GenreFilter(),
        YearFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Género",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Drama", "drama"),
            Pair("Comedia", "comedia"),
            Pair("Animación", "animacion"),
            Pair("Sci-Fi", "scifi"),
            Pair("Fantasy", "fantasy"),
            Pair("Action", "accion"),
            Pair("Adventure", "aventura"),
            Pair("Crimen", "crimen"),
            Pair("Misterio", "misterio"),
            Pair("Documental", "documental"),
            Pair("Familia", "familia"),
            Pair("Kids", "kids"),
            Pair("Reality", "reality"),
            Pair("War", "war"),
            Pair("Politics", "politics"),
        ),
    )

    private class YearFilter : UriPartFilter("Año", arrayOf(Pair("<Seleccionar>", "")) + (2024 downTo 1950).map { Pair(it.toString(), it.toString()) }.toTypedArray())

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun List<Video>.sort(): List<Video> {
        val preferredLanguage = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        val preferredServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy<Video> {
                val quality = it.quality.lowercase()
                var score = 0
                if (quality.contains(preferredLanguage.lowercase())) score += 100
                if (quality.contains(preferredServer.lowercase())) score += 50
                if (quality.contains(preferredQuality)) score += 20
                score
            }.thenByDescending {
                Regex("(\\d+)p").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "480"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "Latino"
        private val LANGUAGE_LIST = arrayOf("Latino", "Castellano", "VOSE")
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Filemoon"
        private val SERVER_LIST = arrayOf("Filemoon", "Voe", "StreamTape", "Uqload", "Vudeo", "StreamWish", "Dood")
    }
}
