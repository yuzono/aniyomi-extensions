package eu.kanade.tachiyomi.animeextension.en.dramafull

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class FilterResponse(
    val data: List<DramaDto>,
    @SerialName("next_page_url")
    val nextPageUrl: String?,
)

@Serializable
data class DramaDto(
    val id: Int,
    val name: String,
    val slug: String,
    @SerialName("type_id")
    val typeId: Int,
    @SerialName("image")
    val thumbnail: String?,
)

@Serializable
data class VideoResponse(
    @SerialName("video_source")
    val videoSource: Map<String, String?>,
    val sub: JsonElement? = null,
)
