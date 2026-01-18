package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema

import android.content.SharedPreferences
import android.text.InputType
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.lib.megamaxmultiserver.MegaMaxMultiServer
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.lib.vidlandextractor.VidLandExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import extensions.utils.addEditTextPreference
import extensions.utils.addListPreference
import extensions.utils.delegate
import extensions.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale

class Tuktukcinema : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "توك توك سينما"

    private val preferences by getPreferencesLazy()

    override val baseUrl
        get() = preferences.customDomain.ifBlank { "https://tuktukhd.com" }

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "$baseUrl/")
    }

    private val intl = Intl(
        language = Locale.getDefault().language,
        baseLanguage = "ar",
        availableLanguages = setOf("ar", "en"),
        classLoader = this::class.java.classLoader!!,
    )

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.Block--Item, div.Small--Box"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/main/", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.select("a").attr("title").let { editTitle(it, details = true) }
            thumbnail_url = element.select("img").attr(
                if (element.ownerDocument()!!.location().contains("?s=")) "abs:src" else "abs:data-src",
            )
            setUrlWithoutDomain(element.select("a").attr("abs:href"))
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "section.allseasonss div.Block--Item"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val url = response.request.url.toString()
        val seasonsDOM = document.select(episodeListSelector())
        return if (seasonsDOM.isNullOrEmpty()) {
            SEpisode.create().apply {
                setUrlWithoutDomain(url)
                name = "مشاهدة"
            }.let(::listOf)
        } else {
            val selectedSeason = document.selectFirst("div#mpbreadcrumbs a span:contains(الموسم)")?.text().orEmpty()
            val seasons = document.select(episodeListSelector())
            seasons.reversed().flatMap { season ->
                val seasonText = season.select("h3").text()
                val seasonUrl = season.selectFirst("a")?.attr("abs:href") ?: return@flatMap emptyList()
                val seasonDoc = if (selectedSeason == seasonText) { document } else {
                    client.newCall(GET(seasonUrl)).execute().asJsoup()
                }
                val seasonNum = if (seasons.size == 1) "1" else seasonText.filter { it.isDigit() }
                seasonDoc.select("section.allepcont a").mapIndexed { index, episode ->
                    val episodeNum = episode.select("div.epnum").text().filter { it.isDigit() }
                        .ifEmpty { (index + 1).toString() }
                    SEpisode.create().apply {
                        setUrlWithoutDomain(episode.attr("abs:href"))
                        name = "$seasonText : الحلقة $episodeNum"
                        episode_number = ("$seasonNum.$episodeNum").toFloat()
                    }
                }
            }
        }
    }

    override fun episodeFromElement(element: Element): SEpisode =
        throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListSelector(): String = "ul li.server--item"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(videoListSelector()).parallelCatchingFlatMapBlocking {
            val url = it.attr("data-link").substringBefore("0REL0Y").reversed()
            extractVideos(Base64.decode(url, Base64.DEFAULT).let(::String), it.text())
        }
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val megaMax by lazy { MegaMaxMultiServer(client, headers) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val vidLandExtractor by lazy { VidLandExtractor(client) }

    private fun extractVideos(
        url: String,
        server: String,
        customQuality: String? = null,
    ): List<Video> {
        return when {
            "iframe" in url -> {
                return megaMax.extractUrls(url).parallelCatchingFlatMapBlocking {
                    extractVideos(it.url, it.name, it.quality)
                }
            }

            "mixdrop" in server -> {
                mixDropExtractor.videosFromUrl(url, "Ar", customQuality ?: " ")
            }

            "dood" in server -> {
                doodExtractor.videosFromUrl(url, customQuality)
            }

            "lulustream" in server -> {
                streamWishExtractor.videosFromUrl(url, server.replaceFirstChar(Char::titlecase))
            }

            "krakenfiles" in server -> {
                val page = client.newCall(GET(url, headers)).execute().asJsoup()
                page.select("source").map {
                    Video(it.attr("src"), "Kraken" + customQuality?.let { q -> ": $q" }.orEmpty(), it.attr("src"))
                }
            }

            "earnvids" in server -> {
                vidLandExtractor.videosFromUrl(url)
            }

            "Vidbom" in server || "Vidshare" in server || "Govid" in server -> {
                vidBomExtractor.videosFromUrl(url, headers)
            }

            else -> emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.quality
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/?s=$query&page=$page", headers)
        } else {
            val filterList = if (filters.isEmpty()) getFilterList() else filters
            val sectionFilter = filterList.filterIsInstance<SectionFilter>().first()
            val genreFilter = filterList.filterIsInstance<GenreFilter>().first()
            val advancedSection = filterList.filterIsInstance<AdvancedSection>().first()
            val advancedGenre = filterList.filterIsInstance<AdvancedGenre>().first()
            val advancedRating = filterList.filterIsInstance<AdvancedRating>().first()
            val url = baseUrl.toHttpUrl().newBuilder().apply {
                if (advancedSection.state != 0 || advancedGenre.state != 0 || advancedRating.state != 0) {
                    addPathSegments("filtering/")
                    if (advancedSection.state != 0) {
                        addQueryParameter("category", advancedSection.toUriPart())
                    }
                    if (advancedGenre.state != 0) {
                        addQueryParameter("genre", advancedGenre.toUriPart())
                    }
                    if (advancedRating.state != 0) {
                        addQueryParameter("mpaa", advancedRating.toUriPart())
                    }
                    addQueryParameter("pagenum", page.toString())
                } else {
                    if (sectionFilter.state != 0) {
                        addPathSegments(sectionFilter.toUriPart())
                    } else if (genreFilter.state != 0) {
                        addPathSegment("genre")
                        addPathSegment(genreFilter.toUriPart())
                    } else {
                        throw Exception("من فضلك اختر قسم او تصنيف")
                    }
                    addQueryParameter("page", page.toString())
                }
            }
            GET(url.toString(), headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            genre = document.select("div.catssection li a").joinToString(", ") { it.text() }
            title = document.select("h1.post-title").text().let(::editTitle)
            author = document.select("ul.RightTaxContent li:contains(دولة) a").text()
            description = document.select("div.story").text().trim()
            status = SAnime.COMPLETED
            thumbnail_url = document.select("div.left div.image img").attr("abs:data-src")
        }
    }

    private fun editTitle(title: String, details: Boolean = false): String {
        REGEX_MOVIE.find(title)?.let { match ->
            val (movieName, type) = match.destructured
            return if (details) "$movieName ($type)".trim() else movieName.trim()
        }

        REGEX_SERIES.find(title)?.let { match ->
            val (seriesName, epNum) = match.destructured
            return when {
                details -> "$seriesName (ep:$epNum)".trim()
                seriesName.contains("الموسم") -> seriesName.substringBefore("الموسم").trim()
                else -> seriesName.trim()
            }
        }

        return title.trim()
    }

    // =============================== Latest ===============================
    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/recent/page/$page/", headers)

    override fun latestUpdatesFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================ Filters =============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header(intl["filter_note"]),
        SectionFilter(intl),
        AnimeFilter.Separator(),
        GenreFilter(intl),
        AnimeFilter.Separator(),
        AnimeFilter.Header(intl["advanced_search"]),
        AdvancedSection(intl),
        AdvancedGenre(intl),
        AdvancedRating(intl),
    )

    private class SectionFilter(intl: Intl) : PairFilter(
        intl["section"],
        arrayOf(
            Pair(intl["choose"], ""),
            Pair(intl["All_Movies"], "category/movies-2/"),
            Pair(intl["Netflix_Movies"], "channel/film-netflix-1/"),
            Pair(intl["Foreign_Movies"], "category/movies-2/افلام-اجنبي/"),
            Pair(intl["Indian_Movies"], "category/movies-2/افلام-هندى/"),
            Pair(intl["Asian_Movies"], "category/movies-2/افلام-اسيوي/"),
            Pair(intl["Anime_Movies"], "category/anime-6/افلام-انمي/"),
            Pair(intl["Turkish_Movies"], "category/movies-2/افلام-تركي/"),
            Pair(intl["Dubbed_Movies"], "category/movies-2/افلام-مدبلجة/"),
            Pair(intl["Latest_Foreign_Series"], "sercat/مسلسلات-اجنبي/"),
            Pair(intl["Latest_Turkish_Series"], "sercat/مسلسلات-تركي/"),
            Pair(intl["Latest_Asian_Series"], "sercat/مسلسلات-أسيوي/"),
            Pair(intl["Netflix_Series"], "channel/series-netflix-2/"),
            Pair(intl["Latest_Anime"], "sercat/قائمة-الانمي/"),
            Pair(intl["Latest_TV_Shows"], "sercat/برامج-تلفزيونية/"),
            Pair(intl["Latest_Indian_Series"], "sercat/مسلسلات-هندي/"),
        ),
    )

    private class GenreFilter(intl: Intl) : PairFilter(
        intl["genre"],
        arrayOf(
            Pair(intl["choose"], ""),
            Pair(intl["Action"], "اكشن"),
            Pair(intl["Adventure"], "مغامرة"),
            Pair(intl["Animation"], "كرتون"),
            Pair(intl["Fantasy"], "فانتازيا"),
            Pair(intl["Sci-Fi"], "خيال-علمي"),
            Pair(intl["Romance"], "رومانسي"),
            Pair(intl["Comedy"], "كوميدي"),
            Pair(intl["Family"], "عائلي"),
            Pair(intl["Drama"], "دراما"),
            Pair(intl["Thriller"], "اثارة"),
            Pair(intl["Mystery"], "غموض"),
            Pair(intl["Crime"], "جريمة"),
            Pair(intl["Horror"], "رعب"),
            Pair(intl["Historical"], "تاريخي"),
            Pair(intl["Documentary"], "وثائقي"),
        ),
    )

    private class AdvancedSection(intl: Intl) : PairFilter(
        intl["section"],
        arrayOf(
            Pair(intl["all"], ""),
            Pair(intl["Foreign_Episodes"], "221258"),
            Pair(intl["Anime_Episodes"], "221271"),
            Pair(intl["Asian_Episodes"], "225978"),
            Pair(intl["Foreign_Movies"], "3"),
            Pair(intl["Turkish_Episodes"], "221316"),
            Pair(intl["Indian_Movies"], "5"),
            Pair(intl["TV_Shows"], "238759"),
            Pair(intl["Asian_Movies"], "6"),
            Pair(intl["Indian_Episodes"], "283152"),
            Pair(intl["Anime_Movies"], "8"),
            Pair(intl["Turkish_Movies"], "7"),
            Pair(intl["Dubbed_Movies"], "1521566"),
            Pair(intl["Wrestling_Shows"], "1277168"),
            Pair(intl["Movies"], "2"),
            Pair(intl["Anime"], "225979"),
        ),
    )

    private class AdvancedGenre(intl: Intl) : PairFilter(
        intl["genre"],
        arrayOf(
            Pair(intl["all"], ""),
            Pair(intl["Drama"], "24"),
            Pair(intl["Comedy"], "180"),
            Pair(intl["Action"], "49"),
            Pair(intl["Crime"], "50"),
            Pair(intl["Romance"], "26"),
            Pair(intl["Thriller"], "51"),
            Pair(intl["Adventure"], "293186"),
            Pair(intl["Anime"], "244998"),
            Pair(intl["Mystery"], "25"),
            Pair(intl["Fantasy"], "308"),
            Pair(intl["Sci-Fi"], "273"),
            Pair(intl["Horror"], "272"),
            Pair(intl["Historical"], "534"),
            Pair(intl["Documentary"], "97467"),
            Pair(intl["Family"], "731"),
        ),
    )

    private class AdvancedRating(intl: Intl) : PairFilter(
        intl["age_rating_filter"],
        arrayOf(
            Pair(intl["all"], ""),
            Pair(intl["above_14"], "240378"),
            Pair(intl["above_17"], "240244"),
            Pair(intl["not_children"], "240660"),
            Pair(intl["tv_13"], "294376"),
            Pair(intl["adult_only"], "240192"),
            Pair(intl["above_7"], "241926"),
            Pair(intl["parent_13"], "293269"),
            Pair(intl["tv_all_ages"], "297197"),
            Pair(intl["all_ages"], "240447"),
            Pair(intl["above_13"], "240363"),
            Pair(intl["under_6"], "241023"),
            Pair("PG-13 - Teens 13 or older", "1530745"),
            Pair("N/A", "1530272"),
            Pair("R - 17+ (violence & profanity)", "1529057"),
            Pair("G - All Ages", "1531434"),
        ),
    )

    open class PairFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =============================== Settings ===============================
    private var SharedPreferences.customDomain by preferences.delegate(PREF_DOMAIN_CUSTOM_KEY, "")
    private var SharedPreferences.quality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = intl["preferred_quality"],
            entries = listOf("1080p", "720p", "480p", "360p", "240p"),
            entryValues = listOf("1080", "720", "480", "360", "240"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addEditTextPreference(
            key = PREF_DOMAIN_CUSTOM_KEY,
            default = "",
            title = intl["custom_domain"],
            dialogMessage = "أدخل المجال المخصص (على سبيل المثال، https://example.com)",
            summary = preferences.customDomain,
            getSummary = { it },
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            validate = { it.isBlank() || (it.toHttpUrlOrNull() != null && !it.endsWith("/")) },
            validationMessage = { "عنوان URL غير صالح أو مشوه أو ينتهي بشرطة مائلة" },
        )
    }

    companion object {
        private const val PREF_DOMAIN_CUSTOM_KEY = "custom_domain"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val REGEX_MOVIE = Regex("""(?:فيلم|عرض)\s(.*\s\d+)\s(\S+)""")
        private val REGEX_SERIES = Regex("""(?:مسلسل|برنامج|انمي)\s(.+)\sالحلقة\s(\d+)""")
    }
}
