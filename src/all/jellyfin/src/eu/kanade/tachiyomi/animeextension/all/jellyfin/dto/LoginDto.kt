package eu.kanade.tachiyomi.animeextension.all.jellyfin.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginDto(
    val accessToken: String,
    val sessionInfo: LoginSessionDto,
) {
    @Serializable
    data class LoginSessionDto(
        val userId: String,
    )
}
