package eu.kanade.tachiyomi.animeextension.fr.franime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidmolyExtractor
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlinx.serialization.json.*

class FrAnime : AnimeHttpSource() {

    override val name = "FRAnime"
    override val baseUrl = "https://franime.fr"
    override val lang = "fr"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)
        .add("Accept", "application/json")

    // --- POPULAIRE / RECHERCHE ---
    override fun popularAnimeRequest(page: Int): Request = GET("https://api.franime.fr/api/anime", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val jsonData = response.body.string()
        val array = Json.parseToJsonElement(jsonData).jsonArray
        val animes = array.map { element ->
            SAnime.create().apply {
                val obj = element.jsonObject
                title = obj["title"]?.jsonPrimitive?.content ?: ""
                // On stocke l'ID original pour les appels API futurs
                url = obj["url"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = obj["poster"]?.jsonPrimitive?.content
            }
        }
        return AnimesPage(animes, false)
    }

    // --- LISTE DES EPISODES ---
    override fun episodeListRequest(anime: SAnime): Request {
        return GET("https://api.franime.fr/api/anime/${anime.url}", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsonData = response.body.string()
        val animeObj = Json.parseToJsonElement(jsonData).jsonObject
        val episodes = mutableListOf<SEpisode>()
        
        val saisons = animeObj["saisons"]?.jsonArray ?: return emptyList()
        
        saisons.forEachIndexed { sIdx, saisonElement ->
            val saison = saisonElement.jsonObject
            val episodesArray = saison["episodes"]?.jsonArray ?: return@forEachIndexed
            
            episodesArray.forEachIndexed { eIdx, epElement ->
                val ep = epElement.jsonObject
                val episodeNumber = ep["ensemble_id"]?.jsonPrimitive?.content ?: "${eIdx + 1}"
                
                // On crée une version VOSTFR et VF si les lecteurs existent
                val languages = listOf("vostfr", "vf")
                languages.forEach { lang ->
                    if (ep.containsKey(lang)) {
                        val sEpisode = SEpisode.create().apply {
                            name = "Saison ${sIdx + 1} Ep $episodeNumber ($lang)"
                            // On encode les données nécessaires pour videoListRequest dans l'URL
                            url = "${animeObj["url"]?.jsonPrimitive?.content}|$sIdx|$eIdx|$lang"
                            episode_number = episodeNumber.toFloatOrNull() ?: 0f
                            date_upload = 0L
                        }
                        episodes.add(sEpisode)
                    }
                }
            }
        }
        return episodes.reversed() // Pour avoir les derniers épisodes en haut
    }

    // --- EXTRACTION DES LIENS VIDEOS ---
    override fun videoListRequest(episode: SEpisode): Request {
        val parts = episode.url.split("|")
        return GET("https://api.franime.fr/api/anime/${parts[0]}", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val jsonData = response.body.string()
        val parts = response.request.url.fragment?.split("|") ?: return emptyList() 
        // Note: fragment ne marche pas ici, on utilise une ruse avec l'URL de l'épisode transmise via l'URL d'Aniyomi
        
        // Pour simplifier l'accès, on repasse par le JSON
        val animeObj = Json.parseToJsonElement(jsonData).jsonObject
        val data = response.request.tag(SEpisode::class.java)?.url?.split("|") ?: return emptyList()
        
        val sIdx = data[1].toInt()
        val eIdx = data[2].toInt()
        val lang = data[3]

        val players = animeObj["saisons"]?.jsonArray?.get(sIdx)
            ?.jsonObject?.get("episodes")?.jsonArray?.get(eIdx)
            ?.jsonObject?.get(lang)?.jsonObject ?: return emptyList()

        val videos = mutableListOf<Video>()
        
        players.forEach { (playerName, playerUrlElement) ->
            val playerUrl = playerUrlElement.jsonPrimitive.content
            
            try {
                when {
                    playerUrl.contains("sibnet") -> videos.addAll(SibnetExtractor(client).videosFromUrl(playerUrl))
                    playerUrl.contains("sendvid") -> videos.addAll(SendvidExtractor(client).videosFromUrl(playerUrl))
                    playerUrl.contains("vidmoly") -> videos.addAll(VidmolyExtractor(client).videosFromUrl(playerUrl))
                }
            } catch (e: Exception) {
                // On ignore les lecteurs qui plantent
            }
        }
        return videos
    }

    // --- RECHERCHE ET AUTRES ---
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("https://api.franime.fr/api/anime/search?q=$query", headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)
    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)
    override fun animeDetailsParse(response: Response) = SAnime.create()
}