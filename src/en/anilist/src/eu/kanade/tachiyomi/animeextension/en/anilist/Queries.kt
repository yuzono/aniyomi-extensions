package eu.kanade.tachiyomi.animeextension.en.anilist

private fun String.toQuery() = this.trimIndent().replace("%", "$")

fun getDetailsQuery() = """
query media(%id: Int, %type: MediaType) {
  Media(id: %id, type: %type) {
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
    status
  }
}
""".toQuery()
