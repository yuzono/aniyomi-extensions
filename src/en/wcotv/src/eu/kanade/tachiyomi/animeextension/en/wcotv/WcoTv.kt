package eu.kanade.tachiyomi.animeextension.en.wcotv

import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.multisrc.wcotheme.WcoTheme
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response

class WcoTv : WcoTheme() {
    override val name = "WcoTv"
    override val baseUrl = "https://www.wco.tv"

    override val useOldIframeExtractor = true

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        return document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
            // Remove entries that have no URL
            .filterNot { it.url == "/" }
            .let { AnimesPage(it, false) }
    }
}
