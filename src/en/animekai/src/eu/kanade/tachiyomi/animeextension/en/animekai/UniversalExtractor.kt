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
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UniversalExtractor(
    private val client: OkHttpClient,
    private val timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
) {
    private val tag by lazy { javaClass.simpleName }
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(origRequestUrl: String, origRequestHeader: Headers, name: String?, withSub: Boolean = true): List<Video> {
        Log.d(tag, "Fetching videos from: $origRequestUrl")
        val host = origRequestUrl.toHttpUrl().host.substringBefore(".").proper()
        val latch = CountDownLatch(if (withSub) MAX_SUBTITLE_ATTEMPTS else 1)
        var webView: WebView? = null
        var resultUrl = ""
        val subtitleUrls = mutableListOf<String>()
        val playlistUtils by lazy { PlaylistUtils(client, origRequestHeader) }
        val headers = origRequestHeader.toMultimap().mapValues { it.value.getOrNull(0) ?: "" }.toMutableMap()

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
                        if (resultUrl.isBlank() && VIDEO_REGEX.containsMatchIn(url)) {
                            resultUrl = url
                            latch.countDown()
                        }
                        if (SUBTITLE_REGEX.containsMatchIn(url)) {
                            subtitleUrls.add(url)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webView?.loadUrl(origRequestUrl, headers)
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

        val prefix = name ?: host

        return when {
            "m3u8" in resultUrl -> {
                Log.d(tag, "m3u8 URL: $resultUrl")
                playlistUtils.extractFromHls(
                    playlistUrl = resultUrl,
                    referer = origRequestUrl,
                    subtitleList = subtitleList,
                    videoNameGen = { "$prefix: $it" },
                )
            }
            "mpd" in resultUrl -> {
                Log.d(tag, "mpd URL: $resultUrl")
                playlistUtils.extractFromDash(
                    mpdUrl = resultUrl,
                    videoNameGen = { it -> "$prefix: $it" },
                    subtitleList = subtitleList,
                    referer = origRequestUrl,
                )
            }
            "mp4" in resultUrl -> {
                Log.d(tag, "mp4 URL: $resultUrl")
                Video(
                    url = resultUrl,
                    quality = "$prefix: MP4",
                    videoUrl = resultUrl,
                    headers = Headers.headersOf("referer", origRequestUrl),
                    subtitleTracks = subtitleList,
                ).let(::listOf)
            }
            else -> emptyList()
        }
    }

    private fun String.proper(): String {
        return this.replaceFirstChar {
            if (it.isLowerCase()) {
                it.titlecase(
                    Locale.getDefault(),
                )
            } else it.toString()
        }
    }

    private fun extractLabelFromUrl(url: String): String {
        val file = url.substringAfterLast("/")
        return when (val code = file.substringBefore("_").lowercase()) {
            "eng" -> "English"
            "ger", "deu" -> "German"
            "spa" -> "Spanish"
            "fre", "fra" -> "French"
            "ita" -> "Italian"
            "jpn" -> "Japanese"
            "chi", "zho" -> "Chinese"
            "kor" -> "Korean"
            "rus" -> "Russian"
            "ara" -> "Arabic"
            "hin" -> "Hindi"
            "por" -> "Portuguese"
            "vie" -> "Vietnamese"
            "pol" -> "Polish"
            "ukr" -> "Ukrainian"
            "swe" -> "Swedish"
            "ron", "rum" -> "Romanian"
            "ell", "gre" -> "Greek"
            "hun" -> "Hungarian"
            "fas", "per" -> "Persian"
            "tha" -> "Thai"
            else -> code.uppercase()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 10L

        // Used to allow enough intercepted requests for subtitle extraction.
        const val MAX_SUBTITLE_ATTEMPTS = 99

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
    }
}
