package eu.kanade.tachiyomi.torrentutils

import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo

object TorrentUtils {
    fun getTorrentInfo(
        url: String,
        title: String,
    ): TorrentInfo {
        throw Exception("Stub!")
    }

    fun getTorrentPlayUrl(
        torrent: TorrentInfo,
        indexFile: Int = 0,
    ): String {
        throw Exception("Stub!")
    }
}
