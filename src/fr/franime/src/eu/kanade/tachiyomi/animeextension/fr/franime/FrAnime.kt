package eu.kanade.tachiyomi.animeextension.fr.franime

import eu.kanade.tachiyomi.animeextension.fr.franime.dto.Anime
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class FrAnime : AnimeHttpSource() {

    override val name = "FRAnime"

    private val domain = "franime.fr"

    override val baseUrl = "https://$domain"

    private val baseApiUrl = "https://api.$domain/api"
    private val baseApiAnimeUrl = "$baseApiUrl/anime"

    override val lang = "fr"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val json: Json by injectLazy()

    private var cachedDatabase: List<Anime>? = null

    @OptIn(ExperimentalSerializationApi::class)
    private fun getDb(): List<Anime> {
        return cachedDatabase ?: synchronized(this) {
            cachedDatabase ?: client.newCall(GET("$baseApiUrl/animes/", headers)).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                json.decodeFromStream<List<Anime>>(response.body.source().inputStream()).also { cachedDatabase = it }
            }
        }
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int) =
        pagesToAnimesPage(getDb().sortedByDescending { it.note }, page)

    override fun popularAnimeParse(response: Response) = throw UnsupportedOperationException()

    override fun popularAnimeRequest(page: Int) = throw UnsupportedOperationException()

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int) = pagesToAnimesPage(getDb().reversed(), page)

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val pages = getDb().filter {
            it.title.contains(query, true) ||
                it.originalTitle.contains(query, true) ||
                it.titlesAlt.en?.contains(query, true) == true ||
                it.titlesAlt.enJp?.contains(query, true) == true ||
                it.titlesAlt.jaJp?.contains(query, true) == true ||
                titleToUrl(it.originalTitle).contains(query)
        }
        return pagesToAnimesPage(pages, page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = (baseUrl + anime.url).toHttpUrl()
        val stem = url.encodedPathSegments.last()
        val language = url.queryParameter("lang") ?: "vo"
        val season = url.queryParameter("s")?.toIntOrNull() ?: 1
        val animeData = getDb().first { titleToUrl(it.originalTitle) == stem }
        val episodes = (animeData.seasons.getOrNull(season - 1)?.episodes ?: emptyList())
            .mapIndexedNotNull { index, episode ->
                val players = when (language) {
                    "vo" -> episode.languages.vo
                    else -> episode.languages.vf
                }.players

                if (players.isEmpty()) return@mapIndexedNotNull null

                SEpisode.create().apply {
                    setUrlWithoutDomain(anime.url + "&ep=${index + 1}")
                    name = episode.title ?: "Episode ${index + 1}"
                    episode_number = (index + 1).toFloat()
                }
            }
        return episodes.sortedByDescending { it.episode_number }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = (baseUrl + episode.url).toHttpUrl()
        val seasonNumber = url.queryParameter("s")?.toIntOrNull() ?: 1
        val episodeNumber = url.queryParameter("ep")?.toIntOrNull() ?: 1
        val episodeLang = url.queryParameter("lang") ?: "vo"
        val stem = url.encodedPathSegments.last()
        val animeData = getDb().first { titleToUrl(it.originalTitle) == stem }
        val episodeData = animeData.seasons[seasonNumber - 1].episodes[episodeNumber - 1]
        val videoBaseUrl = "$baseApiAnimeUrl/${animeData.id}/${seasonNumber - 1}/${episodeNumber - 1}"

        val players = if (episodeLang == "vo") episodeData.languages.vo.players else episodeData.languages.vf.players

        val cookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl()).joinToString("; ") { "${it.name}=${it.value}" }
        val newHeaders = headers.newBuilder()
            .add("Cookie", cookies)
            .build()

        val sendvidExtractor by lazy { SendvidExtractor(client, newHeaders) }
        val sibnetExtractor by lazy { SibnetExtractor(client) }
        val vkExtractor by lazy { VkExtractor(client, newHeaders) }
        val vidMolyExtractor by lazy { VidMolyExtractor(client, newHeaders) }

        val videos = players.withIndex().parallelCatchingFlatMap { (index, playerName) ->
            val apiUrl = "$videoBaseUrl/$episodeLang/$index"
            val playerUrl = client.newCall(GET(apiUrl, headers)).await().body.string()
            val extractedVideos = when (playerName) {
                "sendvid" -> sendvidExtractor.videosFromUrl(playerUrl)
                "sibnet" -> sibnetExtractor.videosFromUrl(playerUrl)
                "vk" -> vkExtractor.videosFromUrl(playerUrl)
                "vidmoly" -> vidMolyExtractor.videosFromUrl(playerUrl)
                else -> emptyList()
            }
            extractedVideos.map { it.copy(headers = headersBuilder().build()) }
        }
        return videos
    }

    // ============================= Utilities ==============================
    private fun pagesToAnimesPage(pages: List<Anime>, page: Int): AnimesPage {
        val chunks = pages.chunked(50)
        val hasNextPage = chunks.size > page
        val entries = pageToSAnimes(chunks.getOrNull(page - 1) ?: emptyList())
        return AnimesPage(entries, hasNextPage)
    }

    private fun titleToUrl(title: String) = title
        .lowercase()
        .replace(Regex("[^a-z0-9\\s]"), "")
        .trim()
        .replace(Regex("\\s+"), "-")

    private fun pageToSAnimes(page: List<Anime>): List<SAnime> {
        return page.flatMap { anime ->
            anime.seasons.mapIndexed { index, season ->
                val hasVostfr = season.episodes.any { ep -> ep.languages.vo.players.isNotEmpty() }
                val hasVf = season.episodes.any { ep -> ep.languages.vf.players.isNotEmpty() }

                buildList {
                    if (hasVostfr) add(createSAnime(anime, index, "VOSTFR", "vo", hasVf))
                    if (hasVf) add(createSAnime(anime, index, "VF", "vf", hasVostfr))
                }
            }.flatten()
        }
    }

    private fun createSAnime(anime: Anime, seasonIndex: Int, langLabel: String, langCode: String, showLangInTitle: Boolean): SAnime {
        return SAnime.create().apply {
            val seasonTitle = anime.title + if (anime.seasons.size > 1) " S${seasonIndex + 1}" else ""
            title = if (showLangInTitle) "$seasonTitle ($langLabel)" else seasonTitle
            thumbnail_url = anime.poster
            genre = anime.genres.joinToString()
            status = parseStatus(anime.status, anime.seasons.size, seasonIndex + 1)
            description = anime.description
            setUrlWithoutDomain("/anime/${titleToUrl(anime.originalTitle)}?lang=$langCode&s=${seasonIndex + 1}")
            initialized = true
        }
    }

    private fun parseStatus(statusString: String?, seasonCount: Int = 1, season: Int = 1): Int {
        if (season < seasonCount) return SAnime.COMPLETED
        return when (statusString?.trim()) {
            "EN COURS" -> SAnime.ONGOING
            "TERMINÉ" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
