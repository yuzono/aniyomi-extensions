package eu.kanade.tachiyomi.animeextension.en.anilist

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient

class CoverProviders(private val client: OkHttpClient, private val headers: Headers) {
    fun getMALCovers(malId: String): List<String> {
        val docHeaders = headers.newBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            add("Host", "myanimelist.net")
        }.build()

        val document = client.newCall(
            GET("https://myanimelist.net/anime/$malId", docHeaders),
        ).execute().asJsoup()

        val picsUrl = document.selectFirst("#horiznav_nav > ul > li > a:contains(Pictures)")
            ?.attr("abs:href")
            ?: return emptyList()

        val picsDocument = client.newCall(
            GET(picsUrl, docHeaders),
        ).execute().asJsoup()

        return picsDocument.select("div.picSurround > a.js-picture-gallery").map {
            Log.i("SOMETHING-pic", it.attr("abs:href"))
            it.attr("abs:href")
        }
    }

    fun getFanartCovers(tvdbId: String, type: String): List<String> {
        val url = "https://webservice.fanart.tv/v3/$type/$tvdbId?api_key=184e1a2b1fe3b94935365411f919f638"

        return client.newCall(
            GET(url, headers),
        ).execute().parseAs<FanartDto>().tvposter?.map { it.url } ?: emptyList()
    }

    @Serializable
    class FanartDto(
        val tvposter: List<ImageDto>? = null,
    ) {
        @Serializable
        class ImageDto(
            val url: String,
        )
    }
}
