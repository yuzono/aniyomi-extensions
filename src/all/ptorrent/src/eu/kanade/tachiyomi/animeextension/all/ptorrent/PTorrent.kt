package eu.kanade.tachiyomi.animeextension.all.ptorrent

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.torrentutils.TorrentUtils
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.SocketTimeoutException
import java.util.Locale

class PTorrent : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "PTorrent (Torrent)"

    override val baseUrl = "https://www.ptorrents.com"

    override val lang = "all"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val supportsLatest = false

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val adjustedPage = if (page == 1) 0 else page
        return GET("$baseUrl/page/$adjustedPage")
    }

    override fun popularAnimeSelector(): String = "main.site-main article"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("h3.article-title a").attr("href"))
        anime.title = element.select("h3.article-title a").text().trim()
        anime.thumbnail_url = element.select("div.data-bg-hover").attr("data-background")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "nav.pagination a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        throw UnsupportedOperationException()
    }

    override fun latestUpdatesNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.use { it.asJsoup() })
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = query.replace(" ", "+")
        val cat = filters.firstNotNullOfOrNull { filter ->
            if (filter is CategoriesList) getCategory()[filter.state].id else 0
        }.toString()

        return if (query.isNotEmpty()) {
            GET("$baseUrl/page/$page?s=$encodedQuery")
        } else {
            GET("$baseUrl/$cat/page/$page")
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val data = document.select("div.entry-content p")
        anime.description = data.text()
        anime.thumbnail_url = data.select("img").attr("scr")
        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.torrent-file-list ul li li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val torrentFile = document.select("a.edl_post_dlinks").attr("href")
        if (torrentFile.isEmpty()) {
            throw Exception("No Torrent Found!")
        }
        try {
            val torrent = TorrentUtils.getTorrentInfo(torrentFile, "torrent")
            val torrentIndexed = torrent.files
            val torrentMagnet = "magnet:?xt=urn:btih:${torrent.hash}&dn=${torrent.hash}"
            val torrentTracker = torrent.trackers.filter { it.trim().isNotEmpty() }.joinToString("") { "&tr=$it" }
            var episodeNumber = 1F
            return torrentIndexed
                .filter { it.path.substringAfterLast('.').lowercase(Locale.ROOT) in validVideoExtensions }
                .map {
                    SEpisode.create().apply {
                        name = if (preferences.getBoolean(IS_FILENAME_KEY, IS_FILENAME_DEFAULT)) {
                            it.path.trim().split('/').last()
                        } else {
                            it.path.trim()
                                .replace("[", "(")
                                .replace(Regex("]"), ")")
                                .replace("/", "\uD83D\uDCC2 ")
                        }
                        url = "$torrentMagnet$torrentTracker&index=${it.indexFile}"
                        episode_number = episodeNumber++
                        scanlator = convertBytesToReadable(it.size)
                    }
                }.reversed()
                .toMutableList()
        } catch (e: SocketTimeoutException) {
            throw Exception("Dead Torrent \uD83D\uDE35")
        }
    }

    private val validVideoExtensions = setOf("mp4", "mov", "avi", "wmv", "mkv", "flv", "webm", "mpeg", "mpg", "mts", "vob", "ts")

    @SuppressLint("DefaultLocale")
    private fun convertBytesToReadable(bytes: Long): String {
        val kilobytes = bytes / 1024.0
        val megabytes = kilobytes / 1024.0
        val gigabytes = megabytes / 1024.0

        return when {
            gigabytes >= 1 -> String.format("%.2f GB", gigabytes)
            megabytes >= 1 -> String.format("%.2f MB", megabytes)
            else -> String.format("%.2f KB", kilobytes)
        }
    }

    override fun episodeFromElement(element: Element) = throw Exception("Not used")

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return listOf(Video(episode.url, episode.name, episode.url))
    }

    override fun videoListSelector() = throw Exception("Not used")

    override fun videoFromElement(element: Element) = throw Exception("Not used")

    override fun videoUrlParse(document: Document) = throw Exception("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = IS_FILENAME_KEY
            title = "Only display filename"
            setDefaultValue(IS_FILENAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
            summary = "Will note display full path of episode."
        }.also(screen::addPreference)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoriesList(categoryName),
    )

    private data class Category(val name: String, val id: String?)
    private class CategoriesList(category: Array<String>) : AnimeFilter.Select<String>("Category", category)
    private val categoryName = getCategory().map {
        it.name
    }.toTypedArray()
    private fun getCategory(): List<Category> {
        return listOf(
            Category("Home", "0"),
            Category("Special Porn Movies", "special-porn-movies"),
            Category("SPM- BDSM", "special-porn-movies/bdsm"),
            Category("SPM- Bisexual", "special-porn-movies/bisexual"),
            Category("SPM- Bukkake", "special-porn-movies/bukkake"),
            Category("SPM- Femdom & Strapon", "special-porn-movies/femdom-strapon"),
            Category("SPM- Fetish", "special-porn-movies/fetish"),
            Category("SPM- Fisting & Dildo", "special-porn-movies/fisting-dildo"),
            Category("SPM- Peeing", "special-porn-movies/peeing"),
            Category("SPM- Pregnant", "special-porn-movies/pregnant"),
            Category("SPM- Transsexual", "special-porn-movies/transsexual"),
            Category("SPM- Voyeur", "special-porn-movies/voyeur"),
            Category("Movies", "movies"),
            Category("Movies - Porn", "movies/porn-movies"),
            Category("Movies - HD Porn", "movies/hd-porn-movies"),
            Category("Movies - Russian", "movies/russian-porn-movies"),
            Category("Movies - 3D & VR", "movies/3d-vr"),
            Category("Anime", "adult-anime-game/anime"),
            Category("Erotic & Softcore Movies", "erotic/erotic-softcore-movies"),
            Category("Japanese Adult Video", "foreign-porn-movies/japanese-adult-video"),
        )
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val IS_FILENAME_KEY = "filename"
        private const val IS_FILENAME_DEFAULT = false
    }
}
