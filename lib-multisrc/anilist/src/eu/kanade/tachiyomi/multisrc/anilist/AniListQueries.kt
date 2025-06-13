package eu.kanade.tachiyomi.multisrc.anilist

import kotlinx.serialization.Serializable

object AniListQueries {
    private fun String.toQuery() = this.trimIndent().replace("%", "$")

    private const val MEDIA_TYPE = "ANIME"
    private const val PER_PAGE = 25

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

    val ANIME_DETAILS_QUERY = """
        query (%id: Int) {
            Media(id: %id, type: $MEDIA_TYPE) { $MEDIA }
        }
        """.toQuery()

    val EPISODES_QUERY = """
        query media(%id: Int) {
            Media(id: %id, type: $MEDIA_TYPE) {
                episodes
                nextAiringEpisode {
                    episode
                }
            }
        }
        """.toQuery()

    val MAL_ID_QUERY = """
        query media(%id: Int) {
            Media(id: %id, type: $MEDIA_TYPE) {
                idMal
                id
                status
            }
        }
        """.toQuery()

    val TRENDING_ANIME_LIST_QUERY = """
        query (
            %page: Int,
            %sort: [MediaSort],
            %search: String,
            %isAdult: Boolean,
            %countryOfOrigin: CountryCode,
        ) {
            Page(page: %page, perPage: $PER_PAGE) {
                pageInfo {
                    hasNextPage
                }
                media(
                    type: $MEDIA_TYPE,
                    sort: %sort,
                    search: %search,
                    status_in: [RELEASING, FINISHED],
                    countryOfOrigin: %countryOfOrigin,
                    isAdult: %isAdult,
                ) { $MEDIA }
            }
        }
        """.toQuery()

    fun latestAnilistQuery(extraLatestMediaFields: String = "") = """
        query (
            %page: Int,
            %sort: [MediaSort],
            %search: String,
            %isAdult: Boolean,
            %countryOfOrigin: CountryCode,
        ) {
            Page(page: %page, perPage: $PER_PAGE) {
                pageInfo {
                    hasNextPage
                }
                media(
                    type: $MEDIA_TYPE,
                    sort: %sort,
                    search: %search,
                    status_in: [RELEASING, FINISHED],
                    countryOfOrigin: %countryOfOrigin,
                    isAdult: %isAdult,
                    $extraLatestMediaFields,
                ) { $MEDIA }
            }
        }
        """.toQuery()

    val SORT_QUERY = """
        query (
            %page: Int,
            %isAdult: Boolean,
            %sort: [MediaSort],
            %status: MediaStatus,
            %search: String,
            %genres: [String],
            %excludedGenres: [String],
            %tags: [String],
            %excludedTags: [String],
            %year: String,
            %seasonYear: Int,
            %season: MediaSeason,
            %format: [MediaFormat],
            %countryOfOrigin: CountryCode,
        ) {
            Page (page: %page, perPage: $PER_PAGE) {
                pageInfo {
                    hasNextPage
                }
                media (
                    isAdult: %isAdult,
                    type: $MEDIA_TYPE,
                    sort: %sort,
                    status: %status,
                    search: %search,
                    genre_in: %genres,
                    genre_not_in: %excludedGenres,
                    tag_in: %tags,
                    tag_not_in: %excludedTags,
                    startDate_like: %year,
                    seasonYear: %seasonYear,
                    season: %season,
                    format_in: %format,
                    countryOfOrigin: %countryOfOrigin,
                ) { $MEDIA }
            }
        }
        """.toQuery()

    @Serializable
    data class AnimeListVariables(
        val page: Int,
        val sort: String,
        val isAdult: Boolean? = null,
        val search: String? = null,
        val genres: List<String>? = null,
        val excludedGenres: List<String>? = null,
        val tags: List<String>? = null,
        val excludedTags: List<String>? = null,
        val year: String? = null,
        val status: String? = null,
        val format: List<String>? = null,
        val season: String? = null,
        val seasonYear: String? = null,
        val countryOfOrigin: String? = null,
    ) {
        enum class MediaSort {
            TRENDING_DESC,
            START_DATE_DESC,
        }
    }

    @Serializable
    data class AnimeDetailsVariables(val id: Int)
}
