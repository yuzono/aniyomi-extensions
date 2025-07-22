package eu.kanade.tachiyomi.animeextension.en.uniquestreamanime

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
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
}

@Serializable
data class SearchResultDto(
    val series: List<AnimeDto>?,
    val movies: List<AnimeDto>?,
)

@Serializable
data class AnimeDetailsDto(
    @SerialName("content_id")
    val contentId: String,
    val title: String,
    val images: List<CoverDto>,
    val description: String,
    val seasons: List<SeasonDto>,
) {
    fun toSAnime(): SAnime {
        return SAnime.create().apply {
            thumbnail_url = images.firstOrNull()?.url
            description = this@AnimeDetailsDto.description
        }
    }
}

@Serializable
data class CoverDto(
    val url: String,
    @SerialName("type")
    val coverType: String, // "poster_tall", "poster_wide"
)

/*
{
    "content_id": "JLplLrAy",
    "title": "Dragon Ball DAIMA",
    "description": "Goku and company were living peaceful lives when they suddenly turned small due to a conspiracy! When they discover that the reason for this may lie in a world known as the \"Demon Realm\", a mysterious young Majin named Glorio appears before them.",
    "images": [
        {
            "url": "https://anime.uniquestream.net/public/images/posters/480x720/b155b5e34dbe69c08a3d3cd9e43b75e7.jpg",
            "type": "poster_tall"
        },
        {
            "url": "https://anime.uniquestream.net/public/images/posters/1200x675/b155b5e34dbe69c08a3d3cd9e43b75e7.jpg",
            "type": "poster_wide"
        }
    ],
    "seasons": [
        {
            "content_id": "ZAOtHV9H",
            "title": "Dragon Ball DAIMA",
            "season_number": 1,
            "season_seq_number": 0,
            "display_number": "",
            "episode_count": 20
        }
    ],
    "episode": {
        "content_id": "9QYiYWpn",
        "title": "Conspiracy",
        "image": "https://anime.uniquestream.net/public/images/episodes/320x180/82c02517f3fa1134ea0dd00872f03c87.jpg",
        "image_loading": null,
        "type": "show",
        "subbed": false,
        "dubbed": false,
        "series_title": null,
        "season_number": 1,
        "season_display": "",
        "episode_number": 1,
        "duration_ms": 1925569,
        "episode": "1",
        "is_clip": false
    },
    "audio_locales": [
        "en-US",
        "ja-JP"
    ],
    "subtitle_locales": [
        "en-US",
        "es-419",
        "es-ES",
        "pt-BR"
    ]
}
 */

/*
"seasons": [
    {
        "content_id": "ZAOtHV9H",
        "title": "Dragon Ball DAIMA",
        "season_number": 1,
        "season_seq_number": 0,
        "display_number": "",
        "episode_count": 20
    },
    {
        "content_id": "dqDxzx0S",
        "title": "One Piece: East Blue (1-61)",
        "season_number": 2,
        "season_seq_number": 1,
        "display_number": "1",
        "episode_count": 61
    },
    {
        "content_id": "dHTiu8rG",
        "title": "One Piece Special Edition (HD, Subtitled): East Blue (1-61)",
        "season_number": 1,
        "season_seq_number": 2,
        "display_number": "1",
        "episode_count": 61
    },
    {
        "content_id": "zfdUvIqq",
        "title": "One Piece: Alabasta (62-135)",
        "season_number": 3,
        "season_seq_number": 3,
        "display_number": "2",
        "episode_count": 74
    },
    {
        "content_id": "HxyVVAYQ",
        "title": "One Piece Special Edition (HD, Subtitled): Alabasta (62-135)",
        "season_number": 2,
        "season_seq_number": 4,
        "display_number": "2",
        "episode_count": 74
    },
    {
        "content_id": "7RDsXo6t",
        "title": "One Piece: Sky Island (136-206)",
        "season_number": 4,
        "season_seq_number": 5,
        "display_number": "3",
        "episode_count": 71
    },
    {
        "content_id": "ryPJvHBp",
        "title": "One Piece Special Edition (HD, Subtitled): Sky Island (136-206)",
        "season_number": 3,
        "season_seq_number": 6,
        "display_number": "3",
        "episode_count": 71
    },
    {
        "content_id": "MuNJ3WL6",
        "title": "One Piece: Water 7 (207-325)",
        "season_number": 5,
        "season_seq_number": 8,
        "display_number": "4",
        "episode_count": 119
    },
    {
        "content_id": "4Fi7CBfp",
        "title": "One Piece: Thriller Bark (326-384)",
        "season_number": 6,
        "season_seq_number": 9,
        "display_number": "5",
        "episode_count": 59
    }
]
 */
@Serializable
data class SeasonDto(
    @SerialName("content_id")
    val contentId: String,
    val title: String,
    @SerialName("season_number")
    val seasonNumber: Int,
    @SerialName("season_seq_number")
    val seasonSeqNumber: Int,
    @SerialName("display_number")
    val displayNumber: String, // empty if no season number
    @SerialName("episode_count")
    val episodeCount: Int,
)

/*
{
    "title": "An Angry Showdown! Cross the Red Line!",
    "episode": "61",
    "is_clip": false,
    "content_id": "oS7sxbTT",
    "episode_number": 61.0,
    "duration_ms": 1497203,
    "image": "https://anime.uniquestream.net/public/images/episodes/320x180/5deb5d8898533d59f5cfcd51e1df4040.jpg",
    "image_loading": "https://anime.uniquestream.net/public/images/episodes/320x180/missing.jpg"
}
 */
@Serializable
data class EpisodeDto(
    val title: String,
    val episode: String, // empty for special episodes
    @SerialName("is_clip")
    val isClip: Boolean,
    @SerialName("content_id")
    val contentId: String,
    @SerialName("episode_number")
    val episodeNumber: Double, // 0.0 for special episodes
    @SerialName("duration_ms")
    val durationMs: Long,
    val image: String,
) {
    fun toEpisode(season: String): SEpisode {
        return SEpisode.create().apply {
            name = StringBuilder().apply {
                if (season.isNotBlank()) append("S$season ")
                if (episode.isNotBlank()) {
                    episode.toIntOrNull()?.let { episodeNumber ->
                        append("E$episodeNumber - ")
                    } ?: append("$episode - ")
                }
                append(title)
            }.toString()
            episode_number = episodeNumber.toFloat()
            url = "/watch/$contentId"
        }
    }
}
