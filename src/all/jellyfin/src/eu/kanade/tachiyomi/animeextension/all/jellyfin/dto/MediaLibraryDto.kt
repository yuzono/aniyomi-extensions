package eu.kanade.tachiyomi.animeextension.all.jellyfin.dto

import kotlinx.serialization.Serializable

@Serializable
data class MediaLibraryDto(
    val name: String,
    val id: String,
)
