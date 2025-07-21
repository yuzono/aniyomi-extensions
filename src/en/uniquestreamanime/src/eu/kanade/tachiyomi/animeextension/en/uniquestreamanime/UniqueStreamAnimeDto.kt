package eu.kanade.tachiyomi.animeextension.en.uniquestreamanime

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnimeDto(
    @SerialName("content_id")
    val contentId: String,
    val title: String,
    val image: String,
    val type: String, // "movie", "show"
    val subbed: Boolean,
    val dubbed: Boolean,
) {
    fun toSAnime(): SAnime {
        return SAnime.create().apply {
            url = StringBuilder().apply {
                if (type == "movie") {
                    append("/watch")
                } else {
                    append("/series")
                }
                append("/$contentId")
                append("/${titleToUri()}")
            }.toString()

            title = this@AnimeDto.title
            thumbnail_url = image
            genre = if (subbed && dubbed) "Subbed, Dubbed" else if (subbed) "Subbed" else "Dubbed"
            status = when (type) {
                "movie" -> SAnime.COMPLETED
                "show" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    private fun titleToUri(): String {
        return title.replace(" ", "-")
            .replace(Regex("[^a-z0-9-]"), "")
    }
}

@Serializable
data class SearchResultDto(
    val series: List<AnimeDto>?,
    val movies: List<AnimeDto>?,
)
