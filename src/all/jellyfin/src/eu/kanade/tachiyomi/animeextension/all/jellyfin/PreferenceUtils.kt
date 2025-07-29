package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.app.Application
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

private const val RESTART_MESSAGE = "Restart the app to apply the new setting."

/**
 * Returns the [SharedPreferences] associated with current source id
 */
inline fun AnimeHttpSource.getPreferences(
    migration: SharedPreferences.() -> Unit = { },
): SharedPreferences = getPreferences(id).also(migration)

/**
 * Lazily returns the [SharedPreferences] associated with current source id
 */
inline fun AnimeHttpSource.getPreferencesLazy(
    crossinline migration: SharedPreferences.() -> Unit = { },
) = lazy { getPreferences(migration) }

/**
 * Returns the [SharedPreferences] associated with passed source id
 */
@Suppress("NOTHING_TO_INLINE")
inline fun getPreferences(sourceId: Long): SharedPreferences =
    Injekt.get<Application>().getSharedPreferences("source_$sourceId", 0x0000)

/**
 * Delegate to lazily read from preferences, as well as writing to preferences.
 *
 * @param preferences Shared preferences
 * @param key Key for preference
 * @param default Default value for preference
 */
class LazyMutablePreference<T>(
    val preferences: SharedPreferences,
    val key: String,
    val default: T,
) : ReadWriteProperty<Any?, T> {
    private object UninitializedValue

    @Volatile
    private var propValue: Any? = UninitializedValue

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val localValue = propValue

        if (localValue != UninitializedValue) {
            return localValue as T
        }

        return synchronized(this) {
            val localValue2 = propValue

            if (localValue2 != UninitializedValue) {
                localValue2 as T
            } else {
                val initializedValue = when (default) {
                    is String -> preferences.getString(key, default) as T
                    is Int -> preferences.getInt(key, default) as T
                    is Long -> preferences.getLong(key, default) as T
                    is Float -> preferences.getFloat(key, default) as T
                    is Boolean -> preferences.getBoolean(key, default) as T
                    is Set<*> -> preferences.getStringSet(key, default as Set<String>) as T
                    else -> throw IllegalArgumentException("Unsupported type: ${default?.javaClass}")
                }
                propValue = initializedValue
                initializedValue
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        synchronized(this) {
            val editor = preferences.edit()
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
                else -> throw IllegalArgumentException("Unsupported type: ${value?.javaClass}")
            }
            editor.apply()
            propValue = value
        }
    }

    fun updateValue(value: T) {
        synchronized(this) {
            propValue = value
        }
    }
}

/**
 * Create [LazyMutablePreference] delegate
 */
fun <T> SharedPreferences.delegate(key: String, default: T) =
    LazyMutablePreference(this, key, default)

/**
 * Add an [EditTextPreference] preference to the screen
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param getSummary Lambda to get summary based on text value
 * @param dialogMessage Preference dialog message
 * @param inputType Keyboard input type
 * @param validate Validate preference value before applying
 * @param validationMessage Validation message if text isn't valid, based on text value
 * @param restartRequired Show restart required toast on preference change
 * @param lazyDelegate Lazy delegate for preference
 * @param onComplete Run block on completion with text value as parameter
 */
fun PreferenceScreen.addEditTextPreference(
    key: String,
    default: String,
    title: String,
    summary: String,
    getSummary: (String) -> String = { summary },
    dialogMessage: String? = null,
    inputType: Int? = null,
    validate: ((String) -> Boolean)? = null,
    validationMessage: ((String) -> String)? = null,
    restartRequired: Boolean = false,
    lazyDelegate: LazyMutablePreference<String>? = null,
    onComplete: (String) -> Unit = {},
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

        setOnPreferenceChangeListener { _, newValue ->
            if (restartRequired) {
                Toast.makeText(context, RESTART_MESSAGE, Toast.LENGTH_LONG).show()
            }

            val text = newValue as String
            this.summary = getSummary(text)
            lazyDelegate?.updateValue(text)
            onComplete(text)
            true
        }
    }.also(::addPreference)
}

/**
 * Add a [ListPreference] preference to the screen
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param entries Preference entries
 * @param entryValues Preference entry values
 * @param restartRequired Show restart required toast on preference change
 * @param lazyDelegate Lazy delegate for preference
 */
fun PreferenceScreen.addListPreference(
    key: String,
    default: String,
    title: String,
    summary: String,
    entries: List<String>,
    entryValues: List<String>,
    restartRequired: Boolean = false,
    lazyDelegate: LazyMutablePreference<String>? = null,
) {
    ListPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        this.entries = entries.toTypedArray()
        this.entryValues = entryValues.toTypedArray()

        setDefaultValue(default)
        setOnPreferenceChangeListener { _, newValue ->
            if (restartRequired) {
                Toast.makeText(context, RESTART_MESSAGE, Toast.LENGTH_LONG).show()
            }
            lazyDelegate?.updateValue(newValue as String)
            true
        }
    }.also(::addPreference)
}

/**
 * Add a [MultiSelectListPreference] preference to the screen
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param entries Preference entries
 * @param entryValues Preference entry values
 * @param restartRequired Show restart required toast on preference change
 * @param lazyDelegate Lazy delegate for preference
 */
fun PreferenceScreen.addSetPreference(
    key: String,
    default: Set<String>,
    title: String,
    summary: String,
    entries: List<String>,
    entryValues: List<String>,
    restartRequired: Boolean = false,
    lazyDelegate: LazyMutablePreference<Set<String>>? = null,
) {
    MultiSelectListPreference(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        this.entries = entries.toTypedArray()
        this.entryValues = entryValues.toTypedArray()
        setDefaultValue(default)

        setOnPreferenceChangeListener { _, newValue ->
            if (restartRequired) {
                Toast.makeText(context, RESTART_MESSAGE, Toast.LENGTH_LONG).show()
            }
            @Suppress("UNCHECKED_CAST")
            lazyDelegate?.updateValue(newValue as Set<String>)
            true
        }
    }.also(::addPreference)
}

/**
 * Add a [SwitchPreferenceCompat] preference to the screen
 *
 * @param key Preference key
 * @param default Default value for preference
 * @param title Preference title
 * @param summary Preference summary
 * @param restartRequired Show restart required toast on preference change
 * @param lazyDelegate Lazy delegate for preference
 */
fun PreferenceScreen.addSwitchPreference(
    key: String,
    default: Boolean,
    title: String,
    summary: String,
    restartRequired: Boolean = false,
    lazyDelegate: LazyMutablePreference<Boolean>? = null,
) {
    SwitchPreferenceCompat(context).apply {
        this.key = key
        this.title = title
        this.summary = summary
        setDefaultValue(default)

        setOnPreferenceChangeListener { _, newValue ->
            if (restartRequired) {
                Toast.makeText(context, RESTART_MESSAGE, Toast.LENGTH_LONG).show()
            }
            lazyDelegate?.updateValue(newValue as Boolean)
            true
        }
    }.also(::addPreference)
}
