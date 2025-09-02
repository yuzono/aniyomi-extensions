package eu.kanade.tachiyomi.animeextension.en.animekai

import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Serializable
data class ResultResponse(
    val result: String,
) {
    fun toDocument(): Document {
        return Jsoup.parseBodyFragment(result)
    }
}

@Serializable
data class Image(
    val coverType: String?,
    val url: String?,
)

@Serializable
data class Episode(
    val episode: String?,
    val airDate: String?, // Keeping only one field
    val runtime: Int?, // Keeping only one field
    val image: String?,
    val title: Map<String, String>?,
    val overview: String?,
    val rating: String?,
    val finaleType: String?,
)

@Serializable
data class AnimeData(
    val titles: Map<String, String>?,
    val images: List<Image>?,
    val episodes: Map<String, Episode>?,
)
