package eu.kanade.tachiyomi.animeextension.fr.franime

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Anime as FrAnimeDto

class FrAnime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "FRAnime"
    override val baseUrl = "https://franime.fr"
    private val baseApiUrl = "https://api.franime.fr/api"
    override val lang = "fr"
    override val supportsLatest = true

    private val json: Json by injectLazy()
    private val preferences by getPreferencesLazy()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    private val database by lazy {
        client.newCall(GET("$baseApiUrl/animes/", headers)).execute()
            .body.string()
            .let { json.decodeFromString<List<FrAnimeDto>>(it) }
    }

    override suspend fun getPopularAnime(page: Int) = pagesToAnimesPage(database.sortedByDescending { it.note }, page)
    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()
    override suspend fun getLatestUpdates(page: Int) = pagesToAnimesPage(database.reversed(), page)
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val filtered = database.filter { it.title.contains(query, true) || it.originalTitle.contains(query, true) }
        return pagesToAnimesPage(filtered, page)
    }
    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime
    override fun animeDetailsParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = (baseUrl + anime.url).toHttpUrl()
        val stem = url.encodedPathSegments.last()
        val season = url.queryParameter("s")?.toIntOrNull() ?: 1
        val animeData = database.first { titleToUrl(it.originalTitle) == stem }

        return animeData.seasons[season - 1].episodes.mapIndexed { index, ep ->
            SEpisode.create().apply {
                setUrlWithoutDomain(anime.url + "&ep=${index + 1}")
                name = ep.title ?: "Épisode ${index + 1}"
                episode_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    override fun episodeListParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = (baseUrl + episode.url).toHttpUrl()
        val stem = url.encodedPathSegments.last()
        val animeData = database.first { titleToUrl(it.originalTitle) == stem }
        val seasonIdx = (url.queryParameter("s")?.toInt() ?: 1) - 1
        val epIdx = (url.queryParameter("ep")?.toInt() ?: 1) - 1
        val lang = url.queryParameter("lang") ?: "vo"

        val videoBaseUrl = "$baseApiUrl/anime/${animeData.id}/$seasonIdx/$epIdx"
        val players = if (lang == "vo") {
            animeData.seasons[seasonIdx].episodes[epIdx].languages.vo.players
        } else {
            animeData.seasons[seasonIdx].episodes[epIdx].languages.vf.players
        }

        val sibnetExtractor by lazy { SibnetExtractor(client) }
        val vkExtractor by lazy { VkExtractor(client, headers) }
        val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
        val vidMolyExtractor by lazy { VidMolyExtractor(client) }

        return players.withIndex().parallelCatchingFlatMap { (index, name) ->
            val playerUrl = client.newCall(GET("$videoBaseUrl/$lang/$index", headers)).await().body.string()
            val prefix = "($lang) "
            val extracted = when (name.lowercase()) {
                "sibnet" -> sibnetExtractor.videosFromUrl(playerUrl, prefix)
                "vk" -> vkExtractor.videosFromUrl(playerUrl, prefix)
                "sendvid" -> sendvidExtractor.videosFromUrl(playerUrl, prefix)
                "vidmoly" -> vidMolyExtractor.videosFromUrl(playerUrl, prefix)
                else -> emptyList()
            }
            extracted.map { Video(it.url, it.quality, it.videoUrl, headers = headers) }
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    private fun pagesToAnimesPage(pages: List<FrAnimeDto>, page: Int): AnimesPage {
        val chunks = pages.chunked(50)
        val entries = pageToSAnimes(chunks.getOrNull(page - 1) ?: emptyList())
        return AnimesPage(entries, page < chunks.size)
    }

    private fun titleToUrl(title: String) = title.lowercase()
        .replace(Regex("[^a-z0-9]"), "-")
        .replace(Regex("-+"), "-")

    private fun pageToSAnimes(page: List<FrAnimeDto>): List<SAnime> {
        return page.flatMap { anime ->
            anime.seasons.mapIndexed { index, _ ->
                SAnime.create().apply {
                    title = anime.title + if (anime.seasons.size > 1) " S${index + 1}" else ""
                    thumbnail_url = anime.poster
                    setUrlWithoutDomain("/anime/${titleToUrl(anime.originalTitle)}?s=${index + 1}")
                    initialized = true
                }
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Qualité préférée"
            entries = arrayOf("1080p", "720p", "480p")
            entryValues = arrayOf("1080", "720", "480")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}