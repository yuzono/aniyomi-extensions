package eu.kanade.tachiyomi.animeextension.en.zoro

import androidx.preference.EditTextPreference
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
import okhttp3.Request
import org.jsoup.nodes.Element

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
        get() {
            val custom = preferences.getString(PREF_DOMAIN_CUSTOM_KEY, null)?.trim()
            return if (!custom.isNullOrBlank()) {
                custom
            } else {
                preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
            }
        }
        set(value) {
            if (value in DOMAIN_VALUES) {
                preferences.edit().putString(PREF_DOMAIN_KEY, value).apply()
                preferences.edit().remove(PREF_DOMAIN_CUSTOM_KEY).apply()
            } else {
                preferences.edit().putString(PREF_DOMAIN_CUSTOM_KEY, value).apply()
            }
            docHeaders = newHeaders()
        }

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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        // Preferred domains (dropdown)
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred domain",
            entries = DOMAIN_ENTRIES,
            entryValues = DOMAIN_VALUES,
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        ) {
            // Update baseUrl so fallback works immediately
            baseUrl = it
            // Remove any custom domain if selected
            preferences.edit().remove(PREF_DOMAIN_CUSTOM_KEY).apply()
        }

        // Custom domain (manual input)
        val customDomainPref = EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_CUSTOM_KEY
            title = "Custom domain"
            dialogTitle = "Enter custom domain (e.g., https://example.com)"
            summary = "%s"

            setOnPreferenceChangeListener { pref, newValue ->
                var url = (newValue as? String)?.trim().orEmpty()

                if (url.isBlank()) {
                    // Clear custom → fallback to preferred domain
                    preferences.edit().remove(PREF_DOMAIN_CUSTOM_KEY).apply()
                    docHeaders = newHeaders()
                    true
                } else {
                    // Auto add https:// if missing
                    if (!url.startsWith("http")) {
                        url = "https://$url"
                    }
                    // Custom Key use
                    preferences.edit().putString(PREF_DOMAIN_CUSTOM_KEY, url).apply()
                    docHeaders = newHeaders()
                    true
                }
            }
        }
        screen.addPreference(customDomainPref)
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_CUSTOM_KEY = "custom_domain"

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
