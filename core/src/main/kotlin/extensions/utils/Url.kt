package extensions.utils

object UrlUtils {

    fun fixUrl(url: String): String {
        if (url.startsWith("http") ||
            // Do not fix JSON objects when passed as urls.
            url.startsWith("{\"")
        ) {
            return url
        }
        if (url.isEmpty()) {
            return ""
        }

        val startsWithNoHttp = url.startsWith("//")
        return if (startsWithNoHttp) {
            "https:$url"
        } else {
            url.replaceFirst("^(?!https?://)", "https://")
        }
    }

    fun fixUrl(url: String, baseUrl: String): String {
        if (url.startsWith("http") ||
            // Do not fix JSON objects when passed as urls.
            url.startsWith("{\"")
        ) {
            return url
        }
        if (url.isEmpty()) {
            return ""
        }

        val startsWithNoHttp = url.startsWith("//")
        if (startsWithNoHttp) {
            return "https:$url"
        } else {
            if (url.startsWith('/')) {
                return baseUrl + url
            }
            return "$baseUrl/$url"
        }
    }

}
