package eu.kanade.tachiyomi.animeextension.all.stremio

import java.net.URLEncoder

fun String.urlEncode(): String {
    return URLEncoder.encode(this, "UTF-8")
}

fun String.takeNotBlank(): String? {
    return this.takeIf { it.isNotBlank() }
}
