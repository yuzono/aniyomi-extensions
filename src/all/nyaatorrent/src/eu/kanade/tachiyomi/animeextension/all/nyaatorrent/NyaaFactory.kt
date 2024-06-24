package eu.kanade.tachiyomi.animeextension.all.nyaatorrent

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class NyaaFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> {
        val sources = mutableListOf<AnimeSource>()

        val firstExtension = NyaaTorrent("Nyaa (Torrent)", "https://nyaa.si/", 1)
        sources.add(firstExtension)

        val secondExtension = NyaaTorrent("Nyaa Sukebein (Torrent)", "https://sukebei.nyaa.si/", 2)
        sources.add(secondExtension)

        return sources
    }
}
