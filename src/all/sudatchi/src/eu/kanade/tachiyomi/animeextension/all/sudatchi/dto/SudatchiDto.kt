package eu.kanade.tachiyomi.animeextension.all.sudatchi.dto

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EpisodeDto(
    val title: String,
    val id: Int,
    val number: Int,
    @SerialName("Subtitles")
    val subtitlesNew: List<SubtitleDto>?, // url & SubtitleLangDto
    val subtitles: List<SubtitleDto>?, // url & language
    @SerialName("AudioStreams")
    val audioStreamsNew: List<AudioDto>?, // playlistUri & AudioLangDto
    val audioStreams: List<AudioDto>?, // playlistUri & language
    /* For home page */
    val animeId: Int, // Only correct for home page, not correct for anime detail page
    val coverImage: String?, // "/image/2ca27132"
    val animeTitle: TitleDto?,
) {
    fun toEpisode(animeId: Int) = SEpisode.create().apply {
        url = "/api/anime/$animeId?episode=$id"
        name = title
        episode_number = number.toFloat()
    }

    fun toSAnime(titleLang: String, baseUrl: String) = SAnime.create().apply {
        url = "/anime/$animeId"
        title = animeTitle?.let {
            when (titleLang) {
                "romaji" -> it.romaji
                "japanese" -> it.native
                else -> it.english
            } ?: arrayOf(
                it.english,
                it.romaji,
                it.native,
                "",
            ).firstNotNullOf { it }
        } ?: this@EpisodeDto.title
        thumbnail_url = "$baseUrl$coverImage"
    }
}

@Serializable
data class AnimeDto(
    val id: String,
    val title: TitleDto,
    val status: String?, // FINISHED
    val coverImage: String,
    @SerialName("genres")
    val animeGenres: List<String>?,
) {
    fun toSAnime(titleLang: String) = SAnime.create().apply {
        url = "/anime/$id"
        title = when (titleLang) {
            "romaji" -> this@AnimeDto.title.romaji
            "japanese" -> this@AnimeDto.title.native
            else -> this@AnimeDto.title.english
        } ?: arrayOf(this@AnimeDto.title.english, this@AnimeDto.title.romaji, this@AnimeDto.title.native, "").firstNotNullOf { it }
        status = this@AnimeDto.status?.parseStatus() ?: SAnime.UNKNOWN
        thumbnail_url = coverImage
        genre = animeGenres?.joinToString()
    }
}

@Serializable
data class AnimeRelatedDto(
    val id: Int,
    val title: TitleDto,
    val status: String?, // FINISHED
    val coverImage: String,
    @SerialName("genres")
    val animeGenres: List<String>?,
) {
    fun toSAnime(titleLang: String) = SAnime.create().apply {
        url = "/anime/$id"
        title = when (titleLang) {
            "romaji" -> this@AnimeRelatedDto.title.romaji
            "japanese" -> this@AnimeRelatedDto.title.native
            else -> this@AnimeRelatedDto.title.english
        } ?: arrayOf(this@AnimeRelatedDto.title.english, this@AnimeRelatedDto.title.romaji, this@AnimeRelatedDto.title.native, "").firstNotNullOf { it }
        status = this@AnimeRelatedDto.status?.parseStatus() ?: SAnime.UNKNOWN
        thumbnail_url = coverImage
        genre = animeGenres?.joinToString()
    }
}

@Serializable
data class AnimeDetailDto(
    val id: Int,
    val title: TitleDto,
    @SerialName("synonyms")
    val alternativeNames: List<String>?,
    val description: String?,
    val status: String, // FINISHED
    val coverImage: CoverDto?,
    val bannerImage: String?,
    @SerialName("genres")
    val animeGenres: List<String>?,
    val duration: Int?,
    val episodesCount: Int?,
    val season: String?, // WINTER
    val year: Int?,
    val averageScore: Int?,
    val nextAiring: AiringDto?,
    val trailer: TrailerDto?,
    val studio: String?,
    val country: String?, // JP
    val related: List<AnimeRelatedDto>?,
    val recommendations: List<AnimeRelatedDto>?,
    val episodes: List<EpisodeDto>,
) {
    fun toSAnime(titleLang: String) = SAnime.create().apply {
        url = "/anime/$id"
        title = when (titleLang) {
            "romaji" -> this@AnimeDetailDto.title.romaji
            "japanese" -> this@AnimeDetailDto.title.native
            else -> this@AnimeDetailDto.title.english
        } ?: arrayOf(this@AnimeDetailDto.title.english, this@AnimeDetailDto.title.romaji, this@AnimeDetailDto.title.native, "").firstNotNullOf { it }
        description = StringBuilder().apply {
            this@AnimeDetailDto.description?.let { appendLine(it) }
            appendLine()
            trailer?.let { appendLine("[Trailer](https://youtube.com/watch?v=${it.id})") }
            country?.let { appendLine("Country: $it") }
            season?.let { appendLine("Season: $it") }
            year?.let { appendLine("Year: $it") }
            averageScore?.let { appendLine("Score: $it") }
            appendLine()
            alternativeNames?.let { list ->
                if (list.isEmpty()) return@let
                appendLine("Alternative names:")
                list.forEach { appendLine("- $it") }
            }
        }.toString()
        status = this@AnimeDetailDto.status.parseStatus()
        thumbnail_url = coverImage?.extraLarge ?: bannerImage
        genre = animeGenres?.joinToString()
        author = studio
    }

    @Serializable
    data class AiringDto(
        val ep: Int, // episode number
        val at: Int, // timestamp
    )
}

private fun String.parseStatus() = when (this) {
    "LICENSED" -> SAnime.LICENSED // Not Yet Released
    "AIRING" -> SAnime.ONGOING
    "RELEASING" -> SAnime.ONGOING
    "FINISHED" -> SAnime.COMPLETED
    else -> SAnime.UNKNOWN
}

@Serializable
data class TitleDto(
    val romaji: String?,
    val english: String?,
    val native: String?,
)

@Serializable
data class CoverDto(
    val extraLarge: String?,
)

@Serializable
data class TrailerDto(
    val id: String, // "dQw4w9WgXcQ"
    val site: String, // "youtube"
)

@Serializable
data class HomePageDto(
    val latestEpisodes: List<EpisodeDto>,
)

@Serializable
data class SeriesDto(
    val results: List<AnimeDto>,
    val hasNextPage: Boolean,
)

@Serializable
data class SubtitleLangDto(
    val name: String, // "English"
    val language: String, // "eng"
)

@Serializable
data class SubtitleDto(
    val url: String, // "/ipfs/bafkreicmknx7fg23tvfktgo4gybh2a3dqvgk33z7wtyxnjcbar26m57kjq"
    @SerialName("SubtitlesName")
    val subtitlesName: SubtitleLangDto?,
    val language: String?,
)

@Serializable
data class AudioDto(
    val playlistUri: String, // "bafybeiegi6t2yhc4cubdztvvzb6s5vetuyisrureindkrgi7xgcmu2cmqi/audio.m3u8"
    val Language: AudioLangDto?,
    val language: String?,
)

@Serializable
data class AudioLangDto(
    val name: String, // "English"
    val code: String, // "eng"
)
