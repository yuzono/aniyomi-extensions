package eu.kanade.tachiyomi.animeextension.es.jkanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JsLinks(
    val remote: String? = null,
    val server: String? = null,
    val lang: Long? = null,
    val slug: String? = null,
)

@Serializable
data class EpisodeDto(
    val number: Int,
)

@Serializable
data class EpisodesPageDto(
    @SerialName("last_page")
    val lastPage: Int,
    val total: Int,
    val data: List<EpisodeDto>,
)

@Serializable
data class AnimeDto(
    val title: String,
    @SerialName("synopsis")
    val description: String?,
    @SerialName("image")
    val thumbnailUrl: String,
    @SerialName("studios")
    val author: String?,
    @SerialName("estado")
    val status: String,
    val url: String,
)

@Serializable
data class AnimePageDto(
    @SerialName("current_page")
    val currentPage: Int?,
    @SerialName("last_page")
    val lastPage: Int?,
    @SerialName("next_page_url")
    val nextPageUrl: String?,
    val data: List<AnimeDto>,
)
