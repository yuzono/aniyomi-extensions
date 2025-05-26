package eu.kanade.tachiyomi.animeextension.en.anilist

private fun String.toQuery() = this.trimIndent().replace("%", "$")

fun getSortQuery() = """
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
        ) {
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
        }
    }
}
""".toQuery()

fun getDetailsQuery() = """
query media(%id: Int, %type: MediaType) {
  Media(id: %id, type: %type) {
    title {
        romaji
        english
        native
    }
    description
    season
    seasonYear
    format
    status(version: 2)
    genres
    episodes
    studios {
      edges {
        isMain
        node {
          id
          name
        }
      }
    }
  }
}
""".toQuery()

fun getEpisodeQuery() = """
query media(%id: Int, %type: MediaType) {
  Media(id: %id, type: %type) {
    episodes
    nextAiringEpisode {
      episode
    }
  }
}
""".toQuery()

fun getMalIdQuery() = """
query media(%id: Int, %type: MediaType) {
  Media(id: %id, type: %type) {
    idMal
    id
  }
}
""".toQuery()
