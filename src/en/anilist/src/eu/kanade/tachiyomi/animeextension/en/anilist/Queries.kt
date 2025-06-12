package eu.kanade.tachiyomi.animeextension.en.anilist

private fun String.toQuery() = this.trimIndent().replace("%", "$")

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
