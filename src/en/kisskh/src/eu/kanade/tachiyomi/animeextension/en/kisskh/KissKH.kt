package eu.kanade.tachiyomi.animeextension.en.kisskh

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.addListPreference
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response

class KissKH : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "KissKH"

    override val lang = "en"

    override val supportsLatest = true

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val preferences by getPreferencesLazy()

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    override val supportsRelatedAnimes = false

    /* Popular Animes */

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=1&pageSize=40")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parsePopularAnimeJson(responseString)
    }

    private fun parsePopularAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val lastPage = jObject["totalCount"]!!.jsonPrimitive.int
        val page = jObject["page"]!!.jsonPrimitive.int
        val hasNextPage = page < lastPage
        val array = jObject["data"]!!.jsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["id"]!!.jsonPrimitive.content
            val titleURI = anime.title.replace(titleUriRegex, "-")
            anime.url = "/Drama/$titleURI?id=$animeId"
            anime.thumbnail_url = item.jsonObject["thumbnail"]?.jsonPrimitive?.content
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    /* Latest */

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/DramaList/List?page=$page&type=0&sub=0&country=0&status=0&order=2&pageSize=40")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseLatestAnimeJson(responseString)
    }

    private fun parseLatestAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val lastPage = jObject["totalCount"]!!.jsonPrimitive.int
        val page = jObject["page"]!!.jsonPrimitive.int
        val hasNextPage = page < lastPage
        val array = jObject["data"]!!.jsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["id"]!!.jsonPrimitive.content
            val titleURI = anime.title.replace(titleUriRegex, "-")
            anime.url = "/Drama/$titleURI?id=$animeId"
            anime.thumbnail_url = item.jsonObject["thumbnail"]?.jsonPrimitive?.content
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    /* Search */

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/api/DramaList/Search?q=$query&type=0")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseSearchAnimeJson(responseString)
    }

    private fun parseSearchAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val array = json.decodeFromString<JsonArray>(jsonData)
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["id"]!!.jsonPrimitive.content
            val titleURI = anime.title.replace(titleUriRegex, "-")
            anime.url = "/Drama/$titleURI?id=$animeId"
            anime.thumbnail_url = item.jsonObject["thumbnail"]!!.jsonPrimitive.content
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage = false)
    }

    /* Details */

    override fun getAnimeUrl(anime: SAnime): String {
        return baseUrl + anime.url
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.substringAfter("id=").substringBefore("&")
        return GET("$baseUrl/api/DramaList/Drama/$id?isq=false", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseString = response.body.string()
        return parseAnimeDetailsParseJson(responseString)
    }

    private fun parseAnimeDetailsParseJson(jsonLine: String?): SAnime {
        val anime = SAnime.create()
        val jsonData = jsonLine ?: return anime
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        anime.title = jObject.jsonObject["title"]!!.jsonPrimitive.content
        anime.status = parseStatus(jObject.jsonObject["status"]!!.jsonPrimitive.content)
        anime.description = jObject.jsonObject["description"]!!.jsonPrimitive.content
        anime.thumbnail_url = jObject.jsonObject["thumbnail"]!!.jsonPrimitive.content

        return anime
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    /* Episodes */

    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        return parseEpisodePage(responseString)
    }

    private fun parseEpisodePage(jsonLine: String?): List<SEpisode> {
        val jsonData = jsonLine ?: return mutableListOf()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val episodeList = mutableListOf<SEpisode>()
        val array = jObject["episodes"]!!.jsonArray
        val type = jObject["type"]!!.jsonPrimitive.content
        val episodesCount = jObject["episodesCount"]!!.jsonPrimitive.int
        for (item in array) {
            val episode = SEpisode.create()
            val id = item.jsonObject["id"]!!.jsonPrimitive.content
            episode.episode_number = item.jsonObject["number"]!!.jsonPrimitive.float
            val number = item.jsonObject["number"]!!.jsonPrimitive.content.replace(".0", "")
            when {
                type.contains("Anime") || type.contains("TVSeries") -> {
                    episode.name = "Episode $number"
                }
                type.contains("Hollywood") && episodesCount == 1 || type.contains("Movie") -> {
                    episode.name = "Movie"
                }
                type.contains("Hollywood") && episodesCount > 1 -> {
                    episode.name = "Episode $number"
                }
            }
            episode.url = id
            episodeList.add(episode)
        }
        return episodeList
    }

    // Video Extractor
    override fun videoListRequest(episode: SEpisode): Request {
        val kkey = requestVideoKey(episode.url)

        val url = "$baseUrl/api/DramaList/Episode/${episode.url}.png?err=false&ts=&time=&kkey=$kkey"
        return GET(url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val id = response.request.url.toString()
            .substringAfter("Episode/").substringBefore(".png")
        return videosFromElement(response, id)
    }

    private val subDecryptor by lazy { SubDecryptor(client, headers, baseUrl) }

    private fun videosFromElement(response: Response, id: String): List<Video> {
        val jsonData = response.body.string()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val videoList = mutableListOf<Video>()

        val kkey = requestSubKey(id)
        val subData = client.newCall(GET("$baseUrl/api/Sub/$id?kkey=$kkey")).execute().body.string()
        val subj = json.decodeFromString<JsonArray>(subData)
        val subList = mutableListOf<Track>()
        for (item in subj) {
            try {
                val suburl = item.jsonObject["src"]!!.jsonPrimitive.content
                val lang = item.jsonObject["label"]!!.jsonPrimitive.content

                if (suburl.contains(".txt")) {
                    subList.add(subDecryptor.getSubtitles(suburl, lang))
                } else {
                    subList.add(Track(suburl, lang))
                }
            } catch (_: Error) {}
        }
        val videoUrl = jObject["Video"]!!.jsonPrimitive.content

        videoList.add(Video(videoUrl, "FirstParty", videoUrl, subtitleTracks = subList, headers = Headers.headersOf("referer", "https://kisskh.me/", "origin", "https://kisskh.me")))

        return videoList.reversed()
    }

    private fun requestVideoKey(id: String): String {
        val url = "${BuildConfig.KISSKH_API}$id&version=2.8.10"
        return client.newCall(GET(url, headers)).execute().use { it.parseAs<Key>().key }
    }

    private fun requestSubKey(id: String): String {
        val url = "${BuildConfig.KISSKH_SUB_API}$id&version=2.8.10"
        return client.newCall(GET(url, headers)).execute().use { it.parseAs<Key>().key }
    }

    @Serializable
    data class Key(
        val id: String,
        val version: String,
        val key: String,
    )

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
    }

    private val titleUriRegex by lazy { Regex("[^a-zA-Z0-9]") }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf(
            "kisskh.ovh",
            "kisskh.do",
            "kisskh.co",
            "kisskh.id",
            "kisskh.la",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]
    }
}
