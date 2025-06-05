package eu.kanade.tachiyomi.animeextension.en.wcofun

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.multisrc.wco.Wco
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response

class Wcofun : Wco() {

    override val name = "Wcofun"

    override val baseUrl = "https://www.wcoflix.tv"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }
            .ifEmpty {
                listOf(
                    SEpisode.create().apply {
                        setUrlWithoutDomain(response.request.url.toString())
                        val title = document.select(".video-title").text()
                        val (name, _) = episodeTitleFromElement(title)
                        this.name = name
                    },
                )
            }
    }
}
