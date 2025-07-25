package eu.kanade.tachiyomi.animeextension.en.uniquestreamanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parallelFlatMap
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class UniqueStreamAnime : AnimeHttpSource() {

    override val name = "UniqueStream (Anime)"

    override val lang = "en"

    override val baseUrl = "https://anime.uniquestream.net"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/api/v1/videos/popular?page=$page&limit=10&type=all")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = response.parseAs<List<AnimeDto>>()
            .map { it.toSAnime() }

        return AnimesPage(animeList, animeList.size >= 10)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/api/v1/videos/new?page=$page&limit=10&type=all")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================

    // https://anime.uniquestream.net/api/v1/search?page=1&query=leveling&t=all&limit=6
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegments("api/v1")
            addPathSegment("search")
            addQueryParameter("page", page.toString())
            addQueryParameter("t", "all")
            addQueryParameter("limit", "6")
            addQueryParameter("query", query)
        }
        return GET(url.build())
//        val cleanQuery = query.replace(" ", "+").lowercase()
//
//        val filterList = if (filters.isEmpty()) getFilterList() else filters
//        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
//        val recentFilter = filterList.find { it is RecentFilter } as RecentFilter
//        val yearFilter = filterList.find { it is YearFilter } as YearFilter
//
//        return when {
//            query.isNotBlank() -> GET("$baseUrl/${page.toPage()}?s=$cleanQuery", headers = headers)
//            genreFilter.state != 0 -> GET("$baseUrl/genre/${genreFilter.toUriPart()}/${page.toPage()}/", headers = headers)
//            recentFilter.state != 0 -> GET("$baseUrl/${recentFilter.toUriPart()}/${page.toPage()}", headers = headers)
//            yearFilter.state != 0 -> GET("$baseUrl/release/${yearFilter.toUriPart()}/${page.toPage()}", headers = headers)
//            else -> popularAnimeRequest(page)
//        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return AnimesPage(emptyList(), false)
    }

    // ============================== Details ===============================

    override fun getAnimeUrl(anime: SAnime): String {
        return baseUrl + anime.url + "/${anime.title.titleToUri()}"
    }

    private fun String.titleToUri(): String {
        return this.replace(" ", "-")
            .replace(Regex("[^a-z0-9-]"), "")
    }

    override fun animeDetailsRequest(anime: SAnime) =
        GET(baseUrl + "/api/v1" + anime.url)

    override fun animeDetailsParse(response: Response): SAnime {
        return response.parseAs<AnimeDetailsDto>().toSAnime()
    }

    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val seasons = response.parseAs<AnimeDetailsDto>().seasons
        return seasons.filter { it.episodeCount > 0 }
            .parallelFlatMapBlocking { season ->
                val pages = season.episodeCount / 20 + 1
                (1..pages).parallelFlatMap { page ->
                    client.newCall(
                        GET("$baseUrl/api/v1/season/${season.contentId}/episodes?page=$page&limit=20"),
                    ).execute().use { episodesResponse ->
                        episodesResponse.parseAs<List<EpisodeDto>>().map { it.toEpisode(season.displayNumber) }
                    }
                }
            }.reversed()
    }

    // ============================== Filters ===============================

