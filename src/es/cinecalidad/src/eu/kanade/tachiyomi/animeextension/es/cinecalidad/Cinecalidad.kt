package eu.kanade.tachiyomi.animeextension.es.cinecalidad

import android.app.Application
import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Cinecalidad : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Cinecalidad"

    override val baseUrl = "https://www.cinecalidad.ec"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/")

    override fun popularAnimeSelector(): String = "div.custom.animation-2.items.normal article"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.select("div.poster div.in_title").text().trim()
            thumbnail_url = element.select("div.poster img").attr("data-src").takeIf { it.isNotEmpty() }
            element.select("div.poster > a").attr("href").takeIf { it.isNotEmpty() }
                ?.let { setUrlWithoutDomain(it) }
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.wp-pagenavi a.nextpostslink"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = "$baseUrl/fecha-de-lanzamiento/2024/page/$page".let(::GET)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Tipos",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Series", "ver-serie"),
            Pair("Acción", "genero-de-la-pelicula/accion"),
            Pair("Animación", "genero-de-la-pelicula/animacion"),
            Pair("Anime", "genero-de-la-pelicula/anime"),
            Pair("Aventura", "genero-de-la-pelicula/aventura"),
            Pair("Bélico", "genero-de-la-pelicula/belica"),
            Pair("Ciencia ficción", "genero-de-la-pelicula/ciencia-ficcion"),
            Pair("Crimen", "genero-de-la-pelicula/crimen"),
            Pair("Comedia", "genero-de-la-pelicula/comedia"),
            Pair("Documental", "genero-de-la-pelicula/documental"),
            Pair("Drama", "genero-de-la-pelicula/drama"),
            Pair("Familiar", "genero-de-la-pelicula/familia"),
            Pair("Fantasía", "genero-de-la-pelicula/fantasia"),
            Pair("Historia", "genero-de-la-pelicula/historia"),
            Pair("Música", "genero-de-la-pelicula/musica"),
            Pair("Misterio", "genero-de-la-pelicula/misterio"),
            Pair("Terror", "genero-de-la-pelicula/terror"),
            Pair("Suspenso", "genero-de-la-pelicula/suspense"),
            Pair("Romance", "genero-de-la-pelicula/romance"),
            Pair("Dc Comics", "genero-de-la-pelicula/peliculas-de-dc-comics-online-cinecalidad"),
            Pair("Marvel", "genero-de-la-pelicula/universo-marvel"),
        ),
    )
    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/?s=$id", headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
            .apply {
                setUrlWithoutDomain(response.request.url.toString())
                initialized = true
            }
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query")
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page/")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("div.single_left h1")?.text().orEmpty()
            thumbnail_url = document.selectFirst("div.single_left img")?.attr("data-src")

            description = findDescription(document)

            genre = document.select("span:contains(Género:) a").joinToString(", ") { it.text() }
            author = document.select("span:contains(Creador:) a").joinToString(", ") { it.text() }
            artist = document.select("span:contains(Elenco:) a").joinToString(", ") { it.text() }
        }
    }

    private fun findDescription(document: Document): String {
        val fbLikeDescription = document.selectFirst("div.single_left > table > tbody > tr > td:nth-child(2) > p:nth-child(4)")?.text()
        val tdDescription = document.selectFirst("td[style='text-align:justify;'] > p")?.text()

        return when {
            fbLikeDescription?.contains("Títulos:") == true -> tdDescription
            fbLikeDescription != null -> fbLikeDescription
            tdDescription != null -> tdDescription
            else -> "sin descripción"
        } ?: "sin descripción"
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "uwu"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val isMovie = document.select(".single_left h1").isNotEmpty() && document.select("div.se-c div.se-a ul.episodios").isEmpty()

        return if (isMovie) {
            listOf(
                SEpisode.create().apply {
                    name = "Película"
                    setUrlWithoutDomain(response.request.url.toString())
                    episode_number = 1f
                },
            )
        } else {
            document.select("div.se-c div.se-a ul.episodios li").map { element ->
                val episodeLink = element.selectFirst("a")!!.attr("href")
                val seasonMatch = Regex("S(\\d+)-E(\\d+)").find(element.selectFirst(".numerando")!!.text())
                val seasonNumber = seasonMatch?.groups?.get(1)?.value?.toInt() ?: 1
                val episodeNumber = seasonMatch?.groups?.get(2)?.value?.toInt() ?: 0
                val episodeTitle = element.selectFirst(".episodiotitle a")!!.text()

                SEpisode.create().apply {
                    name = "T$seasonNumber - E$episodeNumber: $episodeTitle"
                    setUrlWithoutDomain(episodeLink)
                    episode_number = episodeNumber.toFloat()
                }
            }
        }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode { throw UnsupportedOperationException() }

    // ============================ Video Links =============================

    /*--------------------------------Video extractors------------------------------------*/
    private val extractors by lazy {
        mapOf(
            "streamtape" to StreamTapeExtractor(client),
            "okru" to OkruExtractor(client),
            "voe" to VoeExtractor(client),
            "filemoon" to FilemoonExtractor(client),
            "streamwish" to StreamWishExtractor(client, headers),
            "doodstream" to DoodExtractor(client),
            "vidhide" to VidHideExtractor(client, headers),
        )
    }

    private val serverPatterns = mapOf(
        "streamtape" to listOf("streamtape", "stp", "stape"),
        "okru" to listOf("okru", "ok."),
        "voe" to listOf("voe"),
        "filemoon" to listOf("filemoon"),
        "streamwish" to listOf("wishembed", "streamwish", "strwish", "wish", "swdyu"),
        "doodstream" to listOf("doodstream", "dood.", "ds2play", "doods."),
        "vidhide" to listOf("vidhide", "vid."),
    )

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("li.dooplay_player_option").parallelCatchingFlatMapBlocking { element ->
            val server = element.text().lowercase()
            val url = element.attr("data-option")

            val extractorKey = serverPatterns.entries.find { (_, patterns) ->
                patterns.any { server.contains(it) }
            }?.key ?: return@parallelCatchingFlatMapBlocking emptyList()

            when (val extractor = extractors[extractorKey]) {
                is StreamTapeExtractor -> listOfNotNull(extractor.videoFromUrl(url, "StreamTape"))
                is OkruExtractor -> extractor.videosFromUrl(url, fixQualities = true)
                is VoeExtractor -> extractor.videosFromUrl(url)
                is FilemoonExtractor -> extractor.videosFromUrl(url, "Filemoon:")
                is StreamWishExtractor -> extractor.videosFromUrl(url) { "StreamWish:$it" }
                is DoodExtractor -> extractor.videosFromUrl(url, "DoodStream")
                is VidHideExtractor -> extractor.videosFromUrl(url) { "VidHide:$it" }
                else -> emptyList()
            }
        }
    }

    override fun videoListSelector(): String = "li.dooplay_player_option"

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!

        return this.sortedWith(
            compareByDescending<Video> {
                it.quality.contains(lang, true)
            }.thenByDescending {
                it.quality.contains(server, true)
            }.thenByDescending {
                it.quality.contains(quality)
            }.thenByDescending {
                Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            },
        )
    }

    // =========================== Preferences =============================

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf("StreamTape", "Okru", "Voe", "Filemoon", "StreamWish", "DoodStream", "VidHide")
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "Español"
        private val LANGUAGE_LIST = arrayOf("Español", "Inglés")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        fun createListPreference(
            key: String,
            title: String,
            entries: Array<String>,
            defaultValue: String,
        ): ListPreference {
            return ListPreference(screen.context).apply {
                this.key = key
                this.title = title
                this.entries = entries
                this.entryValues = entries
                setDefaultValue(defaultValue)
                summary = "%s"

                setOnPreferenceChangeListener { _, newValue ->
                    val selected = newValue as String
                    val index = findIndexOfValue(selected)
                    val entry = entryValues[index] as String
                    preferences.edit().putString(key, entry).commit()
                }
            }
        }
        screen.addPreference(
            createListPreference(
                PREF_SERVER_KEY,
                "Preferred server",
                SERVER_LIST,
                PREF_SERVER_DEFAULT,
            ),
        )

        screen.addPreference(
            createListPreference(
                PREF_QUALITY_KEY,
                "Preferred quality",
                QUALITY_LIST,
                PREF_QUALITY_DEFAULT,
            ),
        )

        screen.addPreference(
            createListPreference(
                PREF_LANGUAGE_KEY,
                "Preferred language",
                LANGUAGE_LIST,
                PREF_LANGUAGE_DEFAULT,
            ),
        )
    }
}
