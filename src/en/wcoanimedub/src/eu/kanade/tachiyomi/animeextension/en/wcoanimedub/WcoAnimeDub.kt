package eu.kanade.tachiyomi.animeextension.en.wcoanimedub

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.wcotheme.Filters
import eu.kanade.tachiyomi.multisrc.wcotheme.WcoTheme
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request

class WcoAnimeDub : WcoTheme() {
    override val name = "WcoAnimeDub"
    override val baseUrl = "https://www.wcoanimedub.tv"

    override val useOldIframeExtractor = true

    /** Site searching is broken (server side) so we disable all query search */
    override val disableRelatedAnimesBySearch = true
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genresFilter = filters.filterIsInstance<Filters.GenresFilter>().firstOrNull()

        if (genresFilter != null && !genresFilter.isDefault()) {
            val url = "$baseUrl/search-by-genre/page/${genresFilter.toUriPart()}"
            return GET(url, headers)
        } else {
            return popularAnimeRequest(page)
        }
    }
}
