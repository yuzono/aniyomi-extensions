package eu.kanade.tachiyomi.animeextension.es.hentaila

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class HentailaDto(
    val id: String,
    val slug: String,
    val title: String,
)

// Popular Dto

@Serializable
data class Uses(
    val search_params: List<String>? = null,
    val dependencies: List<String>? = null,
)

@Serializable
data class Node(
    val type: String,
    val data: JsonArray,
    val uses: Uses?,
)

@Serializable
data class HentailaJsonDto(
    val type: String,
    val nodes: List<Node?>,
)
