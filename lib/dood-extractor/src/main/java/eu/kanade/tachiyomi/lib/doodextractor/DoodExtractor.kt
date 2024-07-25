package eu.kanade.tachiyomi.lib.doodextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class DoodExtractor(private val client: OkHttpClient) {

    fun videoFromUrl(url: String, quality: String? = null, externalSubs: List<Track> = emptyList(),): Video? {
        return runCatching {
            val urlRegex = Regex("https://(.*?)/[de]/([0-9a-zA-Z]+)").find(url)!!
            var updatedHost = urlRegex.groupValues[1].let {
                if (it.endsWith(".cx") || it.endsWith(".wf")) {
                    "dood.so"
                } else {
                    it
                }
            }
            val mediaId = urlRegex.groupValues[2]
            var webUrl = getUrl(updatedHost, mediaId)
            val headers = doodHeaders(updatedHost)
            val response = client.newCall(GET(webUrl, headers)).execute()
            if (response.request.url.toString() != webUrl) {
                updatedHost = Regex("(?://|\\.)([^/]+)").find(response.request.url.toString())?.groupValues?.get(1) ?: updatedHost
                webUrl = getUrl(updatedHost, mediaId)
            }
            val updatedHeaders = headers.newBuilder().set("referer", webUrl).build()
            val iframeMatch = Regex("""<iframe\s*src="([^"]+)""").find(response.body.string())
            webUrl = if (iframeMatch != null) {
                getUrl(updatedHost, iframeMatch.groupValues[1])
            } else {
                webUrl.replace("/d/", "/e/")
            }
            val html = client.newCall(GET(webUrl, updatedHeaders)).execute().body.string()
            val subMatches = Regex("""dsplayer\.addRemoteTextTrack\(\{src:'([^']+)',\s*label:'([^']*)',kind:'captions'""").findAll(html)
            val subTitles = mutableListOf<Track>()
            subMatches.forEach {
                val src = it.groupValues[1]
                if (it.groupValues[1].length > 1) {
                    subTitles.add(Track(it.groupValues[1], if (src.startsWith("//")) "https:$src" else src))
                }
            }

            val tokenMatch = Regex("""dsplayer\.hotkeys[^']+'([^']+).+?function\s*makePlay.+?return[^?]+([^"]+)""", RegexOption.DOT_MATCHES_ALL).find(html)
            val token = tokenMatch!!.groupValues[2]
            val videoUrl = "https://${updatedHost}${tokenMatch.groupValues[1]}"
            val videoHtml = client.newCall(GET(videoUrl, updatedHeaders)).execute().body.string()
            val vidSrc = if (videoHtml.contains("cloudflarestorage.")) {
                videoHtml.trim()
            } else {
                videoHtml + getRandomString() + token + (System.currentTimeMillis() / 1000).toString()
            }
            Video(vidSrc, "Dood${ if (quality != null) ": $quality" else "" }", vidSrc, headers = updatedHeaders, subtitleTracks = externalSubs.ifEmpty { subTitles })
        }.getOrNull()
    }

    fun videosFromUrl(url: String, quality: String? = null): List<Video> {
        val video = videoFromUrl(url, quality)
        return video?.let(::listOf) ?: emptyList()
    }

    private fun getUrl(host: String, mediaId: String) = "https://$host/d/$mediaId"

    private fun getRandomString(length: Int = 10): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length)
            .map { allowedChars.random() }
            .joinToString("")
    }

    private fun doodHeaders(host: String) = Headers.Builder().apply {
        add("User-Agent", "Aniyomi")
        add("Referer", "https://$host/")
    }.build()
}
