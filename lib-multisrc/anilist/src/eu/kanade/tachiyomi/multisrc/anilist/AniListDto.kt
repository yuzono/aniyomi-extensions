package eu.kanade.tachiyomi.multisrc.anilist

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.anilist.AniListAnimeHttpSource.Companion.TitleLanguage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

@Serializable
internal data class AniListAnimeListResponse(val data: Data) {
    @Serializable
    data class Data(@SerialName("Page") val page: Page) {
        @Serializable
        data class Page(
            val pageInfo: PageInfo,
            val media: List<AniListMedia>,
        ) {
            @Serializable
            data class PageInfo(val hasNextPage: Boolean)
        }
    }
}

@Serializable
internal data class AniListAnimeDetailsResponse(val data: Data) {
    @Serializable
    data class Data(@SerialName("Media") val media: AniListMedia)
}

@Serializable
internal data class AniListMedia(
    val id: Int,
    val title: Title,
    val coverImage: CoverImage,
    val description: String?,
    val status: Status?,
    val genres: List<String>?,
    val episodes: Int?,
    val season: String?,
    val seasonYear: Int?,
    val format: String?,
    val studios: Studios?,
) {
    @Serializable
    data class Title(
        val romaji: String?,
        val english: String?,
        val native: String?,
    ) {
        fun getPreferredTitle(titlePref: TitleLanguage): String {
            return when (titlePref) {
                TitleLanguage.ROMAJI -> romaji ?: english ?: native ?: ""
                TitleLanguage.ENGLISH -> english ?: romaji ?: native ?: ""
                else -> native ?: romaji ?: english ?: ""
            }
        }

        fun getAlternativeTitles(titlePref: TitleLanguage): List<String>? {
            val preferredTitle = getPreferredTitle(titlePref)
            return listOfNotNull(romaji, english, native)
                .filterNot { it == preferredTitle }
                .takeIf { it.isNotEmpty() }
        }
    }

    @Serializable
    data class CoverImage(
        val extraLarge: String?,
        val large: String?,
        val medium: String?,
    )

    enum class Status {
        RELEASING,
        FINISHED,
        NOT_YET_RELEASED,
        CANCELLED,
        HIATUS,
    }

    @Serializable
    data class StudiosMain(val nodes: List<Node>) {
        @Serializable
        data class Node(val name: String)
    }

    @Serializable
    data class Studios(val edges: List<Node>) {
        @Serializable
        data class Node(
            val isMain: Boolean,
            val node: Studio,
        ) {
            @Serializable
            data class Studio(
                val name: String,
            )
        }

        fun getAuthor(): String? {
            return edges.filter { it.isMain }.joinToString { it.node.name }
                .ifBlank { edges.joinToString { it.node.name } }
                .takeIf { it.isNotBlank() }
        }
    }

    fun parseStatus() = when (status) {
        Status.RELEASING -> SAnime.ONGOING
        Status.FINISHED -> SAnime.COMPLETED
        Status.CANCELLED -> SAnime.CANCELLED
        Status.HIATUS -> SAnime.ON_HIATUS
        else -> SAnime.UNKNOWN
    }

    fun toSAnime(
        titlePref: TitleLanguage,
        mapAnimeDetailUrl: (Int) -> String,
    ): SAnime {
        return SAnime.create().apply {
            description = buildString {
                append(
                    this@AniListMedia.description?.let {
                        Jsoup.parseBodyFragment(
                            it.replace("<br>\n", "br2n")
                                .replace("<br>", "br2n")
                                .replace("\n", "br2n"),
                        ).text().replace("br2n", "\n")
                    },
                )
                appendLine()

                this@AniListMedia.title.getAlternativeTitles(titlePref)
                    ?.let { append("\n**Alternative title(s):** ${it.joinToString()}") }

                if (!(season == null && seasonYear == null)) {
                    append("\n**Release:** ${season?.lowercase()?.replaceFirstChar { it.titlecaseChar() } ?: ""} ${seasonYear ?: ""}")
                }

                format?.lowercase()?.replaceFirstChar { it.titlecaseChar() }
                    ?.let { append("\n**Type:** $format") }
                episodes?.let { append("\n**Total Episode:** $episodes") }
            }.trim()

            url = mapAnimeDetailUrl(id)
            title = this@AniListMedia.title.getPreferredTitle(titlePref)
            author = studios?.getAuthor()
            genre = genres?.joinToString()
            status = parseStatus()
            thumbnail_url = coverImage.extraLarge ?: coverImage.large ?: coverImage.medium
            initialized = true
        }
    }
}
