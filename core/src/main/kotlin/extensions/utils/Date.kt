package extensions.utils

// From https://github.com/keiyoushi/extensions-source/blob/main/core/src/main/kotlin/keiyoushi/utils/Date.kt

import java.text.ParseException
import java.text.SimpleDateFormat

@Suppress("NOTHING_TO_INLINE")
inline fun SimpleDateFormat.tryParse(date: String?): Long {
    date ?: return 0L

    return try {
        parse(date)?.time ?: 0L
    } catch (_: ParseException) {
        0L
    }
}
