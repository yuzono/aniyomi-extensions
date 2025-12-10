package eu.kanade.tachiyomi.animeextension.en.anicore

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class Anicore : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anicore.tv"

    override val baseUrl = "https://anicore.tv"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_SERVER = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
    }

    // =============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/browse?sort=popularity&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.anime-card").map { element ->
            popularAnimeFromElement(element)
        }
        val hasNextPage = document.selectFirst("a.next") != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeSelector(): String = "div.anime-card"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        title = element.selectFirst("h3, .title")?.text() ?: ""
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
        setUrlWithoutDomain(element.selectFirst("a")?.attr("abs:href") ?: "")
    }

    override fun popularAnimeNextPageSelector(): String? = "a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/browse?sort=latest&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/search?q=$query&page=$page", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeSelector(): String = "div.anime-card"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = "a.next"

    // =============================== Details ===============================
    override fun animeDetailsRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val document = response.asJsoup()
        title = document.selectFirst(".anime-title, h1")?.text() ?: ""
        genre = document.select(".genres a, .genre-tag").joinToString(", ") { it.text() }
        description = document.selectFirst(".description, .synopsis")?.text()
        thumbnail_url = document.selectFirst(".anime-poster img, .poster img")?.attr("abs:src")
        status = parseStatus(document.selectFirst(".status")?.text() ?: "")
    }

    private fun parseStatus(statusText: String): Int = when {
        statusText.contains("ongoing", ignoreCase = true) -> SAnime.ONGOING
        statusText.contains("completed", ignoreCase = true) -> SAnime.COMPLETED
        statusText.contains("upcoming", ignoreCase = true) -> SAnime.UPCOMING
        else -> SAnime.UNKNOWN
    }

    // =============================== Episodes ===============================
    override fun episodeListRequest(anime: SAnime): Request =
        GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(".episode-item, .ep-item, li.episode").mapIndexed { idx, element ->
            episodeFromElement(element, idx + 1)
        }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun episodeFromElement(element: Element, index: Int): SEpisode = SEpisode.create().apply {
        val episodeNum = element.selectFirst(".episode-number, .ep-num")?.text()
            ?.replace(Regex("[^0-9.]"), "")?.toFloatOrNull() ?: index.toFloat()
        episode_number = episodeNum
        name = element.selectFirst(".episode-title, .title, .name")?.text()
            ?.trim() ?: "Episode $episodeNum"
        setUrlWithoutDomain(element.selectFirst("a")?.attr("abs:href") ?: "")
    }

    // =============================== Video ===============================
    override fun videoListRequest(episode: SEpisode): Request =
        GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()

        // Extract video players/iframes
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotEmpty()) {
                when {
                    "streamwish" in src -> {
                        val extractor = StreamWishExtractor(client)
                        videos.addAll(extractor.videosFromUrl(src))
                    }
                    else -> {
                        // Fallback: add as direct video
                        videos.add(Video(src, "Server", src))
                    }
                }
            }
        }

        return videos
    }

    // =============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER
            title = "Preferred Server"
            entries = arrayOf("StreamWish")
            entryValues = arrayOf("streamwish")
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            screen.addPreference(this)
        }
    }
}
