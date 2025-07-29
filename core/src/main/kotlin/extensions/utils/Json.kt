package extensions.utils

// From https://github.com/keiyoushi/extensions-source/blob/main/core/src/main/kotlin/keiyoushi/utils/Json.kt

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

val jsonInstance: Json by injectLazy()

/**
 * Parses JSON string into an object of type [T].
 */
inline fun <reified T> String.parseAs(json: Json = jsonInstance): T =
    json.decodeFromString(this)

/**
 * Parses the response body into an object of type [T].
 */
inline fun <reified T> Response.parseAs(json: Json = jsonInstance): T =
    use { json.decodeFromStream(body.byteStream()) }

/**
 * Serializes the object to a JSON string.
 */
inline fun <reified T> T.toJsonString(json: Json = jsonInstance): String =
    json.encodeToString(this)

/**
 * Converts a string into a JSON request body.
 */
fun String.toJsonBody(): RequestBody =
    this.toRequestBody("application/json; charset=utf-8".toMediaType())

/**
 * Converts the object to a JSON request body.
 */
inline fun <reified T> T.toRequestBody(json: Json = jsonInstance): RequestBody =
    this.toJsonString(json).toJsonBody()
