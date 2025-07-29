package extensions.utils

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
    crossinline migration: SharedPreferences.() -> Unit = { }
) = lazy { getPreferences(migration) }

/**
 * Returns the [SharedPreferences] associated with passed source id
 */
@Suppress("NOTHING_TO_INLINE")
inline fun getPreferences(sourceId: Long): SharedPreferences =
    Injekt.get<Application>().getSharedPreferences("source_$sourceId", 0x0000)
