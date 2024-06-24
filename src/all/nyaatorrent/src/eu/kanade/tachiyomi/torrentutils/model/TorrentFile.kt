package eu.kanade.tachiyomi.torrentutils.model

data class TorrentFile(
    val path: String,
    val indexFile: Int,
    val size: Long,
    private val torrentHash: String,
) {
    override fun toString(): String {
        return path
    }

    private fun getFileName(): String {
        throw Exception("Stub!")
    }

    fun toVideoUrl(): String {
        throw Exception("Stub!")
    }
}
