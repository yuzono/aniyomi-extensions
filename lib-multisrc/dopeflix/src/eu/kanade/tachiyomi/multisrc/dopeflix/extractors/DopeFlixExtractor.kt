package eu.kanade.tachiyomi.multisrc.dopeflix.extractors

import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.multisrc.dopeflix.dto.SourceResponseDto
import eu.kanade.tachiyomi.multisrc.dopeflix.dto.VideoDto
import eu.kanade.tachiyomi.multisrc.dopeflix.dto.VideoLink
import eu.kanade.tachiyomi.network.GET
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class DopeFlixExtractor(private val client: OkHttpClient) {
    private val json: Json by injectLazy()

    companion object {
        private const val SOURCES_PATH = "/ajax/v2/embed-4/getSources?id="

//        private const val SCRIPT_URL = "https://rabbitstream.net/js/player/e4-player.min.js"
        private const val SCRIPT_URL = "https://kerolaunochan.online/js/player/e4_player.min.js"
        private val MUTEX = Mutex()
        private var realIndexPairs: List<List<Int>> = emptyList()

        private fun <R> runLocked(block: () -> R) = runBlocking(Dispatchers.IO) {
            MUTEX.withLock { block() }
        }
    }

    private fun generateIndexPairs(): List<List<Int>> {
        val script = client.newCall(GET(SCRIPT_URL)).execute().body.string()
        return script.substringAfter("const ")
            .substringBefore("()")
            .substringBeforeLast(",")
            .split(",")
            .map {
                val value = it.substringAfter("=")
                when {
                    value.contains("0x") -> value.substringAfter("0x").toInt(16)
                    else -> value.toInt()
                }
            }
            .drop(1)
            .chunked(2)
            .map(List<Int>::reversed) // just to look more like the original script
    }

    private val cacheControl = CacheControl.Builder().noStore().build()
    private val noCacheClient = client.newBuilder()
        .cache(null)
        .build()

    private fun updateKey(): List<List<Int>> {
        val script = noCacheClient.newCall(GET(SCRIPT_URL, cache = cacheControl))
            .execute()
            .body.string()
        val regex =
            Regex("case\\s*0x[0-9a-f]+:(?![^;]*=partKey)\\s*\\w+\\s*=\\s*(\\w+)\\s*,\\s*\\w+\\s*=\\s*(\\w+);")
        val matches = regex.findAll(script).toList()
        val indexPairs = matches.map { match ->
            val var1 = match.groupValues[1]
            val var2 = match.groupValues[2]

            val regexVar1 = Regex(",$var1=((?:0x)?([0-9a-fA-F]+))")
            val regexVar2 = Regex(",$var2=((?:0x)?([0-9a-fA-F]+))")

            val matchVar1 = regexVar1.find(script)?.groupValues?.get(1)?.removePrefix("0x")
            val matchVar2 = regexVar2.find(script)?.groupValues?.get(1)?.removePrefix("0x")

            if (matchVar1 != null && matchVar2 != null) {
                try {
                    listOf(matchVar1.toInt(16), matchVar2.toInt(16))
                } catch (e: NumberFormatException) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }.filter { it.isNotEmpty() }
        val encoded = json.encodeToString(indexPairs)
        return json.decodeFromString<List<List<Int>>>(encoded)
    }

    private fun cipherTextCleaner(data: String): Pair<String, String> {
        val indexPairs = updateKey()

        val (password, ciphertext, _) = indexPairs.fold(Triple("", data, 0)) { previous, item ->
            val start = item.first() + previous.third
            val end = start + item.last()
            val passSubstr = data.substring(start, end)
            val passPart = previous.first + passSubstr
            val cipherPart = previous.second.replace(passSubstr, "")
            Triple(passPart, cipherPart, previous.third + item.last())
        }

        return Pair(ciphertext, password)
    }

    private val mutex = Mutex()

    private var indexPairs: List<List<Int>>
        get() {
            return runLocked {
                if (realIndexPairs.isEmpty()) {
                    realIndexPairs = generateIndexPairs()
                }
                realIndexPairs
            }
        }
        set(value) {
            runLocked {
                if (realIndexPairs.isNotEmpty()) {
                    realIndexPairs = value
                }
            }
        }

    private fun tryDecrypting(ciphered: String, attempts: Int = 0): String {
        if (attempts > 2) throw Exception("PLEASE NUKE DOPEBOX AND SFLIX")
        val (ciphertext, password) = cipherTextCleaner(ciphered)
        return CryptoAES.decrypt(ciphertext, password).ifEmpty {
            indexPairs = emptyList() // force re-creation
            tryDecrypting(ciphered, attempts + 1)
        }
    }

    fun getVideoDto(url: String): VideoDto {
        val id = url.substringAfter("/embed-4/", "")
            .substringBefore("?", "").ifEmpty { throw Exception("I HATE THE ANTICHRIST") }
        val serverUrl = url.substringBefore("/v2/embed")
        val srcRes = client.newCall(
            GET(
                serverUrl + SOURCES_PATH + id,
                headers = Headers.headersOf("x-requested-with", "XMLHttpRequest"),
            ),
        )
            .execute()
            .body.string()

        val resStr = "{\"sources\":\"U2FsdGVkX1/3ukBMzGdkpwHGZ4WF4/uGP+yGVXKgU/rfzmL3H/Z4MjM/T2w9WSkpTPYLEBqKLe87Sd8bYtbnbt8HduJdCGpvGX6V+J5EIv6Tq173qNK93zzk8bcB8oMWIFfeevA88nH161b4lw2emayGA52VwNB3fvRsF1ifdUZvehq5SRNrk6oz1gFSs1wGWc3O7DXn7MG8GfQ0elhQp9cZ2i7VSRox4mdizBh41qkks1GMv+BFaWvv0Vq8Ab85ybta62vB3eddIWhtazWYNPrTC8Mtcwu/t+KLY31oF+ITkHSM77f0cC/2d0MnwsdUjGZqaCklZzRZ7mmCt3KFvjGCBrYY3EU7ZlImQwEYIkGQHDS4m+aCttTYc5PCrZRLeecNBpDvTjHDpugn4sz5ZsENjRZ7PJdKRRVD2p1V97jl0vI3s8hGnMmZMwriEGNtLnpKzftzzf18CbtonhOgK5sxJ46FKE357qUIiejFnxY1CvwrXSkYLWL8JfEZl0Ax7XgHl4JzpS+zwqCzP9MiGAemppxVrUfsY0UuByTe9dp2oFcpIJNR/vg+jDixHbBnaKpzqT4M/Me78RdTeE9YCFnCD5VVsnoXzdSrunzZnzpu6LS5RqC3E1kXaTIURTEpgerWVR1wibiKVccNTtGc7w==\",\"tracks\":[{\"file\":\"https://cca.megafiles.store/c1/54/c1546c96cd60f6e9a74a7bf96bde1933/eng-2.vtt\",\"label\":\"English - English\",\"kind\":\"captions\",\"default\":true},{\"file\":\"https://cca.megafiles.store/c1/54/c1546c96cd60f6e9a74a7bf96bde1933/eng-3.vtt\",\"label\":\"English - English (CC)\",\"kind\":\"captions\"},{\"file\":\"https://cca.megafiles.store/c1/54/c1546c96cd60f6e9a74a7bf96bde1933/fre-5.vtt\",\"label\":\"French - Français (Canada)\",\"kind\":\"captions\"},{\"file\":\"https://cca.megafiles.store/c1/54/c1546c96cd60f6e9a74a7bf96bde1933/fre-6.vtt\",\"label\":\"French - Français (Canada) (SDH)\",\"kind\":\"captions\"},{\"file\":\"https://cca.megafiles.store/c1/54/c1546c96cd60f6e9a74a7bf96bde1933/spa-4.vtt\",\"label\":\"Spanish - Español (Latinoamérica)\",\"kind\":\"captions\"}],\"t\":1,\"server\":18}"
        val data = json.decodeFromString<SourceResponseDto>(resStr)
        if (!data.encrypted) return json.decodeFromString<VideoDto>(srcRes)

        val ciphered = data.sources.jsonPrimitive.content.toString()
        val decrypted = json.decodeFromString<List<VideoLink>>(tryDecrypting(ciphered))
        return VideoDto(decrypted, data.tracks)
    }
}
