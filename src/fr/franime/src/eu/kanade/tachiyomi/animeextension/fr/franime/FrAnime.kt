package eu.kanade.tachiyomi.animeextension.fr.franime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class FrAnime : AnimeHttpSource() {

    override val name = "FRAnime"
    override val baseUrl = "https://franime.fr"
    override val lang = "fr"
    override val supportsLatest = true
    override val client: OkHttpClient = network.cloudflareClient

    private val apiBase = "https://api.franime.fr/api/anime"

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    override fun popularAnimeRequest(page: Int): Request = GET(apiBase, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        val array = Json.parseToJsonElement(jsonData).jsonArray
        val animes = array.map { element ->
            SAnime.create().apply {
                val obj = element.jsonObject
                title = obj["title"]?.jsonPrimitive?.content ?: ""
                url = obj["url"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = obj["poster"]?.jsonPrimitive?.content
            }
        }
        return AnimesPage(animes, false)
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val cleanPath = anime.url.substringAfterLast("/")
        return GET("$apiBase/$cleanPath", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsonData = response.body.string()
        val animeObj = Json.parseToJsonElement(jsonData).jsonObject
        val episodes = mutableListOf<SEpisode>()
        val saisons = animeObj["saisons"]?.jsonArray ?: return emptyList()

        saisons.forEachIndexed { sIdx, saisonElement ->
            val episodesArray = saisonElement.jsonObject["episodes"]?.jsonArray ?: return@forEachIndexed
            episodesArray.forEachIndexed { eIdx, epElement ->
                val ep = epElement.jsonObject
                val episodeNumber = ep["ensemble_id"]?.jsonPrimitive?.content ?: "${eIdx + 1}"
                listOf("vostfr", "vf").forEach { lang ->
                    if (ep.containsKey(lang)) {
                        episodes.add(
                            SEpisode.create().apply {
                                name = "Saison ${sIdx + 1} Ep $episodeNumber ($lang)"
                                url = "${animeObj["url"]?.jsonPrimitive?.content}|$sIdx|$eIdx|$lang"
                                episode_number = episodeNumber.toFloatOrNull() ?: 0f
                            },
                        )
                    }
                }
            }
        }
        return episodes.reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val parts = episode.url.split("|")
        return GET("$apiBase/${parts[0]}", headers).newBuilder()
            .tag(SEpisode::class.java, episode)
            .build()
    }

    override fun videoListParse(response: Response): List<Video> {
        val jsonData = response.body.string()
        val episode = response.request.tag(SEpisode::class.java) ?: return emptyList()
        val data = episode.url.split("|")

        val animeObj = Json.parseToJsonElement(jsonData).jsonObject
        val seasons = animeObj["saisons"]?.jsonArray ?: return emptyList()
        val epArray = seasons[data[1].toInt()].jsonObject["episodes"]?.jsonArray ?: return emptyList()
        val players = epArray[data[2].toInt()].jsonObject[data[3]]?.jsonObject ?: return emptyList()

        val videos = mutableListOf<Video>()
        players.forEach { (playerName, playerUrlElement) ->
            val playerUrl = playerUrlElement.jsonPrimitive.content
            // On ajoute l'URL directement sans extraction
            // C'est Aniyomi qui demandera à 1DM ou au lecteur externe de gérer l'URL
            videos.add(Video(playerUrl, "Serveur: $playerName", playerUrl))
        }
        return videos
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.startsWith(PREFIX_SEARCH)) {
            GET("$apiBase/${query.removePrefix(PREFIX_SEARCH)}", headers)
        } else {
            GET("$apiBase/search?q=$query", headers)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)
    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)
    override fun animeDetailsParse(response: Response) = SAnime.create()

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}