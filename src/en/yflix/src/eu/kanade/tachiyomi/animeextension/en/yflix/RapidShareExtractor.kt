package eu.kanade.tachiyomi.animeextension.en.yflix

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class RapidShareExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val preferences: SharedPreferences,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val jsonMimeType = "application/json".toMediaType()

    private fun getPrefSubLang(): String {
        return preferences.getString(YFlix.PREF_SUB_LANG_KEY, YFlix.PREF_SUB_LANG_DEFAULT)!!
    }

    suspend fun videosFromUrl(url: String, prefix: String): List<Video> {
        val rapidUrl = url.toHttpUrl()
        val token = rapidUrl.pathSegments.last()
        val subtitleUrl = rapidUrl.queryParameter("sub.list")
        val mediaUrl = "${rapidUrl.scheme}://${rapidUrl.host}/media/$token"

        val encryptedResult = try {
            client.newCall(GET(mediaUrl, headers))
                .awaitSuccess()
                .parseAs<EncryptedRapidResponse>()
                .result
        } catch (_: Exception) {
            return emptyList()
        }

        val decryptionBody = buildJsonObject {
            put("text", encryptedResult)
            put("agent", headers["User-Agent"] ?: "")
        }.toString().toRequestBody(jsonMimeType)

        val rapidResult = try {
            client.newCall(POST("https://enc-dec.app/api/dec-rapid", body = decryptionBody))
                .awaitSuccess()
                .parseAs<RapidDecryptResponse>()
                .result
        } catch (_: Exception) {
            return emptyList()
        }

        val subtitleList = try {
            if (subtitleUrl != null) {
                getSubtitles(subtitleUrl)
            } else {
                rapidResult.tracks
                    .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                    .map { Track(it.file, it.label!!) }
            }
        } catch (_: Exception) {
            emptyList()
        }

        val videoSources = rapidResult.sources
        return videoSources.flatMap { source ->
            val videoUrl = source.file
            when {
                videoUrl.contains(".m3u8") -> {
                    playlistUtils.extractFromHls(
                        playlistUrl = videoUrl,
                        referer = "${rapidUrl.scheme}://${rapidUrl.host}/",
                        videoNameGen = { quality -> "$prefix - $quality" },
                        subtitleList = subLangSelect(subtitleList),
                    )
                }
                else -> emptyList()
            }
        }
    }

    private suspend fun getSubtitles(url: String): List<Track> {
        val subHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Origin", "https://rapidshare.cc")
            .set("Referer", "https://rapidshare.cc/")
            .build()
        return try {
            client.newCall(GET(url, subHeaders))
                .awaitSuccess()
                .parseAs<List<RapidShareTrack>>()
                .filter { it.kind == "captions" && it.file.isNotBlank() && it.label != null }
                .map { Track(it.file, it.label!!) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Puts the preferred language subtitle first in the list.
     * The player will likely default to the first subtitle.
     */
    private fun subLangSelect(tracks: List<Track>): List<Track> {
        val language = getPrefSubLang()
        return tracks.sortedByDescending { it.lang.contains(language, true) }
    }
}
