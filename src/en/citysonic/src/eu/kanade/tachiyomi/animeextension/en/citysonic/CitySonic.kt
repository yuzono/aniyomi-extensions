package eu.kanade.tachiyomi.animeextension.en.citysonic

import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.multisrc.dopeflix.DopeFlix

class CitySonic : DopeFlix(
    "CitySonic",
    "en",
    BuildConfig.MEGACLOUD_API,
    listOf(
        "citysonic.tv",
        "gomovies.sx",
        "himovies.sx",
        "fmoviesz.ms",
    ),
) {
    override val detailInfoSelector by lazy { "div.detail-infor" }
    override val coverSelector by lazy { "div.dp-w-cover" }

    override val episodeRegex by lazy { """Eps (\d+)""".toRegex() }
}
