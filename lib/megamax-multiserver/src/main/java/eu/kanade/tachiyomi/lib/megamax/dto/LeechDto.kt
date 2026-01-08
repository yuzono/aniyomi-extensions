package eu.kanade.tachiyomi.lib.megamaxmultiserver.dto

import kotlinx.serialization.Serializable

@Serializable
data class LeechResponse(
    val props: Props2,
)

@Serializable
data class Props2(
    val streams: Streams2
)

@Serializable
data class Streams2(
    val data: List<Data2>,
    val msg: String,
    val status: String,
)

@Serializable
data class Data2(
    val file: String,
    val label: String,
    val size: Long,
    val type: String
)
