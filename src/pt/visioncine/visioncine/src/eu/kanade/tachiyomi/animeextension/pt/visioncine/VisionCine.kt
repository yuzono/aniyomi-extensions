package eu.kanade.tachiyomi.animeextension.pt.visioncine

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class VisionCine : AnimeHttpSource() {

    override val name = "VisionCine"

    override val id = 1234567890L

    override var baseUrl = "https://www.visioncine.stream"

    override val lang = "pt"

    override val supportsLatest = true

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/")
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(popularAnimeRequest(page)).execute()
        return popularAnimeParse(response)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body!!.string())
        val animes = mutableListOf<SAnime>()

        val section = findSection(document, "MAIS VISTO DO DIA")
        section?.select(".swiper-slide.item.poster")?.forEach { item ->
            val anime = parseAnimeItem(item)
            animes.add(anime)
        }

        return AnimesPage(animes, false)
    }

    private fun findSection(document: org.jsoup.nodes.Document, sectionTitle: String): org.jsoup.nodes.Element? {
        val selector = ".topList:has(h5:contains($sectionTitle)) + section.listContent .swiper-wrapper.items"
        return document.selectFirst(selector)
    }

    private fun parseAnimeItem(item: org.jsoup.nodes.Element): SAnime {
        val title = item.selectFirst(".info.movie h6")?.text() ?: "Desconhecido"
        val imageStyle = item.selectFirst(".content")?.attr("style") ?: ""
        val thumbnailUrl = extractImageUrl(imageStyle)
        val url = item.selectFirst("a")?.attr("href") ?: "$baseUrl/"

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = thumbnailUrl
            this.url = url
        }
    }

    private fun extractImageUrl(style: String): String {
        val regex = """background-image:\s*url\(['"]?(.*?)['"]?\)""".toRegex()
        val match = regex.find(style)
        return match?.groupValues?.get(1) ?: ""
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/")
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(latestUpdatesRequest(page)).execute()
        return latestUpdatesParse(response)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body!!.string())
        val animes = mutableListOf<SAnime>()

        val section = findSection(document, "LANÇAMENTOS")
        section?.select(".swiper-slide.item.poster")?.forEach { item ->
            val anime = parseAnimeItem(item)
            animes.add(anime)
        }

        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // Verificar se há um gênero selecionado
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val genreValue = genreFilter?.genreValue() ?: ""

        return when {
            // Se um gênero foi selecionado (não é "Todos")
            genreValue.isNotEmpty() -> GET("$baseUrl/genre/$genreValue")
            // Se há uma busca por texto
            query.isNotEmpty() -> GET("$baseUrl/search.php?q=$query")
            // Padrão: retornar página inicial
            else -> GET("$baseUrl/")
        }
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val response = client.newCall(searchAnimeRequest(page, query, filters)).execute()
        return searchAnimeParse(response)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body!!.string())
        val animes = mutableListOf<SAnime>()

        // Tentar encontrar itens de busca em diferentes formatos
        val items = document.select(".listContent .swiper-slide.item.poster, .row .col-12 .item, .content-left .item, .search-item")

        if (items.isEmpty()) {
            // Tentar formato alternativo de grid de resultados
            document.select(".row .col-6, .row .col-4, .col-2").forEach { col ->
                val title = col.selectFirst("h6, .h6, .title, a[title]")?.text()
                    ?: col.selectFirst("img")?.attr("alt")
                    ?: return@forEach
                val imageStyle = col.selectFirst("[style*='background-image']")?.attr("style")
                    ?: col.selectFirst(".content, .poster, .thumb")?.attr("style")
                    ?: ""
                val thumbnailUrl = extractImageUrl(imageStyle)
                val url = col.selectFirst("a[href*='/watch/']")?.attr("href")
                    ?: col.selectFirst("a")?.attr("href")
                    ?: return@forEach

                if (thumbnailUrl.isNotEmpty() && url.contains("/watch/")) {
                    animes.add(
                        SAnime.create().apply {
                            this.title = title
                            thumbnail_url = thumbnailUrl
                            this.url = url
                        },
                    )
                }
            }
        } else {
            items.forEach { item ->
                val anime = parseAnimeItem(item)
                if (!anime.thumbnail_url.isNullOrEmpty()) {
                    animes.add(anime)
                }
            }
        }

        return AnimesPage(animes, false)
    }

    // ============================== FILTERS ===============================

    class GenreFilter(
        name: String,
        private val genrePairs: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, genrePairs.map { it.second }.toTypedArray()) {
        fun genreValue(): String {
            return if (state in genrePairs.indices) genrePairs[state].first else ""
        }
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(getGenreFilter())
    }

    private fun getGenreFilter(): GenreFilter {
        val genreOptions = listOf(
            Pair("", "Todos"),
            Pair("netflix", "NETFLIX"),
            Pair("discovery", "Discovery+"),
            Pair("4k", "4K"),
            Pair("amazon", "AMAZON"),
            Pair("globoplay", "GloboPlay"),
            Pair("disney", "Disney"),
            Pair("telecine", "TeleCine"),
            Pair("hbo", "HBO"),
            Pair("marvel", "Marvel"),
            Pair("dc", "DC"),
            Pair("novelas", "Novelas"),
            Pair("Apple Tv", "Apple TV"),
            Pair("acao", "Ação"),
            Pair("aventura", "Aventura"),
            Pair("drama", "Drama"),
            Pair("doramas", "Doramas"),
            Pair("suspense", "Suspense"),
            Pair("terror", "Terror"),
            Pair("comedia", "Comédia"),
            Pair("romance", "Romance"),
            Pair("animes", "Animes"),
            Pair("luta", "Luta"),
            Pair("fantasia", "Fantasia"),
            Pair("crime", "Crime"),
            Pair("ficcao", "Ficção"),
            Pair("brasileiro", "Brasileiro"),
            Pair("família", "Família"),
            Pair("musica", "Música"),
            Pair("religiao", "Religião"),
            Pair("historia", "História"),
            Pair("lgbtq", "LGBTQ+"),
            Pair("desenhos", "Desenhos"),
            Pair("guerra", "Guerra"),
            Pair("mystery", "Mystery"),
            Pair("sci-fi-fantasy", "Sci-Fi & Fantasy"),
            Pair("animation", "Animation"),
            Pair("comedy", "Comedy"),
            Pair("fantasy", "Fantasy"),
            Pair("family", "Family"),
            Pair("adventure", "Adventure"),
            Pair("horror", "Horror"),
            Pair("science-fiction", "Science Fiction"),
            Pair("thriller", "Thriller"),
            Pair("action-adventure", "Action & Adventure"),
            Pair("music", "Music"),
            Pair("history", "History"),
            Pair("kids", "Kids"),
            Pair("western", "Western"),
            Pair("documentary", "Documentary"),
            Pair("documentario", "Documentário"),
            Pair("reality", "Reality"),
            Pair("tv-movie", "TV Movie"),
            Pair("faroeste", "Faroeste"),
            Pair("animação", "Animação"),
            Pair("mistério", "Mistério"),
            Pair("ficção-científica", "Ficção científica"),
            Pair("cinema-tv", "Cinema TV"),
        )
        return GenreFilter("Gênero", genreOptions)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET(anime.url)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(animeDetailsRequest(anime)).execute()
        return animeDetailsParse(response)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body!!.string())

        val title = document.selectFirst("h1.fw-bolder")?.text() ?: ""
        val originalTitle = document.selectFirst("h5.fw-normal em")?.text() ?: ""
        val thumbnailStyle = document.selectFirst(".infoPoster .poster")?.attr("style") ?: ""
        val thumbnailUrl = extractImageUrl(thumbnailStyle)
        val synopsis = document.selectFirst("p.small.linefive")?.text() ?: ""

        // Extrair duração/temporada (primeiro span que contém "Min" ou "Temporadas")
        val durationOrSeasons = document.select("p.log span").firstOrNull {
            it.text().contains("Min", ignoreCase = true) || it.text().contains("Temporadas", ignoreCase = true)
        }?.text() ?: ""

        // Extrair ano (segundo span com 4 dígitos ou busca direta)
        val year = document.select("p.log span").firstOrNull {
            it.text().matches(Regex("^20\\d{2}$")) || it.text().matches(Regex("^19\\d{2}$"))
        }?.text() ?: ""

        // Extrair classificação
        val rating = document.selectFirst("p.log em.classification")?.text() ?: ""

        // Extrair qualidade
        val quality = document.select("p.log span").firstOrNull {
            it.text() in listOf("HD", "FULL HD", "4K", "SD")
        }?.text() ?: ""

        // Extrair IMDb
        val imdb = document.select("p.log span").firstOrNull {
            it.text().contains("IMDb")
        }?.text() ?: ""

        // Extrair visualizações (busca o icon e pega o texto do b adjacente)
        val views = document.selectFirst("p.log span i.far.fa-eye")?.parent()?.let {
            it.selectFirst("b")?.text() ?: ""
        } ?: ""

        // Extrair gêneros
        val genreSpans = document.select(".producerInfo .lineone span span")
        val genres = genreSpans.map { it.text() }
            .filter { it.isNotBlank() && !it.matches(Regex("\\d+")) }
            .joinToString(", ")

        // Construir descrição completa
        val description = buildString {
            if (synopsis.isNotBlank()) {
                append(synopsis)
                append("\n\n")
            }
            if (durationOrSeasons.isNotBlank()) {
                append(durationOrSeasons)
                append("\n")
            }
            if (genres.isNotBlank()) {
                append("Gênero: $genres")
                append("\n")
            }
            if (year.isNotBlank()) {
                append("Ano: $year")
                append("\n")
            }
            if (rating.isNotBlank()) {
                append("Classificação: $rating anos")
                append("\n")
            }
            if (quality.isNotBlank()) {
                append("Qualidade: $quality")
                append("\n")
            }
            if (imdb.isNotBlank()) {
                append(imdb)
                append("\n")
            }
            if (views.isNotBlank()) {
                append("Visualizações: $views")
            }
        }

        return SAnime.create().apply {
            this.title = title
            this.author = if (originalTitle.isNotBlank()) originalTitle else title
            this.artist = ""
            this.description = description.trim()
            this.genre = genres
            this.status = SAnime.UNKNOWN
            this.thumbnail_url = thumbnailUrl
            this.url = response.request.url.toString()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(anime.url)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(episodeListRequest(anime)).execute()
        val document = Jsoup.parse(response.body!!.string())
        val episodes = mutableListOf<SEpisode>()

        // Verificar se é uma série (tem episódios) ou um filme (apenas 1 episódio)
        val episodesContainer = document.selectFirst("#episodes-view")

        if (episodesContainer != null) {
            // É uma série
            val seasonOptions = document.select("#seasons-view option")

            // Carregar episódios da temporada visível
            episodesContainer.select(".ep").forEach { ep ->
                val epNumber = ep.selectFirst("p[number]")?.text()?.toIntOrNull() ?: 0
                val epTitle = ep.selectFirst(".info h5")?.text() ?: "Episódio $epNumber"
                val epUrl = ep.selectFirst(".buttons a")?.attr("href") ?: ""

                if (epUrl.isNotEmpty()) {
                    episodes.add(
                        SEpisode.create().apply {
                            this.episode_number = epNumber.toFloat()
                            this.name = epTitle
                            this.url = epUrl
                        },
                    )
                }
            }

            // Carregar outras temporadas em paralelo
            coroutineScope {
                val otherSeasons = seasonOptions.drop(1).filter { opt ->
                    opt.attr("value").toIntOrNull()?.let { it > 0 } == true
                }

                val results = otherSeasons.map { option ->
                    async {
                        val seasonId = option.attr("value").toIntOrNull()!!
                        loadSeasonEpisodes(seasonId)
                    }
                }.awaitAll()

                results.forEach { epList -> episodes.addAll(epList) }
            }
        } else {
            // É um filme
            val movieUrl = document.selectFirst(".buttons a[href*='playcnvs.stream/m/']")?.attr("href")
                ?: document.selectFirst("a[data-tippy-content*='Assistir']")?.attr("href")
                ?: response.request.url.toString()

            if (movieUrl.contains("playcnvs.stream") || movieUrl.contains("watch")) {
                episodes.add(
                    SEpisode.create().apply {
                        this.episode_number = 1f
                        this.name = "Filme"
                        this.url = movieUrl
                    },
                )
            }
        }

        return episodes.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException("Use getEpisodeList instead")
    }

    private fun loadSeasonEpisodes(seasonId: Int): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        try {
            val timestamp = System.currentTimeMillis()
            val url = "$baseUrl/ajax/episodes.php?season=$seasonId&page=1&_=$timestamp"

            val request = okhttp3.Request.Builder()
                .url(url)
                .header("Accept", "*/*")
                .header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$baseUrl/")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val html = response.body?.string()
                if (!html.isNullOrEmpty() && !html.contains("Just a moment")) {
                    val epDocument = Jsoup.parse(html)
                    epDocument.select(".ep").forEach { ep ->
                        val epNumber = ep.selectFirst("p[number]")?.text()?.toIntOrNull() ?: 0
                        val epTitle = ep.selectFirst(".info h5")?.text() ?: "Episódio $epNumber"
                        val epUrl = ep.selectFirst(".buttons a")?.attr("href") ?: ""

                        if (epUrl.isNotEmpty()) {
                            episodes.add(
                                SEpisode.create().apply {
                                    this.episode_number = epNumber.toFloat()
                                    this.name = epTitle
                                    this.url = epUrl
                                },
                            )
                        }
                    }
                }
            }
            response.close()
        } catch (e: Exception) {
            // Falha silenciosa
        }

        return episodes
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(videoListRequest(episode)).execute()
        return videoListParse(response)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.body!!.string())
        val videos = mutableListOf<Video>()

        try {
            // Extrair servidores/qualidades disponíveis do dropdown
            val serverOptions = document.select(".sources-dropdown .dropdown-menu .dropdown-item")

            if (serverOptions.isNotEmpty()) {
                // Suportar initializePlayer e initializePlayerWithSubtitle com aspas duplas ou simples
                val videoUrlPattern = """initializePlayer(?:WithSubtitle)?\(['"]([^'"]+)['"]""".toRegex()

                // Múltiplos servidores disponíveis
                for (serverOption in serverOptions) {
                    val serverUrl = serverOption.attr("href")
                    val serverName = serverOption.ownText().trim()
                    val qualityLabel = serverOption.selectFirst(".badge")?.text() ?: ""
                    val displayName = if (qualityLabel.isNotEmpty()) "$serverName - $qualityLabel" else serverName

                    if (serverUrl.isNotBlank() && serverUrl != response.request.url.toString()) {
                        // Para cada servidor adicional, tentar obter link direto
                        try {
                            val serverRequest = GET(serverUrl)
                            val serverResponse = client.newCall(serverRequest).execute()
                            val serverDocument = Jsoup.parse(serverResponse.body!!.string())

                            // Extrair link direto do script initializePlayer
                            val serverScriptText = serverDocument.html()
                            val serverMatch = videoUrlPattern.find(serverScriptText)

                            if (serverMatch != null) {
                                val serverDirectVideoUrl = serverMatch.groupValues[1]
                                val video = Video(serverDirectVideoUrl, displayName, serverDirectVideoUrl)
                                videos.add(video)
                            } else {
                                // Se não conseguir extrair link direto, usar URL do servidor
                                val video = Video(serverUrl, displayName, serverUrl)
                                videos.add(video)
                            }
                        } catch (e: Exception) {
                            // Se falhar ao acessar servidor, adicionar URL como fallback
                            val video = Video(serverUrl, displayName, serverUrl)
                            videos.add(video)
                        }
                    }
                }
            }

            // Se não encontrou vídeos ainda, tentar métodos alternativos
            if (videos.isEmpty()) {
                // Tentar encontrar qualquer link de player
                val playButtons = document.select("a[href*='playcnvs.stream']")
                for (button in playButtons) {
                    val buttonUrl = button.attr("href")
                    val buttonText = button.text().ifEmpty { "Player" }
                    val video = Video(buttonUrl, buttonText, buttonUrl)
                    videos.add(video)
                }

                // Tentar iframes
                val iframes = document.select("iframe[src]")
                for (iframe in iframes) {
                    val iframeSrc = iframe.attr("src")
                    if (iframeSrc.contains("stream") || iframeSrc.contains("video") || iframeSrc.contains("player")) {
                        val video = Video(iframeSrc, "iFrame Player", iframeSrc)
                        videos.add(video)
                    }
                }

                // Última opção: usar a URL atual
                if (videos.isEmpty()) {
                    val fallbackUrl = response.request.url.toString()
                    val video = Video(fallbackUrl, "Player Web", fallbackUrl)
                    videos.add(video)
                }
            }
        } catch (e: Exception) {
            // Em caso de erro, retornar pelo menos um link básico
            val fallbackUrl = response.request.url.toString()
            val video = Video(fallbackUrl, "Player Web", fallbackUrl)
            videos.add(video)
        }

        return videos
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
    }
}
