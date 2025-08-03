package eu.kanade.tachiyomi.lib.m3u8server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Real HTTP server for M3U8 processing using NanoHTTPD
 * Compatible with Android and provides actual HTTP endpoints
 */
class M3u8HttpServer(
    port: Int = 0, // 0 means random port
    private val client: OkHttpClient = OkHttpClient()
) : NanoHTTPD(port) {

    public val port: Int
        get() = super.getListeningPort()

    private val tag by lazy { javaClass.simpleName }
    private var isRunning = false

    override fun start() {
        try {
            super.start()
            isRunning = true
            Log.d(tag, "M3U8 HTTP Server started on port $port")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server: ${e.message}")
            throw e
        }
    }

    override fun stop() {
        super.stop()
        isRunning = false
        Log.d(tag, "M3U8 HTTP Server stopped")
    }

    fun isRunning(): Boolean = isRunning

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(tag, "Received request: $method $uri from ${session.remoteIpAddress}")

        val response = when {
            uri.startsWith("/m3u8") -> handleM3u8Request(session)
            uri.startsWith("/segment") -> handleSegmentRequest(session)
            uri.startsWith("/health") -> handleHealthRequest()
            else -> {
                Log.w(tag, "Unknown endpoint: $uri")
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }

        Log.d(tag, "Response status: ${response.status}")
        return response
    }

    private fun handleM3u8Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()

        Log.d(tag, "Processing M3U8 request for URL: $url")

        if (url.isNullOrBlank()) {
            Log.w(tag, "Missing URL parameter in M3U8 request")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        return try {
            Log.d(tag, "Starting M3U8 processing for: $url")
            val processedContent = runBlocking { processM3u8Url(url) }
            Log.d(tag, "M3U8 processing completed successfully, content length: ${processedContent.length}")
            newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", processedContent)
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.first()

        Log.d(tag, "Processing segment request for URL: $url")

        if (url.isNullOrBlank()) {
            Log.w(tag, "Missing URL parameter in segment request")
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        }

        return try {
            Log.d(tag, "Starting segment processing for: $url")
            val segmentData = runBlocking { processSegmentUrl(url) }
            Log.d(tag, "Segment processing completed successfully, data size: ${segmentData.size} bytes")
            val inputStream = ByteArrayInputStream(segmentData)
            newChunkedResponse(Response.Status.OK, "video/mp2t", inputStream)
        } catch (e: Exception) {
            Log.e(tag, "Error processing segment: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleHealthRequest(): Response {
        Log.d(tag, "Health check requested")
        val status = getHealthStatus()
        Log.d(tag, "Health status: $status")
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, status)
    }

    /**
     * Process M3U8 content through the server
     */
    suspend fun processM3u8Url(url: String): String = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Fetching M3U8 content from: $url")
            val m3u8Content = fetchM3u8Content(url)
            Log.d(tag, "Original M3U8 content length: ${m3u8Content.length}")

            val modifiedContent = modifyM3u8Content(m3u8Content, port)
            Log.d(tag, "Modified M3U8 content length: ${modifiedContent.length}")
            Log.d(tag, "M3U8 processing completed successfully")

            modifiedContent
        } catch (e: Exception) {
            Log.e(tag, "Error processing M3U8 URL: ${e.message}", e)
            throw IOException("Error processing m3u8: ${e.message}")
        }
    }

    /**
     * Process segment with automatic detection
     */
    suspend fun processSegmentUrl(url: String): ByteArray = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "Fetching segment from: $url")
            val segmentData = fetchSegmentWithAutoDetection(url)
            Log.d(tag, "Segment processing completed, final size: ${segmentData.size} bytes")
            segmentData
        } catch (e: Exception) {
            Log.e(tag, "Error processing segment URL: ${e.message}", e)
            throw IOException("Error processing segment: ${e.message}")
        }
    }

    /**
     * Health check
     */
    fun getHealthStatus(): String {
        return if (isRunning) {
            "M3U8 HTTP Server is running on port $port"
        } else {
            "M3U8 HTTP Server is not running"
        }
    }

    private suspend fun fetchM3u8Content(url: String): String = withContext(Dispatchers.IO) {
        Log.d(tag, "Making HTTP request to fetch M3U8 content")
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        Log.d(tag, "M3U8 HTTP response code: ${response.code}")

        if (!response.isSuccessful) {
            Log.e(tag, "Failed to fetch M3U8 content, HTTP code: ${response.code}")
            throw IOException("Failed to fetch m3u8: ${response.code}")
        }

        val content = response.body?.string()
        if (content.isNullOrEmpty()) {
            Log.e(tag, "Empty M3U8 response body")
            throw IOException("Empty response body")
        }

        Log.d(tag, "Successfully fetched M3U8 content")
        content
    }

    private suspend fun fetchSegmentWithAutoDetection(url: String): ByteArray = withContext(Dispatchers.IO) {
        Log.d(tag, "Making HTTP request to fetch segment")
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()

        Log.d(tag, "Segment HTTP response code: ${response.code}")

        if (!response.isSuccessful) {
            Log.e(tag, "Failed to fetch segment, HTTP code: ${response.code}")
            throw IOException("Failed to fetch segment: ${response.code}")
        }

        val inputStream = response.body?.byteStream()
        if (inputStream == null) {
            Log.e(tag, "Empty segment response body")
            throw IOException("Empty response body")
        }

        val outputStream = ByteArrayOutputStream()

        // Read first 4KB to detect format
        val buffer = ByteArray(4096)
        val bytesRead = inputStream.read(buffer)
        Log.d(tag, "Read $bytesRead bytes from segment for format detection")

        if (bytesRead > 0) {
            val skipBytes = AutoDetector.detectSkipBytes(buffer)
            Log.d(tag, "AutoDetector determined skip bytes: $skipBytes")

            // Write data from detected offset
            val validBytes = bytesRead - skipBytes
            outputStream.write(buffer, skipBytes, validBytes)
            Log.d(tag, "Wrote $validBytes bytes from detected offset")

            // Copy remaining data
            val remainingBytes = inputStream.copyTo(outputStream)
            Log.d(tag, "Copied $remainingBytes remaining bytes")
        }

        inputStream.close()
        val finalData = outputStream.toByteArray()
        Log.d(tag, "Final segment data size: ${finalData.size} bytes")
        finalData
    }

    private fun modifyM3u8Content(content: String, serverPort: Int): String {
        Log.d(tag, "Modifying M3U8 content for server port: $serverPort")
        val lines = content.lines().toMutableList()
        val modifiedLines = mutableListOf<String>()
        var segmentCount = 0

        for (line in lines) {
            when {
                line.startsWith("#") -> {
                    // Keep comments and headers
                    modifiedLines.add(line)
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    // This is a segment URL
                    val encodedUrl = URLEncoder.encode(line, StandardCharsets.UTF_8.name())
                    val localUrl = "http://localhost:$serverPort/segment?url=$encodedUrl"
                    modifiedLines.add(localUrl)
                    segmentCount++
                }
                else -> {
                    // Keep empty lines
                    modifiedLines.add(line)
                }
            }
        }

        Log.d(tag, "Modified M3U8 content: $segmentCount segments redirected to local server")
        return modifiedLines.joinToString("\n")
    }
}