//    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
//        AnimeFilter.Header("Text search ignores filters"),
//        GenreFilter(),
//        RecentFilter(),
//        YearFilter(),
//    )
//
//    private class GenreFilter : UriPartFilter(
//        "Genres",
//        arrayOf(
//            Pair("<select>", ""),
//            Pair("Action", "action"),
//            Pair("Action & Adventure", "action-adventure"),
//            Pair("Adventure", "adventure"),
//            Pair("Animation", "animation"),
//            Pair("Anime", "anime"),
//            Pair("Asian", "asian"),
//            Pair("Bollywood", "bollywood"),
//            Pair("Comedy", "comedy"),
//            Pair("Crime", "crime"),
//            Pair("Documentary", "documentary"),
//            Pair("Drama", "drama"),
//            Pair("Family", "family"),
//            Pair("Fantasy", "fantasy"),
//            Pair("Foreign", "foreign"),
//            Pair("History", "history"),
//            Pair("Hollywood", "hollywood"),
//            Pair("Horror", "horror"),
//            Pair("Kids", "kids"),
//            Pair("Korean", "korean"),
//            Pair("Malay", "malay"),
//            Pair("Malayalam", "malayalam"),
//            Pair("Military", "military"),
//            Pair("Music", "music"),
//            Pair("Mystery", "mystery"),
//            Pair("News", "news"),
//            Pair("Reality", "reality"),
//            Pair("Romance", "romance"),
//            Pair("Sci-Fi & Fantasy", "sci-fi-fantasy"),
//            Pair("Science Fiction", "science-fiction"),
//            Pair("Soap", "soap"),
//            Pair("Talk", "talk"),
//            Pair("Tamil", "tamil"),
//            Pair("Telugu", "telugu"),
//            Pair("Thriller", "thriller"),
//            Pair("TV Movie", "tv-movie"),
//            Pair("War", "war"),
//            Pair("War & Politics", "war-politics"),
//            Pair("Western", "western"),
//        ),
//    )
//
//    private class RecentFilter : UriPartFilter(
//        "Recent",
//        arrayOf(
//            Pair("<select>", ""),
//            Pair("Recent TV Shows", "tvshows"),
//            Pair("Recent Movies", "movies"),
//        ),
//    )
//
//    private class YearFilter : UriPartFilter(
//        "Release Year",
//        arrayOf(
//            Pair("<select>", ""),
//            Pair("2024", "2024"),
//            Pair("2023", "2023"),
//            Pair("2022", "2022"),
//            Pair("2021", "2021"),
//            Pair("2020", "2020"),
//            Pair("2019", "2019"),
//            Pair("2018", "2018"),
//            Pair("2017", "2017"),
//            Pair("2016", "2016"),
//            Pair("2015", "2015"),
//            Pair("2014", "2014"),
//            Pair("2013", "2013"),
//            Pair("2012", "2012"),
//            Pair("2011", "2011"),
//            Pair("2010", "2010"),
//            Pair("2009", "2009"),
//            Pair("2008", "2008"),
//            Pair("2007", "2007"),
//            Pair("2006", "2006"),
//            Pair("2005", "2005"),
//            Pair("2004", "2004"),
//            Pair("2003", "2003"),
//            Pair("2002", "2002"),
//            Pair("2001", "2001"),
//            Pair("2000", "2000"),
//            Pair("1999", "1999"),
//            Pair("1998", "1998"),
//            Pair("1997", "1997"),
//            Pair("1996", "1996"),
//            Pair("1995", "1995"),
//            Pair("1994", "1994"),
//            Pair("1993", "1993"),
//            Pair("1992", "1992"),
//            Pair("1991", "1991"),
//            Pair("1990", "1990"),
//            Pair("1989", "1989"),
//            Pair("1988", "1988"),
//            Pair("1987", "1987"),
//            Pair("1986", "1986"),
//            Pair("1985", "1985"),
//            Pair("1984", "1984"),
//            Pair("1983", "1983"),
//            Pair("1982", "1982"),
//            Pair("1981", "1981"),
//            Pair("1980", "1980"),
//            Pair("1979", "1979"),
//            Pair("1978", "1978"),
//            Pair("1977", "1977"),
//            Pair("1976", "1976"),
//            Pair("1975", "1975"),
//            Pair("1974", "1974"),
//        ),
//    )
//
//    override val fetchGenres = false

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val contentId = episode.url
            .substringAfter('/')
            .substringAfter('/')
            .substringBefore('/')
        val playlistDto = client.newCall(
            GET("$baseUrl/api/v1/episode/$contentId/media/hls/ja-JP"),
        ).execute().use { response ->
            response.parseAs<PlaylistDto>()
        }

        val playlist = listOf(playlistDto.hls) + playlistDto.versions.hls
        return playlist.flatMap {
            val audio = it.locale.substringBefore('-').uppercase()
            val mainVideo = playlistUtils.extractFromHls(
                playlistUrl = it.playlist,
                videoNameGen = { quality -> "Audio: $audio - $quality" },
            )
            val subVideos = it.hardSubs
                ?.map { hardSub ->
                    val sub = hardSub.locale.substringBefore('-').uppercase()
                    playlistUtils.extractFromHls(
                        playlistUrl = hardSub.playlist,
                        videoNameGen = { quality -> "Audio: $audio - Hardsub: $sub - $quality" },
                    )
                }
            listOf(mainVideo) + (subVideos ?: emptyList())
        }.flatten()
    }

    // ============================== Settings ==============================

    val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "240p")
    val prefQualityEntries = prefQualityValues
}
