package eu.kanade.tachiyomi.animeextension.all.stremio.addon

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animeextension.all.stremio.ResultDto
import eu.kanade.tachiyomi.animeextension.all.stremio.Stremio
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.AddonDto
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.AddonResultDto
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.ManifestDto
import eu.kanade.tachiyomi.animeextension.all.stremio.addons
import eu.kanade.tachiyomi.animeextension.all.stremio.authKey
import eu.kanade.tachiyomi.animeextension.all.stremio.get
import eu.kanade.tachiyomi.animeextension.all.stremio.post
import eu.kanade.tachiyomi.animeextension.all.stremio.toBody
import eu.kanade.tachiyomi.util.parallelMapNotNull
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

class AddonManager(
    private val preferences: SharedPreferences,
    private val client: OkHttpClient,
) {
    val addons: List<AddonDto> by lazy {
        runBlocking {
            if (preferences.addons.isNotBlank()) {
                getFromPref(preferences.addons)
            } else if (preferences.authKey.isNotBlank()) {
                getFromUser(preferences.authKey)
            } else {
                throw Exception("Addons must be manually added if not logged in")
            }
        }
    }

    suspend fun getFromPref(addons: String): List<AddonDto> {
        val urls = addons.split(" ")

        return urls.parallelMapNotNull { url ->
            try {
                val manifest = client.get(
                    url.replace("stremio://", "https://"),
                ).parseAs<ManifestDto>()
                AddonDto(
                    transportUrl = url,
                    manifest = manifest,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun getFromUser(authKey: String): List<AddonDto> {
        val body = buildJsonObject {
            put("authKey", authKey)
            put("type", "AddonCollectionGet")
            put("update", true)
        }.toBody()

        return client.post("${Stremio.API_URL}/api/addonCollectionGet", body = body)
            .parseAs<ResultDto<AddonResultDto>>().result.addons
    }
}
