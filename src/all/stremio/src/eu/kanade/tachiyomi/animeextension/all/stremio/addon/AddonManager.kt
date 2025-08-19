package eu.kanade.tachiyomi.animeextension.all.stremio.addon

import eu.kanade.tachiyomi.animeextension.all.stremio.ResultDto
import eu.kanade.tachiyomi.animeextension.all.stremio.Stremio
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.AddonDto
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.AddonResultDto
import eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto.ManifestDto
import eu.kanade.tachiyomi.util.parallelMapNotNull
import extensions.utils.PreferenceDelegate
import extensions.utils.Source
import extensions.utils.get
import extensions.utils.parseAs
import extensions.utils.post
import extensions.utils.toRequestBody
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("SpellCheckingInspection")
class AddonManager(
    addonDelegate: PreferenceDelegate<String>,
    authKeyDelegate: PreferenceDelegate<String>,
) /* KMK --> : ReadOnlyProperty<Source, List<AddonDto>> KMK <-- */ {
    private val addonValue by addonDelegate
    private val authKeyValue by authKeyDelegate

    private var cachedAddons: String? = null
    private var cachedAuthKey: String? = null
    private var addons: List<AddonDto>? = null

    /**
     * The use of `runBlocking(Dispatchers.IO)` inside the `getValue` method of a property delegate is a potential performance hazard.
     * This will block the thread that accesses the addons property while the addons are being fetched over the network.
     * A safer approach would be to expose a suspend function [getAddons] to retrieve the addons.
     */
    /* KMK -->
    override fun getValue(
        thisRef: Source,
        property: KProperty<*>,
    ): List<AddonDto> {
        val useAddons = addonValue.isNotBlank()
        val hasChanged = when {
            useAddons -> addonValue != cachedAddons
            else -> authKeyValue != cachedAuthKey
        }

        if (hasChanged) {
            addons = runBlocking(Dispatchers.IO) {
                when {
                    useAddons -> getFromPref(thisRef, addonValue)
                    authKeyValue.isNotBlank() -> getFromUser(thisRef, authKeyValue)
                    else -> throw Exception("Addons must be manually added if not logged in")
                }
            }

            if (useAddons) {
                cachedAddons = addonValue
                cachedAuthKey = null
            } else {
                cachedAuthKey = authKeyValue
                cachedAddons = null
            }
        }

        return addons ?: emptyList()
    }
    KMK <-- */

    suspend fun getAddons(thisRef: Source): List<AddonDto> {
        val useAddons = addonValue.isNotBlank()
        val hasChanged = when {
            useAddons -> addonValue != cachedAddons
            else -> authKeyValue != cachedAuthKey
        }

        if (hasChanged) {
            addons = when {
                useAddons -> getFromPref(thisRef, addonValue)
                authKeyValue.isNotBlank() -> getFromUser(thisRef, authKeyValue)
                else -> throw Exception("Addons must be manually added if not logged in")
            }
            cachedAddons = addonValue
            cachedAuthKey = authKeyValue
        }

        return addons ?: emptyList()
    }

    private suspend fun getFromPref(thisRef: Source, addons: String): List<AddonDto> {
        val urls = addons.split("\n")

        return urls.parallelMapNotNull { url ->
            try {
                val manifestUrl = url.replace("stremio://", "https://")
                with(thisRef) {
                    val manifest = thisRef.client.get(manifestUrl).parseAs<ManifestDto>()
                    AddonDto(
                        transportUrl = manifestUrl,
                        manifest = manifest,
                    )
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun getFromUser(thisRef: Source, authKey: String): List<AddonDto> {
        return with(thisRef) {
            val body = buildJsonObject {
                put("authKey", authKey)
                put("type", "AddonCollectionGet")
                put("update", true)
            }.toRequestBody()

            thisRef.client.post("${Stremio.API_URL}/api/addonCollectionGet", body = body)
                .parseAs<ResultDto<AddonResultDto>>().result.addons
        }
    }
}
