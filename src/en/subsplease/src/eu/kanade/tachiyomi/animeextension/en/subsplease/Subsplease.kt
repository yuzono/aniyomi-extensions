package eu.kanade.tachiyomi.animeextension.en.subsplease

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class Subsplease : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Subsplease (Torrent)"

    override val baseUrl = "https://subsplease.org"

    override val lang = "en"

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/?f=schedule&tz=Europe/Berlin")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parsePopularAnimeJson(responseString)
    }

    private fun parsePopularAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val jOe = jObject.jsonObject["schedule"]!!.jsonObject.entries
        val animeList = mutableListOf<SAnime>()
        jOe.forEach {
            val itJ = it.value.jsonArray
            for (item in itJ) {
                val anime = SAnime.create()
                anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
                anime.setUrlWithoutDomain("$baseUrl/shows/${item.jsonObject["page"]!!.jsonPrimitive.content}")
                anime.thumbnail_url = baseUrl + item.jsonObject["image_url"]?.jsonPrimitive?.content
                animeList.add(anime)
            }
        }
        return AnimesPage(animeList, hasNextPage = false)
    }

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val sId = document.select("#show-release-table").attr("sid")
        val responseString = client.newCall(GET("$baseUrl/api/?f=show&tz=Europe/Berlin&sid=$sId")).execute().body.string()
        val url = "$baseUrl/api/?f=show&tz=Europe/Berlin&sid=$sId"
        return parseEpisodeAnimeJson(responseString, url)
    }

    private fun parseEpisodeAnimeJson(jsonLine: String?, url: String): List<SEpisode> {
        val jsonData = jsonLine ?: return emptyList()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val episodeList = mutableListOf<SEpisode>()
        val epE = jObject["episode"]!!.jsonObject.entries
        epE.forEach {
            val itJ = it.value.jsonObject
            val episode = SEpisode.create()
            val num = itJ["episode"]!!.jsonPrimitive.content
            episode.episode_number = num.toFloat()
            episode.name = "Episode $num"
            episode.date_upload = itJ["release_date"]!!.jsonPrimitive.content.toDate()
            episode.setUrlWithoutDomain("$url&num=$num")
            episodeList.add(episode)
        }
        return episodeList
    }

    private fun String?.toDate(): Long {
        return this?.let {
            runCatching {
                dateFormatter.parse(trim())?.time
            }.getOrNull()
        } ?: 0L
    }

    private val dateFormatter by lazy {
        val locale = when (lang) {
            "pt-BR" -> Locale("pt", "BR")
            else -> Locale.ENGLISH
        }
        SimpleDateFormat("MMMM d, yyyy", locale)
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val responseString = response.body.string()
        val num = response.request.url.toString()
            .substringAfter("num=")
        return videosFromElement(responseString, num)
    }

    private fun videosFromElement(jsonLine: String?, num: String): List<Video> {
        val jsonData = jsonLine ?: return emptyList()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val epE = jObject["episode"]!!.jsonObject.entries
        val videoList = mutableListOf<Video>()
        epE.forEach {
            val itJ = it.value.jsonObject
            val epN = itJ["episode"]!!.jsonPrimitive.content
            if (num == epN) {
                val dowArray = itJ["downloads"]!!.jsonArray
                for (item in dowArray) {
                    val quality = item.jsonObject["res"]!!.jsonPrimitive.content + "p"
                    val videoUrl = item.jsonObject["magnet"]!!.jsonPrimitive.content
                    videoList.add(Video(videoUrl, quality, videoUrl))
                }
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080p")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    // Search

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/api/?f=search&tz=Europe/Berlin&s=$query")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseSearchAnimeJson(responseString)
    }

    private fun parseSearchAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val jE = jObject.entries
        val animeList = mutableListOf<SAnime>()
        jE.forEach {
            val itJ = it.value.jsonObject
            val anime = SAnime.create()
            anime.title = itJ.jsonObject["show"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("$baseUrl/shows/${itJ.jsonObject["page"]!!.jsonPrimitive.content}")
            anime.thumbnail_url = baseUrl + itJ.jsonObject["image_url"]?.jsonPrimitive?.content
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage = false)
    }

    // Details

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.description = document.select("div.series-syn p ").text()
        return anime
    }

    // Latest

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Default-Quality"
            entries = arrayOf("1080p", "720p", "480p")
            entryValues = arrayOf("1080", "720", "480")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(qualityPref)
    }
}
