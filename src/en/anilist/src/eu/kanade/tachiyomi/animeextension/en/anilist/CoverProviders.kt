package eu.kanade.tachiyomi.animeextension.en.anilist

import eu.kanade.tachiyomi.animeextension.en.anilist.AniList.Companion.JIKAN_API_URL
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient

class CoverProviders(private val client: OkHttpClient, private val headers: Headers) {

    fun getMALCovers(malId: String): List<String> =
        runCatching {
            val picturesResponse = client.newCall(
                GET("$JIKAN_API_URL/anime/$malId/pictures", headers),
            ).execute().use { it.parseAs<MALPicturesDto>() }

            picturesResponse.data.mapNotNull { imgs ->
                imgs.jpg.let { it.largeImageUrl ?: it.imageUrl ?: it.smallImageUrl }
            }
        }.getOrElse { emptyList() }

    fun getFanartCovers(tvdbId: String, type: String): List<String> =
        runCatching {
            val fanArt = client.newCall(
                GET(
                    "https://webservice.fanart.tv/v3/$type/$tvdbId?api_key=184e1a2b1fe3b94935365411f919f638",
                    headers,
                ),
            ).execute().use { it.parseAs<FanartDto>() }

            fanArt.tvposter?.map { it.url } ?: emptyList()
        }.getOrElse { emptyList() }
}
