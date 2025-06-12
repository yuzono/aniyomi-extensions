package eu.kanade.tachiyomi.animeextension.en.anilist

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.anilist.AniListAnimeDetailsResponse
import eu.kanade.tachiyomi.multisrc.anilist.AniListAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class AniList : AniListAnimeHttpSource() {

    override val baseUrl = "https://anilist.co"

    override val networkBuilder = super.networkBuilder
        .rateLimitHost("https://api.jikan.moe".toHttpUrl(), 1)

    private val mappings by lazy {
        client.newCall(
            GET("https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-mini.json", headers),
        ).execute().parseAs<List<Mapping>>()
    }

    /* ================================= AniList configurations ================================= */

    override fun mapAnimeDetailUrl(animeId: Int): String {
        return animeId.toString()
    }

    override fun mapAnimeId(animeDetailUrl: String): Int {
        return animeDetailUrl.toIntOrNull() ?: throw Exception("Invalid AniList anime ID: $animeDetailUrl")
    }

    // =========================== Anime Details ============================

    override fun getAnimeUrl(anime: SAnime): String {
        return "$baseUrl/anime/${anime.url}"
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val currentTime = System.currentTimeMillis() / 1000L
        val lastRefresh = lastRefreshed[anime.url] ?: 0L

        val newAnime = if (currentTime - lastRefresh < refreshInterval) {
            anime.apply {
                thumbnail_url = coverList[coverIndex]
                coverIndex = (coverIndex + 1) % coverList.size
            }
        } else {
            super.getAnimeDetails(anime)
        }
        lastRefreshed[anime.url] = currentTime
        return newAnime
    }

    private var coverList = emptyList<String>()
    private var coverIndex = 0
    private var currentAnime = ""
    private val lastRefreshed = mutableMapOf<String, Long>()
    private val episodeListMap = mutableMapOf<String, List<SEpisode>>()
    private val refreshInterval = 15

    private val coverProviders by lazy { CoverProviders(client, headers) }

    override fun animeDetailsParse(response: Response): SAnime {
        val animeData = response.parseAs<AniListAnimeDetailsResponse>().data.media
        val anime = animeData.toSAnime()

        if (currentAnime != anime.url) {
            currentAnime = ""
            val type = if (animeData.format == "MOVIE") "movies" else "tv"

            val data = mappings.firstOrNull {
                it.anilistId == anime.url.toInt()
            }
            val malId = data?.malId?.toString()
            val tvdbId = data?.thetvdbId?.toString()

            coverList = buildList {
                add(anime.thumbnail_url ?: "")
                malId?.let { addAll(coverProviders.getMALCovers(malId)) }
                tvdbId?.let { addAll(coverProviders.getFanartCovers(tvdbId, type)) }
            }.filter { it.isNotEmpty() }

            currentAnime = anime.url
            coverIndex = 0
        }

        return anime
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val currentTime = System.currentTimeMillis() / 1000L
        val lastRefresh = lastRefreshed[anime.url] ?: 0L

        val episodeList = if (currentTime - lastRefresh < refreshInterval) {
            episodeListMap[anime.url] ?: emptyList()
        } else {
            super.getEpisodeList(anime)
        }

        episodeListMap[anime.url] = episodeList
        return episodeList
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val variablesObject = buildJsonObject {
            put("id", anime.url.toInt())
            put("type", "ANIME")
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getMalIdQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<AnilistToMalResponse>().data.media
        if (data.status == "NOT_YET_RELEASED") {
            return emptyList()
        }

        val malId = data.idMal
        val anilistId = data.id

        val episodeData = client.newCall(anilistEpisodeRequest(anilistId)).execute()
            .parseAs<AniListEpisodeResponse>().data.media
        val episodeCount = episodeData.nextAiringEpisode?.episode?.minus(1)
            ?: episodeData.episodes ?: 0

        if (malId != null) {
            val episodeList = try {
                getFromMal(malId, episodeCount)
            } catch (e: Exception) {
                Log.e("Anilist-Ext", "Failed to get episodes from mal: ${e.message}")
                null
            }

            if (episodeList != null) {
                return episodeList
            }
        }

        return List(episodeCount) {
            val epNumber = it + 1

            SEpisode.create().apply {
                name = "Episode $epNumber"
                episode_number = epNumber.toFloat()
                url = "$epNumber"
            }
        }.reversed()
    }

    private fun anilistEpisodeRequest(anilistId: Int): Request {
        val variablesObject = buildJsonObject {
            put("id", anilistId)
            put("type", "ANIME")
        }
        val variables = json.encodeToString(variablesObject)

        val body = FormBody.Builder().apply {
            add("query", getEpisodeQuery())
            add("variables", variables)
        }.build()

        return POST(apiUrl, body = body)
    }

    private fun getSingleEpisodeFromMal(malId: Int): List<SEpisode> {
        val animeData = client.newCall(
            GET("https://api.jikan.moe/v4/anime/$malId", headers),
        ).execute().parseAs<JikanAnimeDto>().data

        return listOf(
            SEpisode.create().apply {
                name = "Episode 1"
                episode_number = 1F
                date_upload = parseDate(animeData.aired.from)
                url = "1"
            },
        )
    }

    private fun getFromMal(malId: Int, episodeCount: Int): List<SEpisode> {
        val markFillers = preferences.markFiller
        val episodeList = mutableListOf<SEpisode>()

        var hasNextPage = true
        var page = 1
        while (hasNextPage) {
            val data = client.newCall(
                GET("https://api.jikan.moe/v4/anime/$malId/episodes?page=$page", headers),
            ).execute().parseAs<JikanEpisodesDto>()

            if (data.pagination.lastPage == 1 && data.data.isEmpty()) {
                return getSingleEpisodeFromMal(malId)
            }

            episodeList.addAll(
                data.data.map { ep ->
                    val airedOn = ep.aired?.let { parseDate(it) } ?: -1L
                    val fullName = ep.title?.let { "Ep. ${ep.number} - $it" } ?: "Episode ${ep.number}"
                    val scanlatorText = if (markFillers && ep.filler) "Filler episode" else null

                    SEpisode.create().apply {
                        date_upload = airedOn
                        episode_number = ep.number.toFloat()
                        url = ep.number.toString()
                        name = SANITY_REGEX.replace(fullName) { m -> m.groupValues[1] }
                        scanlator = scanlatorText
                    }
                },
            )

            hasNextPage = data.pagination.hasNextPage
            page++
        }

        (episodeList.size + 1..episodeCount).forEach {
            episodeList.add(
                SEpisode.create().apply {
                    episode_number = it.toFloat()
                    url = "$it"
                    name = "Ep. $it"
                },
            )
        }

        return episodeList.filter { it.episode_number <= episodeCount }.sortedBy { -it.episode_number }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request =
        throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> =
        throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    companion object {
        private val SANITY_REGEX by lazy { Regex("""^Ep. \d+ - (Episode \d+)${'$'}""") }

        private const val MARK_FILLERS_KEY = "preferred_mark_fillers"
        private const val MARK_FILLERS_DEFAULT = true
    }

    private val SharedPreferences.markFiller
        get() = getBoolean(MARK_FILLERS_KEY, MARK_FILLERS_DEFAULT)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        addAdultPreferences(screen)

        SwitchPreferenceCompat(screen.context).apply {
            key = MARK_FILLERS_KEY
            title = "Mark filler episodes"
            setDefaultValue(MARK_FILLERS_DEFAULT)
        }.also(screen::addPreference)
    }
}
