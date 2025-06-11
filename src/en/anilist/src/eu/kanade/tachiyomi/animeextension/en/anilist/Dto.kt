package eu.kanade.tachiyomi.animeextension.en.anilist

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class Mapping(
    @SerialName("mal_id") val malId: Int? = null,
    @SerialName("anilist_id") val anilistId: Int? = null,
    @SerialName("thetvdb_id") val thetvdbId: Int? = null,
)

@Serializable
class AniListEpisodeResponse(
    val data: DataObject,
) {
    @Serializable
    class DataObject(
        @SerialName("Media") val media: MediaObject,
    ) {
        @Serializable
        class MediaObject(
            val episodes: Int? = null,
            val nextAiringEpisode: NextAiringObject? = null,
        ) {
            @Serializable
            class NextAiringObject(
                val episode: Int,
            )
        }
    }
}

@Serializable
class AnilistToMalResponse(
    val data: DataObject,
) {
    @Serializable
    class DataObject(
        @SerialName("Media") val media: MediaObject,
    ) {
        @Serializable
        class MediaObject(
            val id: Int,
            val status: String,
            val idMal: Int? = null,
        )
    }
}

@Serializable
class JikanAnimeDto(
    val data: JikanAnimeDataDto,
) {
    @Serializable
    class JikanAnimeDataDto(
        val aired: AiredDto,
    ) {
        @Serializable
        class AiredDto(
            val from: String,
        )
    }
}

@Serializable
class JikanEpisodesDto(
    val pagination: JikanPaginationDto,
    val data: List<JikanEpisodesDataDto>,
) {
    @Serializable
    class JikanPaginationDto(
        @SerialName("has_next_page") val hasNextPage: Boolean,
        @SerialName("last_visible_page") val lastPage: Int,
    )

    @Serializable
    class JikanEpisodesDataDto(
        @SerialName("mal_id") val number: Int,
        val title: String? = null,
        val aired: String? = null,
        val filler: Boolean,
    )
}

@Serializable
class MALPicturesDto(
    val data: List<MALCoverDto>,
) {
    @Serializable
    class MALCoverDto(
        val jpg: MALJpgDto,
    ) {
        @Serializable
        class MALJpgDto(
            @SerialName("image_url") val imageUrl: String? = null,
            @SerialName("small_image_url") val smallImageUrl: String? = null,
            @SerialName("large_image_url") val largeImageUrl: String? = null,
        )
    }
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
