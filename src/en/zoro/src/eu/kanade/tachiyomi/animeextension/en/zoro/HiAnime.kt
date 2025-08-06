package eu.kanade.tachiyomi.animeextension.en.zoro

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import extensions.utils.LazyMutable
import extensions.utils.addListPreference
import extensions.utils.delegate
import okhttp3.Request
import org.jsoup.nodes.Element
import kotlin.getValue

class HiAnime :
    ZoroTheme(
        "en",
        "HiAnime",
        "https://hianime.to",
        hosterNames = listOf(
            "HD-1",
            "HD-2",
            "HD-3",
            "StreamTape",
        ),
    ) {
    override val id = 6706411382606718900L

    override val ajaxRoute = "/v2"

    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val megaCloudExtractor by LazyMutable { MegaCloudExtractor(client, headers, BuildConfig.MEGACLOUD_API) }

    override var baseUrl: String
        by preferences.delegate(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$baseUrl/recently-updated?page=$page",
        docHeaders,
    )

    override fun popularAnimeFromElement(element: Element): SAnime {
        return super.popularAnimeFromElement(element).apply {
            url = url.substringBefore("?")
        }
    }

    override suspend fun extractVideo(server: VideoData): List<Video> {
        return when (server.name) {
            "StreamTape" -> {
                streamtapeExtractor.videoFromUrl(
                    server.link,
                    "Streamtape - ${server.type}",
                )?.let(::listOf) ?: emptyList()
            }

            "HD-1", "HD-2", "HD-3" -> megaCloudExtractor.getVideosFromUrl(
                server.link,
                server.type,
                server.name,
                server.name == "HD-3",
            )

            else -> emptyList()
        }
    }

    // Added the setupPreferenceScreen method here
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            baseUrl = it
            docHeaders = newHeaders()
        }
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = listOf(
            "hianime.to",
            "hianime.nz",
            "hianime.sx",
            "hianime.is",
            "hianime.bz",
            "hianime.pe",
            "hianime.cx",
            "hianime.do",
            "hianimez.is",
            "hianimez.to",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]
    }
}
