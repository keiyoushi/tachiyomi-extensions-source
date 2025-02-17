package keiyoushi.utils

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
