package eu.kanade.tachiyomi.animeextension.en.citysonic

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix

class CitySonic : DopeFlix(
    "CitySonic",
    "en",
    BuildConfig.MEGACLOUD_API,
    listOf(
        "gomovies.gg",
        "himovies.sx",
        "fmovieszz.lol",
        "movies4kto.lol",
        "series2watch.net",
        // "citysonic.tv",
    ),
) {
    override val detailInfoSelector by lazy { "div.detail_page-infor, div.m_i-detail" }
    override val coverSelector by lazy { "div.cover_follow, div.dp-w-cover, div.w_b-cover" }

    override val episodeRegex by lazy { """Eps (\d+)""".toRegex() }
}
