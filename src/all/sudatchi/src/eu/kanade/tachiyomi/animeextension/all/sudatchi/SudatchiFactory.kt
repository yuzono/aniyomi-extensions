package eu.kanade.tachiyomi.animeextension.all.sudatchi

import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class SudatchiFactory : AnimeSourceFactory {
    override fun createSources() = listOf(
        Sudatchi(),
        Sudatchi("Sudatchi (NSFW)", mature = true),
    )
}
