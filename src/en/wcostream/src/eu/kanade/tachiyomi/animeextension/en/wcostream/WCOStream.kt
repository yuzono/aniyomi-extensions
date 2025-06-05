package eu.kanade.tachiyomi.animeextension.en.wcostream

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class WCOStream : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "WCOStream"

    override val baseUrl = "https://www.wcostream.tv"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder()
            .add("User-Agent", DESKTOP_USER_AGENT)
            .add("Referer", "$baseUrl/")
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div#content div.menu ul > li > a"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            title = element.ownText()
            thumbnail_url = "$baseUrl/wp-content/themes/animewp78712/images/logo.gif"
        }
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // episodes

    override fun episodeListSelector() = "div#catlist-listview > ul > li, table:has(> tbody > tr > td > h3:contains(Episode List)) div.menustyle > ul > li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select("a").text())
        episode.setUrlWithoutDomain(element.select("a").attr("href").toHttpUrl().encodedPath)
        episode.name = "Episode: " + element.select("a").text()
        episode.episode_number = when {
            epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
            else -> 1F
        }
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video Extractor

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val iframeUrl = document.selectFirst("iframe")?.attr("src")
            ?: throw Exception("No iframe found in the episode page")

        val iframeHeaders = Headers.headersOf(
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Connection", "keep-alive",
            "Host", iframeUrl.toHttpUrl().host,
            "Referer", "$baseUrl/",
            "Sec-Fetch-Dest", "iframe",
            "Sec-Fetch-Mode", "navigate",
            "Sec-Fetch-Site", "cross-site",
            "Upgrade-Insecure-Requests", "1",
            "User-Agent", DESKTOP_USER_AGENT,
        )
        val iframeSoup = client.newCall(
            GET(iframeUrl, headers = iframeHeaders),
        ).execute().asJsoup()
        val getVideoLinkScript = iframeSoup.selectFirst("script:containsData(getJSON)")!!.data()
        val getVideoLinkUrl = getVideoLinkScript.substringAfter("getJSON(\"").substringBefore("\"")

        val getVideoHeaders = Headers.headersOf(
            "Accept", "application/json, text/javascript, */*; q=0.01",
            "Host", iframeUrl.toHttpUrl().host,
            "Referer", iframeUrl,
            "User-Agent", DESKTOP_USER_AGENT,
            "X-Requested-With", "XMLHttpRequest",
        )

        val getVideoLinkBody = client.newCall(
            GET("https://${iframeUrl.toHttpUrl().host}$getVideoLinkUrl", headers = getVideoHeaders),
        ).execute().body.string()

        val parsed = json.decodeFromString<GetVideoResponse>(getVideoLinkBody)
        val videoUrl = "${parsed.server}/getvid?evid=${parsed.enc}"

        val videoHeaders = Headers.headersOf(
            "Accept",
            "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
            "Host",
            videoUrl.toHttpUrl().host,
            "Referer",
            iframeUrl.toHttpUrl().host,
            "User-Agent",
            DESKTOP_USER_AGENT,
        )
        videoList.add(Video(videoUrl, "Video 480p", videoUrl, headers = videoHeaders))

        if (!parsed.hd.isNullOrEmpty()) {
            val videoHdUrl = "${parsed.server}/getvid?evid=${parsed.hd}"
            videoList.add(
                Video(
                    videoHdUrl,
                    "Video 720p",
                    videoHdUrl,
                    headers = videoHeaders,
                ),
            )
        }

        if (!parsed.fhd.isNullOrEmpty()) {
            val videoFhdUrl = "${parsed.server}/getvid?evid=${parsed.fhd}"
            videoList.add(
                Video(
                    videoFhdUrl,
                    "Video 1080p",
                    videoFhdUrl,
                    headers = videoHeaders,
                ),
            )
        }

        return videoList.sort()
    }

    @Serializable
    data class GetVideoResponse(
        val enc: String,
        val server: String,
        val hd: String? = null,
        val fhd: String? = null,
    )

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains("720") },
            ),
        ).reversed()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("a").attr("href").replace("watch", "anime").substringBefore("-episode"))
            title = element.select("a").attr("title")
            thumbnail_url = element.select("img").attr("abs:src")
        }
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun searchAnimeSelector(): String = "div#blog > div.cerceve"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = "catara=${query.replace(" ", "+")}&konuara=series".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val headers = Headers.headersOf(
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Content-Type", "application/x-www-form-urlencoded",
            "Host", baseUrl.toHttpUrl().host,
            "Origin", baseUrl,
            "Referer", "$baseUrl/search",
            "User-Agent", DESKTOP_USER_AGENT,
        )
        return POST("$baseUrl/search", body = body, headers = headers)
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            genre = document.select("div#cat-genre > div.wcobtn").joinToString(", ") { it.text() }
            description = document.select("div#content div.katcont div.iltext p").text()
            thumbnail_url = document.select("#cat-img-desc img").attr("abs:src")
        }
    }

    // Latest

    override fun latestUpdatesSelector(): String = "div#content > div > div:has(div.recent-release:contains(Recent Releases)) > div > ul > li"

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("a").attr("abs:href"))
            title = element.select("div.recent-release-episodes > a").text().substringBefore(" Episode")
            thumbnail_url = element.select("img[src]").attr("abs:src")
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null

    override val supportsRelatedAnimes = false

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        private const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.150 Safari/537.36 Edg/88.0.705.63"
    }
}
