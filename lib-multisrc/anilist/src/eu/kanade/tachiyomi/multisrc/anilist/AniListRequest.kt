package eu.kanade.tachiyomi.multisrc.anilist

import kotlinx.serialization.Serializable

private fun String.toQuery() = this.trimIndent().replace("%", "$")

private const val STUDIO_MAIN = """
    studios(isMain: true) {
        node {
            name
        }
    }
"""

private const val STUDIOS = """
    studios {
        edges {
            isMain
            node {
                id
                name
            }
        }
    }
"""

private const val MEDIA = """
    id
    title {
        romaji
        english
        native
    }
    coverImage {
        extraLarge
        large
        medium
    }
    description
    status(version: 2)
    genres
    episodes
    season
    seasonYear
    format
    $STUDIOS
"""

internal val ANIME_DETAILS_QUERY = """
query (%id: Int) {
    Media(id: %id) {
        $MEDIA
    }
}
""".toQuery()

internal val EPISODES_QUERY = """
query media(%id: Int, %type: MediaType) {
    Media(id: %id, type: %type) {
        episodes
        nextAiringEpisode {
            episode
        }
    }
}
""".toQuery()

internal val ANIME_LIST_QUERY = """
query (
    %page: Int,
    %sort: [MediaSort],
    %search: String,
    %isAdult: Boolean,
) {
    Page(page: %page, perPage: 30) {
        pageInfo {
            hasNextPage
        }
        media(
            type: ANIME,
            sort: %sort,
            search: %search,
            status_in: [RELEASING, FINISHED],
            countryOfOrigin: "JP",
            isAdult: %isAdult,
        ) {
            $MEDIA
        }
    }
}
""".toQuery()

internal val LATEST_ANIME_LIST_QUERY = """
query (
    %page: Int,
    %sort: [MediaSort],
    %search: String,
    %isAdult: Boolean,
) {
    Page(page: %page, perPage: 30) {
        pageInfo {
            hasNextPage
        }
        media(
            type: ANIME,
            sort: %sort,
            search: %search,
            status_in: [RELEASING, FINISHED],
            countryOfOrigin: "JP",
            isAdult: %isAdult,
            startDate_greater: 1,
            episodes_greater: 1,
        ) {
            $MEDIA
        }
    }
}
""".toQuery()

internal val SORT_QUERY = """
query (
    %page: Int,
    %perPage: Int,
    %isAdult: Boolean,
    %type: MediaType,
    %sort: [MediaSort],
    %status: MediaStatus,
    %search: String,
    %genres: [String],
    %year: String,
    %seasonYear: Int,
    %season: MediaSeason,
    %format: [MediaFormat],
    %countryOfOrigin: CountryCode,
) {
    Page (page: %page, perPage: %perPage) {
        pageInfo {
            hasNextPage
        }
        media (
            isAdult: %isAdult,
            type: %type,
            sort: %sort,
            status: %status,
            search: %search,
            genre_in: %genres,
            startDate_like: %year,
            seasonYear: %seasonYear,
            season: %season,
            format_in: %format,
            countryOfOrigin: %countryOfOrigin,
        ) {
            $MEDIA
        }
    }
}
""".toQuery()

@Serializable
internal data class AnimeListVariables(
    val page: Int,
    val sort: MediaSort,
    val isAdult: Boolean? = null,
    val search: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val status: String? = null,
    val format: String? = null,
    val season: String? = null,
    val seasonYear: String? = null,
) {
    enum class MediaSort {
        TRENDING_DESC,
        START_DATE_DESC,
    }
}

@Serializable
internal data class AnimeDetailsVariables(val id: Int)
