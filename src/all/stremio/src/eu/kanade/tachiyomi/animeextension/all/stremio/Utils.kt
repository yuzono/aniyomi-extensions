package eu.kanade.tachiyomi.animeextension.all.stremio

import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.stremio.Stremio.Companion.ADDONS_DEFAULT
import eu.kanade.tachiyomi.animeextension.all.stremio.Stremio.Companion.ADDONS_KEY
import eu.kanade.tachiyomi.animeextension.all.stremio.Stremio.Companion.AUTHKEY_KEY
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit.MINUTES

private val JSON_INSTANCE: Json by injectLazy()
private val APP: Application by injectLazy()
private val HANDLER by lazy { Handler(Looper.getMainLooper()) }

fun JsonObject.toBody(): RequestBody {
    return JSON_INSTANCE.encodeToString(this)
        .toRequestBody("application/json; charset=utf-8".toMediaType())
}

fun displayToast(message: String, length: Int = Toast.LENGTH_SHORT) {
    HANDLER.post {
        Toast.makeText(APP, message, length).show()
    }
}

val SharedPreferences.authKey
    get() = getString(AUTHKEY_KEY, "")!!

val SharedPreferences.addons
    get() = getString(ADDONS_KEY, ADDONS_DEFAULT)!!

fun PreferenceScreen.addEditTextPreference(
    title: String,
    default: String,
    summary: String,
    getSummary: (String) -> String = { summary },
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: ((String) -> String)? = null,
    key: String = title,
    restartRequired: Boolean = false,
    onComplete: () -> Unit = {},
) {
    EditTextPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        this.setDefaultValue(default)
        this.dialogTitle = title
        this.dialogMessage = dialogMessage

        setOnBindEditTextListener { editText ->
            if (inputType != null) {
                editText.inputType = inputType
            }

            if (validate != null) {
                editText.addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                        override fun afterTextChanged(editable: Editable?) {
                            requireNotNull(editable)

                            val text = editable.toString()
                            val isValid = text.isBlank() || validate(text)

                            editText.error = if (!isValid) validationMessage?.invoke(text) else null
                            editText.rootView.findViewById<Button>(android.R.id.button1)
                                ?.isEnabled = editText.error == null
                        }
                    },
                )
            }
        }

        setOnPreferenceChangeListener { pref, newValue ->
            try {
                val text = newValue as String
                val result = text.isBlank() || validate?.invoke(text) != false

                if (result) {
                    if (restartRequired) {
                        Toast.makeText(context, "Restart Aniyomi to apply new setting.", Toast.LENGTH_LONG).show()
                    }

                    this.summary = getSummary(newValue)
                }

                onComplete()

                result
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }.also(::addPreference)
}

// TODO: Remove with ext lib 16

private val DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10, MINUTES).build()
private val DEFAULT_HEADERS = Headers.Builder().build()
private val DEFAULT_BODY: RequestBody = FormBody.Builder().build()

suspend fun OkHttpClient.get(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response {
    return newCall(GET(url, headers, cache)).awaitSuccess()
}

suspend fun OkHttpClient.get(
    url: HttpUrl,
    headers: Headers = DEFAULT_HEADERS,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response {
    return newCall(GET(url, headers, cache)).awaitSuccess()
}

suspend fun OkHttpClient.post(
    url: String,
    headers: Headers = DEFAULT_HEADERS,
    body: RequestBody = DEFAULT_BODY,
    cache: CacheControl = DEFAULT_CACHE_CONTROL,
): Response {
    return newCall(POST(url, headers, body, cache)).awaitSuccess()
}
