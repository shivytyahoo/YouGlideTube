package io.github.aedev.flow.data.music.model

import java.util.Calendar

/** Hour-of-day x weekday/weekend bucket for time-of-day music shelves. */
enum class MusicTimeBucket(
    val wireName: String,
) {
    WEEKDAY_MORNING("WeekdayMorning"),
    WEEKDAY_AFTERNOON("WeekdayAfternoon"),
    WEEKDAY_EVENING("WeekdayEvening"),
    WEEKDAY_NIGHT("WeekdayNight"),
    WEEKEND_MORNING("WeekendMorning"),
    WEEKEND_AFTERNOON("WeekendAfternoon"),
    WEEKEND_EVENING("WeekendEvening"),
    WEEKEND_NIGHT("WeekendNight"),
    ;

    companion object {
        fun fromParts(
            hour: Int,
            isWeekend: Boolean,
        ): MusicTimeBucket {
            val slot =
                when (hour) {
                    in 6..11 -> 0
                    in 12..17 -> 1
                    in 18..23 -> 2
                    else -> 3
                }
            return entries[if (isWeekend) 4 + slot else slot]
        }

        /** Buckets use LOCAL time, matching the desktop engine. */
        fun fromTimestamp(timestampMs: Long): MusicTimeBucket {
            val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
            val day = cal.get(Calendar.DAY_OF_WEEK)
            val isWeekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY
            return fromParts(cal.get(Calendar.HOUR_OF_DAY), isWeekend)
        }

        fun fromWire(name: String): MusicTimeBucket? = entries.firstOrNull { it.wireName == name }
    }
}
