package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema

import android.content.SharedPreferences
import android.text.InputType
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
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
import java.util.Base64

class Tuktukcinema : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "توك توك سينما"

    private val preferences by getPreferencesLazy()

    override val baseUrl
        get() = preferences.customDomain.ifBlank { "https://www.tuktukcinma.com" }

    override val lang = "ar"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "$baseUrl/")
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.Block--Item, div.Small--Box"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/main/", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.select("a").attr("title").let { editTitle(it, true) }
            thumbnail_url = element.select("img").attr(
                if (element.ownerDocument()!!.location().contains("?s=")) "src" else "data-src",
            )
            setUrlWithoutDomain(element.select("a").attr("href"))
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
                val seasonUrl = season.selectFirst("a")?.attr("href") ?: return@flatMap emptyList()
                val seasonDoc = if (selectedSeason == seasonText) { document } else {
                    client.newCall(GET(seasonUrl)).execute().asJsoup()
                }
                val seasonNum = if (seasons.size == 1) "1" else seasonText.filter { it.isDigit() }
                seasonDoc.select("section.allepcont a").mapIndexed { index, episode ->
                    val episodeNum = episode.select("div.epnum").text().filter { it.isDigit() }.ifEmpty { (index + 1).toString() }
                    SEpisode.create().apply {
                        setUrlWithoutDomain(episode.attr("href"))
                        name = "$seasonText : الحلقة " + episodeNum
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
            extractVideos(String(Base64.getDecoder().decode(url)), it.text())
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
                mixDropExtractor.videosFromUrl(url, "Ar", customQuality ?: "")
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
                    Video(it.attr("src"), "Kraken" + (customQuality?.let { q -> ": $q" } ?: ""), it.attr("src"))
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
            val url = baseUrl.toHttpUrl().newBuilder()
            if (sectionFilter.state != 0) {
                url.addPathSegment(sectionFilter.toUriPart())
            } else if (genreFilter.state != 0) {
                url.addPathSegment("genre")
                url.addPathSegment(genreFilter.toUriPart())
            } else {
                throw Exception("من فضلك اختر قسم او تصنيف")
            }
            url.addQueryParameter("page", page.toString())
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
            thumbnail_url = document.select("div.left div.image img").attr("data-src")
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
        AnimeFilter.Header("يمكنك تصفح اقسام الموقع اذا كان البحث فارغ"),
        SectionFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("التصنيفات تعمل اذا كان 'اقسام الموقع' على 'اختر' فقط"),
        GenreFilter(),
    )

    private class SectionFilter : PairFilter(
        "اقسام الموقع",
        arrayOf(
            Pair("اختر", ""),
            Pair("كل الافلام", "category/movies-33/"),
            Pair("افلام اجنبى", "category/movies-33/افلام-اجنبي/"),
            Pair("افلام انمى", "category/anime-6/افلام-انمي/"),
            Pair("افلام تركيه", "category/movies-33/افلام-تركي/"),
            Pair("افلام اسيويه", "category/movies-33/افلام-اسيوي/"),
            Pair("افلام هنديه", "category/movies-33/افلام-هندى/"),
            Pair("كل المسسلسلات", "category/series-9/"),
            Pair("مسلسلات اجنبى", "category/series-9/مسلسلات-اجنبي/"),
            Pair("مسلسلات انمى", "category/anime-6/انمي-مترجم/"),
            Pair("مسلسلات تركى", "category/series-9/مسلسلات-تركي/"),
            Pair("مسلسلات اسيوى", "category/series-9/مسلسلات-أسيوي/"),
            Pair("مسلسلات هندى", "category/series-9/مسلسلات-هندي/"),
        ),
    )

    private class GenreFilter : SingleFilter(
        "التصنيف",
        arrayOf(
            "اكشن",
            "مغامرة",
            "كرتون",
            "فانتازيا",
            "خيال-علمي",
            "رومانسي",
            "كوميدي",
            "عائلي",
            "دراما",
            "اثارة",
            "غموض",
            "جريمة",
            "رعب",
            "وثائقي",
        ).sortedArray(),
    )

    open class SingleFilter(displayName: String, private val vals: Array<String>) :
        AnimeFilter.Select<String>(displayName, vals) {
        fun toUriPart() = vals[state]
    }

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
            title = "الجودة المفضلة",
            entries = listOf("1080p", "720p", "480p", "360p", "240p"),
            entryValues = listOf("1080", "720", "480", "360", "240"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )

        screen.addEditTextPreference(
            key = PREF_DOMAIN_CUSTOM_KEY,
            default = "",
            title = "المجال المخصص",
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
        private val REGEX_MOVIE = Regex("""(?:فيلم|عرض)\\s(.*\\s[0-9]+)\\s(.+?)\\s""")
        private val REGEX_SERIES = Regex("""(?:مسلسل|برنامج|انمي)\\s(.+)\\sالحلقة\\s(\\d+)""")
    }
}
