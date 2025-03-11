package eu.kanade.tachiyomi.animeextension.all.stremio.addon.dto

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
data class AddonResultDto(
    val addons: List<AddonDto>,
)

@Serializable
data class AddonDto(
    val transportUrl: String,
    val manifest: ManifestDto,
) {
    fun getTransportUrl(): HttpUrl {
        val url = transportUrl.toHttpUrl()

        return url.newBuilder()
            .removePathSegment(url.pathSize - 1)
            .build()
    }
}

@Serializable
data class ManifestDto(
    val name: String,
    val types: List<String>,

    @Serializable(with = ResourceListSerializer::class)
    val resources: List<ResourceDto>,
    val catalogs: List<CatalogDto>,
    val idPrefixes: List<String>? = null,
) {
    fun isValidResource(resourceType: AddonResource, entryType: String, id: String): Boolean {
        val isValid: (ResourceDto) -> Boolean = { r ->
            r.name == resourceType &&
                r.types?.any { it.equals(entryType, true) } != false &&
                r.idPrefixes?.any { id.startsWith(it, true) } != false
        }

        if (resources.none(isValid)) {
            return false
        }

        idPrefixes?.let {
            return it.any { prefix -> id.startsWith(prefix, true) }
        }

        return resources.any(isValid)
    }
}
