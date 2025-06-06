package eu.kanade.tachiyomi.multisrc.wcotheme

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

abstract class WcoTheme : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", DESKTOP_USER_AGENT)
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun popularAnimeSelector() = "div.sidebar-titles > ul > li > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        title = element.ownText()
        thumbnail_url = "$baseUrl/favicon.ico"
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesSelector(): String = "div.recent-release:contains(Recent Releases) + div > ul > li"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            title = element.text()
            thumbnail_url = element.select("img[src]").attr("abs:src")
        }
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        return client.newCall(latestUpdatesRequest(page))
            .execute()
            .use { response ->
                if (page == 1) {
                    latestUpdatesParse(response)
                } else {
                    val document = response.asJsoup()

                    val animes = document.select(latestUpdatesNextPageSelector())
                        .map { element ->
                            latestUpdatesFromElement(element)
                        }

                    return AnimesPage(animes, false)
                }
            }
    }

    override fun latestUpdatesNextPageSelector() = "div.recent-release:contains(Recently Added) + div > ul > li"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genresFilter = filters.filterIsInstance<Filters.GenresFilter>().firstOrNull()

        if (query.isNotBlank()) {
            val formBody = FormBody.Builder()
                .add("catara", query)
                .add("konuara", "series")
                .build()
            return POST("$baseUrl/search", headers, body = formBody)
        } else if (genresFilter != null && !genresFilter.isDefault()) {
            val url = "$baseUrl/search-by-genre/page/${genresFilter.toUriPart()}"
            return GET(url, headers)
        } else {
            return popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchUrl = response.request.url.toString()

        if (searchUrl.contains("/search-by-genre/")) {
            // If the response is from a genre search, use the genre selector
            val document = response.asJsoup()
            return document.select(genreAnimeSelector()).map { genreAnimeFromElement(it) }
                .let { AnimesPage(it, false) }
        }
        if (searchUrl.contains("/search")) {
            val document = response.asJsoup()
            return document.select(searchAnimeSelector()).map { searchAnimeFromElement(it) }
                .let { AnimesPage(it, false) }
        }
        return popularAnimeParse(response)
    }

    override fun searchAnimeSelector() = "div#sidebar_right2 li"

    open fun genreAnimeSelector() = "div#sidebar_right4 .ddmcc li a"

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    open fun genreAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = null

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.selectFirst("div.video-title a")?.text()?.let { title = it }
        description = document.selectFirst("div#sidebar_cat p")?.text()
        thumbnail_url = document.selectFirst("div#sidebar_cat img")?.attr("abs:src")
        genre = document.select("div#sidebar_cat > a").joinToString { it.text() }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.cat-eps"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }
            // If opening an episode link instead of anime link, there is no episode list available.
            // So we return the same episode with the title from the page.
            .ifEmpty {
                listOf(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(response.request.url.toString())
                        val title = document.select(".video-title").text()
                        val (name, _) = episodeTitleFromElement(title)
                        this.name = name
                    },
                )
            }
    }

    private val episodeTitleRegex by lazy { Regex("(Season (\\d+) )?Episode (\\d+) (.*)") }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        val title = element.text()
        val (name, _) = episodeTitleFromElement(title)
        this.name = name
    }

    open fun episodeTitleFromElement(title: String): Pair<String, Float> {
        val matchResult = episodeTitleRegex.find(title)
        return if (matchResult != null) {
            // Extract season and episode numbers from the title
            val (_, season, episode, episodeTitle) = matchResult.destructured
            val seasonNum = season.toIntOrNull()
            val episodeNum = episode.toIntOrNull()
            val episodeNumber = (((seasonNum ?: 1) - 1) * 100 + (episodeNum ?: 1)).toFloat()
            val name = StringBuilder().apply {
                seasonNum?.let { append("Season $it - ") }
                episodeNum?.let { append("Episode $episodeNum: ") }
                append(episodeTitle.trim())
            }.toString()
            name to episodeNumber
        } else {
            // Fallback for titles that don't match the regex
            title to 1f
        }
    }

    // ============================ Video Links =============================
    @Serializable
    data class VideoResponseDto(
        val server: String,
        @SerialName("enc")
        val sd: String?,
        val hd: String?,
        val fhd: String?,
    ) {
        val videos by lazy {
            listOfNotNull(
                sd?.takeIf(String::isNotBlank)?.let { Pair("SD", it) },
                hd?.takeIf(String::isNotBlank)?.let { Pair("HD", it) },
                fhd?.takeIf(String::isNotBlank)?.let { Pair("FHD", it) },
            ).map {
                val videoUrl = "$server/getvid?evid=" + it.second
                Video(videoUrl, it.first, videoUrl)
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val iframeLink = document.selectFirst("iframe")?.attr("src")
            ?: throw Exception("No iframe found in the episode page")

        val iframeSoup = client.newCall(GET(iframeLink, headers))
            .execute().asJsoup()

        val getVideoLinkScript = iframeSoup.selectFirst("script:containsData(getJSON)")!!.data()
        val getVideoLink = getVideoLinkScript.substringAfter("\$.getJSON(\"").substringBefore("\"")

        val iframeDomain = "https://" + iframeLink.toHttpUrl().host
        val requestUrl = iframeDomain + getVideoLink

        val requestHeaders = headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .set("Referer", requestUrl)
            .set("Origin", iframeDomain)
            .build()

        val videoData = client.newCall(GET(requestUrl, requestHeaders)).execute()
            .parseAs<VideoResponseDto>()

        return videoData.videos
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains("HD") },
            ),
        ).reversed()
    }

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.GenresFilter(),
    )

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
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
    override val supportsRelatedAnimes = false

    // ============================= Utilities ==============================

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "HD"
        private val PREF_QUALITY_ENTRIES = arrayOf("FHD", "HD", "SD")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.150 Safari/537.36 Edg/88.0.705.63"
    }
}
