package eu.kanade.tachiyomi.animeextension.es.hentaila

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import extensions.utils.getPreferencesLazy
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class Hentaila : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Hentaila"

    override val baseUrl = "https://hentaila.com"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf(
            "StreamWish",
            "Voe",
            "Arc",
            "YourUpload",
            "Mp4Upload",
            "BurstCloud",
            "StreamHideVid",
            "Sendvid",
        )

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMMMM dd, yyyy", Locale.ENGLISH)
        }
    }

    override fun popularAnimeRequest(page: Int) = GET(
        "$baseUrl/catalogo/__data.json?order=popular&page=$page",
        headers,
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animes = getAnimes(response)
        return AnimesPage(animes.first, animes.second)
    }

    override fun latestUpdatesRequest(page: Int) = GET(
        "$baseUrl/catalogo/__data.json?order=latest_released&page=$page",
        headers,
    )

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = getAnimes(response)
        return AnimesPage(animes.first, animes.second)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        if (query.isNotEmpty()) {
            val body = """{"query":"$query"}""".toRequestBody("application/json".toMediaType())
            return POST("$baseUrl/api/search", headers, body = body)
        }

        val urlBuilder = "$baseUrl/catalogo/__data.json?page=$page}"
            .toHttpUrl().newBuilder()

        filterList.forEach { filter ->
            when (filter) {
                is GenreFilter -> urlBuilder.addQueryParameter("genre", filter.toUriPart())
                is OrderFilter -> urlBuilder.addQueryParameter("filter", filter.toUriPart())
                is StatusOngoingFilter -> if (filter.state) urlBuilder.addQueryParameter("status", "emision")
                is StatusCompletedFilter -> if (filter.state) urlBuilder.addQueryParameter("status", "finalizado")
                is UncensoredFilter -> if (filter.state) urlBuilder.addQueryParameter("uncensored", "")
                else -> {}
            }
        }
        return GET(urlBuilder.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.toString().startsWith("$baseUrl/api/search")) {
            val results = response.parseAs<List<HentailaDto>>()
            val animeList = results.map { anime ->
                SAnime.create().apply {
                    title = anime.title
                    url = "/media/${anime.slug}"
                    thumbnail_url = "https://cdn.hentaila.com/covers/${anime.id}.jpg"
                }
            }
            return AnimesPage(animeList, false)
        }

        val animeList = getAnimes(response)
        return AnimesPage(animeList.first, animeList.second)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val statusData = document.select("div.flex.flex-wrap.items-center.text-sm span")
        var currentStatus = SAnime.COMPLETED
        for (data in statusData) {
            when (data.text()) {
                "Finalizado" -> {
                    currentStatus = SAnime.COMPLETED
                    break
                }
                "En emisión" -> {
                    currentStatus = SAnime.ONGOING
                    break
                }
                else -> {}
            }
        }
        return SAnime.create().apply {
            thumbnail_url = document.selectFirst("img.object-cover.w-full.aspect-poster")!!.attr("src")
            title = document.selectFirst(".grid.items-start h1.text-lead")!!.text()
            description = document.select(".entry.text-lead.text-sm p").text()
            genre = document.select(".flex-wrap.items-center .btn.btn-xs.rounded-full:not(.sm\\:w-auto)").joinToString { it.text() }
            status = currentStatus
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val animeId = response.request.url.toString().substringAfter("media/").lowercase()
        val jsoup = response.asJsoup()

        jsoup.select("article.group\\/item")
            .forEach {
                val epNum = it.select("div.bg-line.text-subs span").text()
                val episode = SEpisode.create().apply {
                    episode_number = epNum.toFloat()
                    name = "Episodio $epNum"
                    url = "/media/$animeId/$epNum"
                }
                episodes.add(episode)
            }
        return episodes.reversed()
    }

    private fun getAnimes(response: Response): Pair<List<SAnime>, Boolean> {
        val document = response.parseAs<HentailaJsonDto>()
        for (doc in document.nodes) {
            if (doc?.uses?.searchParams != null) {
                val data = doc.data
                val result = data?.get(0)?.jsonObject
                val results = result?.get("results")?.jsonPrimitive?.intOrNull ?: -1

                val paginationCode = result?.get("pagination")?.jsonPrimitive?.intOrNull ?: -1
                val pagination = data?.get(paginationCode)?.jsonObject

                val currentPageCode = pagination?.get("currentPage")?.jsonPrimitive?.intOrNull ?: -1
                val currentPage = data?.get(currentPageCode)?.jsonPrimitive?.intOrNull ?: -1

                val totalPageCode = pagination?.get("totalPages")?.jsonPrimitive?.intOrNull ?: -1
                val totalPage = data?.get(totalPageCode)?.jsonPrimitive?.intOrNull ?: -1

                val animeIdJson = data?.get(results)?.jsonArray
                val animeIds = animeIdJson?.map { it.jsonPrimitive.int }

                val animes = animeIds?.map { id ->

                    val currentAnimeDetails = data[id].jsonObject

                    val animeIdCode = currentAnimeDetails["id"]?.jsonPrimitive?.intOrNull ?: -1
                    val animeId = data[animeIdCode].jsonPrimitive.content

                    val animeTitleCode =
                        currentAnimeDetails["title"]?.jsonPrimitive?.intOrNull ?: -1
                    val animeTitle = data[animeTitleCode].jsonPrimitive.content

                    val animeSynopsisCode =
                        currentAnimeDetails["synopsis"]?.jsonPrimitive?.intOrNull ?: -1
                    val animeSynopsis = data[animeSynopsisCode].jsonPrimitive.content

                    val animeSlugCode = currentAnimeDetails["slug"]?.jsonPrimitive?.intOrNull ?: -1
                    val animeSlug = data[animeSlugCode].jsonPrimitive.content

                    SAnime.create().apply {
                        title = animeTitle
                        url = "/media/$animeSlug"
                        description = animeSynopsis
                        thumbnail_url = "https://cdn.hentaila.com/covers/$animeId.jpg"
                        update_strategy = AnimeUpdateStrategy.ONLY_FETCH_ONCE
                    }
                }

                val nextPage = currentPage <= totalPage
                if (animes != null) {
                    return Pair(animes, nextPage)
                }
            }
        }
        return Pair(emptyList(), false)
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }

    // private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val burstCloudExtractor by lazy { BurstCloudExtractor(client) }

    // private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client, headers) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }

    private val universalExtractor by lazy { UniversalExtractor(client) }

    override fun videoListRequest(episode: SEpisode): Request {
        val url = "$baseUrl${episode.url}/__data.json"
        return GET(url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.parseAs<HentailaJsonDto>()
        val serverList = mutableListOf<VideoData>()
        for (each in document.nodes) {
            if (each?.uses?.params != null && each.uses.params[0] == "number") {
                val data = each.data
                val result = data?.get(0)?.jsonObject
                val embedsCode = result?.get("embeds")?.jsonPrimitive?.intOrNull ?: -1
                val embedData = data?.get(embedsCode)?.jsonObject
                val subEmbedDataCode = embedData?.get("SUB")?.jsonPrimitive?.intOrNull ?: -1
                val subEmbedVideoArray = data?.get(subEmbedDataCode)?.jsonArray
                val subEmbedVideoArrayList = subEmbedVideoArray?.map {
                    it.jsonPrimitive.intOrNull ?: -1
                }
                if (subEmbedVideoArrayList != null) {
                    for (sourceCode in subEmbedVideoArrayList) {
                        val currentSourceData = data[sourceCode].jsonObject
                        val serverNameCode =
                            currentSourceData["server"]?.jsonPrimitive?.intOrNull ?: -1
                        val serverUrlCode =
                            currentSourceData["url"]?.jsonPrimitive?.intOrNull ?: -1

                        val serverName = data[serverNameCode].jsonPrimitive.content
                        val serverUrl = data[serverUrlCode].jsonPrimitive.content
                        serverList.add(VideoData(serverName, serverUrl))
                    }
                }
            }
        }
        val allVideos = serverList.parallelCatchingFlatMapBlocking { each ->
            when (each.name.lowercase()) {
                "streamwish" -> streamWishExtractor.videosFromUrl(each.url, videoNameGen = { "StreamWish:$it" })
                "voe" -> voeExtractor.videosFromUrl(each.url)
                "arc" -> listOf(Video(each.url.substringAfter("#"), "Arc", each.url.substringAfter("#")))
                "yupi", "yourupload" -> yourUploadExtractor.videoFromUrl(each.url, headers = headers)
                // "mp4upload" -> mp4uploadExtractor.videosFromUrl(each.url, headers = headers)
                "burst" -> burstCloudExtractor.videoFromUrl(each.url, headers = headers)
                // "vidhide", "streamhide", "guccihide", "streamvid" -> streamHideVidExtractor.videosFromUrl(each.url)
                "sendvid" -> sendvidExtractor.videosFromUrl(each.url)
                else -> emptyList()
                // else -> universalExtractor.videosFromUrl(each.url, headers)
            }
        }
        return allVideos
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
        AnimeFilter.Separator(),
        OrderFilter(),
        AnimeFilter.Separator(),
        StatusOngoingFilter(),
        StatusCompletedFilter(),
        AnimeFilter.Separator(),
        UncensoredFilter(),
    )

    private class StatusOngoingFilter : AnimeFilter.CheckBox("En Emision")
    private class StatusCompletedFilter : AnimeFilter.CheckBox("Finalizado")
    private class UncensoredFilter : AnimeFilter.CheckBox("Sin Censura")

    private class OrderFilter : UriPartFilter(
        "Ordenar por",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Populares", "popular"),
            Pair("Recientes", "recent"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "Generos",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("3D", "3d"),
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("Casadas", "casadas"),
            Pair("Chikan", "chikan"),
            Pair("Ecchi", "ecchi"),
            Pair("Enfermeras", "enfermeras"),
            Pair("Futanari", "futanari"),
            Pair("Escolares", "escolares"),
            Pair("Gore", "gore"),
            Pair("Hardcore", "hardcore"),
            Pair("Harem", "harem"),
            Pair("Incesto", "incesto"),
            Pair("Juegos Sexuales", "juegos-sexuales"),
            Pair("Milfs", "milfs"),
            Pair("Maids", "maids"),
            Pair("Netorare", "netorare"),
            Pair("Ninfomania", "ninfomania"),
            Pair("Ninjas", "ninjas"),
            Pair("Orgias", "orgias"),
            Pair("Romance", "romance"),
            Pair("Shota", "shota"),
            Pair("Softcore", "softcore"),
            Pair("Succubus", "succubus"),
            Pair("Teacher", "teacher"),
            Pair("Tentaculos", "tentaculos"),
            Pair("Tetonas", "tetonas"),
            Pair("Vanilla", "vanilla"),
            Pair("Violacion", "violacion"),
            Pair("Virgenes", "virgenes"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Bondage", "bondage"),
            Pair("Elfas", "elfas"),
            Pair("Petit", "petit"),
            Pair("Threesome", "threesome"),
            Pair("Paizuri", "paizuri"),
            Pair("Gal", "gal"),
            Pair("Oyakodon", "oyakodon"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
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
}
