package eu.kanade.tachiyomi.animeextension.en.zoro

import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.multisrc.zorotheme.ZoroTheme
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request
import org.jsoup.nodes.Element

class HiAnime : ZoroTheme(
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

    private val megaCloudExtractor by lazy { MegaCloudExtractor(client, headers) }

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$baseUrl/recently-updated?page=$page",
        docHeaders,
    )

    override fun popularAnimeFromElement(element: Element): SAnime {
        return super.popularAnimeFromElement(element).apply {
            url = url.substringBefore("?")
        }
    }

    override fun extractVideo(server: VideoData): List<Video> {
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
            )

            else -> emptyList()
        }
    }

    // Added the setupPreferenceScreen method here
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        screen.addPreference(
            ListPreference(screen.context).apply {
                key = PREF_DOMAIN_KEY
                title = "Preferred domain"
                entries = DOMAIN_ENTRIES
                entryValues = DOMAIN_VALUES
                setDefaultValue(PREF_DOMAIN_DEFAULT)
                summary = "%s"

                setOnPreferenceChangeListener { _, _ ->
                    Toast.makeText(
                        screen.context,
                        "Restart App to apply changes",
                        Toast.LENGTH_LONG,
                    ).show()
                    true
                }
            },
        )
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val DOMAIN_ENTRIES = arrayOf(
            "hianime.to",
            "hianime.nz",
            "hianime.sx",
            "hianime.is",
            "hianime.bz",
            "hianime.pe",
            "hianimez.is",
        )
        private val DOMAIN_VALUES = DOMAIN_ENTRIES.map { "https://$it" }.toTypedArray()
        private val PREF_DOMAIN_DEFAULT = DOMAIN_VALUES[0]
    }
}
