package eu.kanade.tachiyomi.animeextension.en.animekai

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class UniversalExtractor(
    private val client: OkHttpClient,
    private val timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
) {
    private val tag by lazy { javaClass.simpleName }
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers, name: String?, withSub: Boolean = true): List<Video> {
        val host = extractHoster(origRequestUrl.toHttpUrl().host).proper()
        val prefix = name ?: host
        Log.d(tag, "Fetching videos for $prefix from: $origRequestUrl")
        val referer = origRequestHeader["Referer"] ?: origRequestUrl.toHttpUrl().toString()

        val latch = CountDownLatch(1)
        var webView: WebView? = null
        val resultUrl = AtomicReference("")
        val subtitleUrls = mutableListOf<String>()
        val playlistUtils by lazy { PlaylistUtils(client, origRequestHeader) }

        try {
            handler.post {
                val newView = WebView(context)
                webView = newView
                with(newView.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    userAgentString = origRequestHeader["User-Agent"]
                }
                newView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!origRequestUrl.contains("autostart=true")) {
                            Log.d(tag, "Page loaded, injecting script")
                            view?.evaluateJavascript(CHECK_SCRIPT) {}
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? {
                        val url = request.url.toString()
                        Log.d(tag, "Intercepted URL: $url")
                        if (VIDEO_REGEX.containsMatchIn(url)) {
                            if (resultUrl.compareAndSet("", url) && !withSub) {
                                if (latch.count > 0) {
                                    latch.countDown()
                                }
                            }
                        }
                        if (SUBTITLE_REGEX.containsMatchIn(url)) {
                            subtitleUrls.add(url)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                // Build HTML with iframe
                val html = """
                    <html>
                      <body style="margin:0;padding:0;">
                        <iframe src="$origRequestUrl" width="100%" height="100%" frameborder="0" style="display:block;min-height:100vh;"/>
                      </body>
                    </html>
                """.trimIndent()
                webView?.loadDataWithBaseURL(referer, html, "text/html", "UTF-8", null)
            }

            latch.await(timeoutSec, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(tag, "Error while waiting for video URL", e)
        } finally {
            handler.post {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            }
        }

        val subtitleList = subtitleUrls.distinct().map { subUrl ->
            val label = extractLabelFromUrl(subUrl)
            Track(subUrl, label)
        }

        val finalResultUrl = resultUrl.get()
        return when {
            "m3u8" in finalResultUrl -> {
                Log.d(tag, "m3u8 URL: $finalResultUrl")
                playlistUtils.extractFromHls(
                    playlistUrl = finalResultUrl,
                    referer = origRequestUrl,
                    subtitleList = subtitleList,
                    videoNameGen = { "$prefix: $it" },
                )
            }
            "mpd" in finalResultUrl -> {
                Log.d(tag, "mpd URL: $finalResultUrl")
                playlistUtils.extractFromDash(
                    mpdUrl = finalResultUrl,
                    videoNameGen = { it -> "$prefix: $it" },
                    subtitleList = subtitleList,
                    referer = origRequestUrl,
                )
            }
            "mp4" in finalResultUrl -> {
                Log.d(tag, "mp4 URL: $finalResultUrl")
                Video(
                    url = finalResultUrl,
                    quality = "$prefix: MP4",
                    videoUrl = finalResultUrl,
                    headers = Headers.headersOf("referer", origRequestUrl),
                    subtitleTracks = subtitleList,
                ).let(::listOf)
            }
            else -> emptyList()
        }
    }

    /**
     * Extracts the main domain segment from a host string.
     * For example, "www.megaup.live" -> "megaup"
     */
    private fun extractHoster(host: String): String {
        val parts = host.split(".")
        return when {
            parts.size >= 2 -> parts[parts.size - 2]
            else -> host
        }
    }

    private fun String.proper(): String {
        return this.replaceFirstChar {
            if (it.isLowerCase()) {
                it.titlecase()
            } else it.toString()
        }
    }

    private fun extractLabelFromUrl(url: String): String {
        val file = url.substringAfterLast("/")
        return file.split("_").firstNotNullOfOrNull { LANG_MAP[it.lowercase()] } ?: file
    }

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 10L

        private val VIDEO_REGEX by lazy { Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$", RegexOption.IGNORE_CASE) }

        // subs/ita_6.vtt; subs/ai_eng_3.vtt
        private val SUBTITLE_REGEX by lazy { Regex(""".*/subs/\w+\.vtt(\?.*)?$""", RegexOption.IGNORE_CASE) }
        private val CHECK_SCRIPT by lazy {
            """
            (() => {
                const btn = document.querySelector('button, .vjs-big-play-button');
                if (btn) btn.click();
                return "clicked";
            })();
            """.trimIndent()
        }

        private val LANG_MAP = mapOf(
            "eng" to "English",
            "ger" to "German", "deu" to "German",
            "spa" to "Spanish",
            "fre" to "French", "fra" to "French",
            "ita" to "Italian",
            "jpn" to "Japanese",
            "chi" to "Chinese", "zho" to "Chinese",
            "kor" to "Korean",
            "rus" to "Russian",
            "ara" to "Arabic",
            "hin" to "Hindi",
            "por" to "Portuguese",
            "vie" to "Vietnamese",
            "pol" to "Polish",
            "ukr" to "Ukrainian",
            "swe" to "Swedish",
            "ron" to "Romanian", "rum" to "Romanian",
            "ell" to "Greek", "gre" to "Greek",
            "hun" to "Hungarian",
            "fas" to "Persian", "per" to "Persian",
            "tha" to "Thai",
        )
    }
}
