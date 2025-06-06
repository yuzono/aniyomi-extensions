package eu.kanade.tachiyomi.animeextension.en.wcoforever

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.wcotheme.WcoTheme
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response

class WcoForever : WcoTheme() {
    override val name = "WcoForever"
    override val baseUrl = "https://www.wcoforever.net"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }
            // If opening an episode link instead of anime link, there is no episode list available.
            // So we return the same episode with the title from the page.
            .ifEmpty {
                listOf(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(response.request.url.toString())
                        val title = document.select(".baslikCell").text()
                        val (name, _) = episodeTitleFromElement(title)
                        this.name = name
                    },
                )
            }
    }
}
