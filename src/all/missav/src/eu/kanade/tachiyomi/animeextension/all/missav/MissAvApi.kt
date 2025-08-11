package eu.kanade.tachiyomi.animeextension.all.missav

import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MissAvApi {
    internal fun generateUUID(): String {
        // Use Java's built-in UUID generator for better security and simplicity
        return UUID.randomUUID().toString()
    }

    fun searchURL(uuid: String): String {
        val path = "/missav-default/search/users/$uuid/items/?frontend_timestamp=${System.currentTimeMillis()}"

        val signedPath = generateHMACSignature(path, PUBLIC_TOKEN)

        return "$API_URL$signedPath"
    }

    fun recommURL(recommId: String): String {
        val path = "/missav-default/recomms/next/items/$recommId?frontend_timestamp=${System.currentTimeMillis()}"

        val signedPath = generateHMACSignature(path, PUBLIC_TOKEN)

        return "$API_URL$signedPath"
    }

    fun relatedURL(): String {
        val path = "/missav-default/batch/?frontend_timestamp=${System.currentTimeMillis()}"

        val signedPath = generateHMACSignature(path, PUBLIC_TOKEN)

        return "$API_URL$signedPath"
    }

    fun searchData(query: String): String {
        return """{
            "searchQuery":"$query",
            "count":$RESULT_COUNT,
            "scenario":"search",
            "returnProperties":true,
            "includedProperties":[
                "title_en",
                "dm"
            ],
            "cascadeCreate":true
        }
        """.trimIndent()
    }

    val recommData
        get() = """{"count":$RESULT_COUNT,"cascadeCreate":true}"""

    fun relatedData(uuid: String, entryId: String): String {
        return """{
          "requests": [
            {
              "method": "POST",
              "path": "/recomms/items/$entryId/items/",
              "params": {
                "targetUserId": "$uuid",
                "count": $RESULT_COUNT,
                "scenario": "desktop-watch-next-side",
                "returnProperties": true,
                "includedProperties": [
                  "title_en",
                  "dm"
                ],
                "cascadeCreate": true
              }
            },
            {
              "method": "POST",
              "path": "/recomms/items/$entryId/items/",
              "params": {
                "targetUserId": "$uuid",
                "count": $RESULT_COUNT,
                "scenario": "desktop-watch-next-bottom",
                "returnProperties": true,
                "includedProperties": [
                  "title_en",
                  "dm"
                ],
                "cascadeCreate": true
              }
            }
          ],
          "distinctRecomms": true
        }
        """.trimIndent()
    }

    private fun generateHMACSignature(data: String, @Suppress("SameParameterValue") key: String): String {
        return try {
            val secretKeySpec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA1")
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(secretKeySpec)

            val hashBytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            val hexString = hashBytes.joinToString("") { "%02x".format(it) }

            "$data&frontend_sign=$hexString"
        } catch (e: Exception) {
            // Fallback to original path if signing fails
            data
        }
    }

    const val RESULT_COUNT = 24
    private const val API_URL = "https://client-rapi-missav.recombee.com"
    private const val PUBLIC_TOKEN = "Ikkg568nlM51RHvldlPvc2GzZPE9R4XGzaH9Qj4zK9npbbbTly1gj9K4mgRn0QlV"
}
