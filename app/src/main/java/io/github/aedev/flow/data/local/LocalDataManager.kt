package io.github.aedev.flow.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.theme.CustomTheme
import io.github.aedev.flow.ui.theme.ThemeMode
import io.github.aedev.flow.ui.theme.ThemeVariant
import io.github.aedev.flow.ui.theme.canonicalFamily
import io.github.aedev.flow.ui.theme.defaultVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject

val Context.dataStore: DataStore<Preferences> by safePreferencesDataStore(name = "flow_preferences")

class LocalDataManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private val THEME_MODE = stringPreferencesKey("theme_mode")
            private val ACCENT_COLOR = stringPreferencesKey("accent_color")
            private val VIDEO_QUALITY_WIFI = stringPreferencesKey("quality_wifi")
            private val VIDEO_QUALITY_CELLULAR = stringPreferencesKey("quality_cellular")
            private val BACKGROUND_PLAY = stringPreferencesKey("background_play")
            private val TRENDING_REGION = stringPreferencesKey("trending_region")
            private val LAST_UPDATE_CHECK = stringPreferencesKey("last_update_check")
            private val UPDATE_SKIPPED_VERSION = stringPreferencesKey("update_skipped_version")
            private val UPDATE_PROMPTED_VERSION = stringPreferencesKey("update_prompted_version")
            private val UPDATE_NOTIFIED_VERSION = stringPreferencesKey("update_notified_version")
            private val BEDTIME_REMINDER =
                androidx.datastore.preferences.core
                    .booleanPreferencesKey("bedtime_reminder")
            private val BEDTIME_START_HOUR =
                androidx.datastore.preferences.core
                    .intPreferencesKey("bedtime_start_hour")
            private val BEDTIME_START_MINUTE =
                androidx.datastore.preferences.core
                    .intPreferencesKey("bedtime_start_minute")
            private val BEDTIME_END_HOUR =
                androidx.datastore.preferences.core
                    .intPreferencesKey("bedtime_end_hour") // Optional, mostly for UI
            private val BEDTIME_END_MINUTE =
                androidx.datastore.preferences.core
                    .intPreferencesKey("bedtime_end_minute")

            private val BREAK_REMINDER =
                androidx.datastore.preferences.core
                    .booleanPreferencesKey("break_reminder")
            private val BREAK_FREQUENCY =
                androidx.datastore.preferences.core
                    .intPreferencesKey("break_frequency") // Minutes
            private val WATCH_LEDGER_JSON =
                androidx.datastore.preferences.core
                    .stringPreferencesKey("watch_ledger_json")
            private val WATCH_LEDGER_START =
                androidx.datastore.preferences.core
                    .stringPreferencesKey("watch_ledger_start")

            private val CUSTOM_THEME_COLORS = stringPreferencesKey("custom_theme_colors")
            private val CUSTOM_THEME_PALETTES = stringPreferencesKey("custom_theme_palettes_v2")
            private val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
            private val ACTIVE_CUSTOM_THEME = stringPreferencesKey("custom_theme_id")
            private val ACTIVE_CUSTOM_THEME_LIGHT = stringPreferencesKey("custom_theme_id_light")
            private val ACTIVE_CUSTOM_THEME_DARK = stringPreferencesKey("custom_theme_id_dark")
            private const val LEGACY_CUSTOM_THEME_ID = "custom-android-legacy"
            private val THEME_VARIANT = stringPreferencesKey("theme_variant")
            // v2-only keys: this project is a fresh-look rebuild, so the v1-saved system themes
            // must not drag the old violet default in front of the Morphe palette. Login lives in
            // its own preferences file, so this touches theme only.
            private val SYSTEM_LIGHT_THEME_MODE = stringPreferencesKey("v2_system_light_theme_mode")
            private val SYSTEM_DARK_THEME_MODE = stringPreferencesKey("v2_system_dark_theme_mode")
            private val SYSTEM_DARK_THEME_VARIANT = stringPreferencesKey("system_dark_theme_variant")

        }

        // Update Settings
        val lastUpdateCheck: Flow<Long> =
            context.dataStore.data.map { prefs ->
                prefs[LAST_UPDATE_CHECK]?.toLongOrNull() ?: 0L
            }

        suspend fun setLastUpdateCheck(timestamp: Long) {
            context.dataStore.edit { prefs ->
                prefs[LAST_UPDATE_CHECK] = timestamp.toString()
            }
        }

        val skippedUpdateVersion: Flow<String?> = context.dataStore.data.map { it[UPDATE_SKIPPED_VERSION] }

        suspend fun setSkippedUpdateVersion(version: String) {
            context.dataStore.edit { it[UPDATE_SKIPPED_VERSION] = version }
        }

        val promptedUpdateVersion: Flow<String?> = context.dataStore.data.map { it[UPDATE_PROMPTED_VERSION] }

        suspend fun setPromptedUpdateVersion(version: String) {
            context.dataStore.edit { it[UPDATE_PROMPTED_VERSION] = version }
        }

        val notifiedUpdateVersion: Flow<String?> = context.dataStore.data.map { it[UPDATE_NOTIFIED_VERSION] }

        suspend fun setNotifiedUpdateVersion(version: String) {
            context.dataStore.edit { it[UPDATE_NOTIFIED_VERSION] = version }
        }

        // Theme Settings
        val themeMode: Flow<ThemeMode> =
            context.dataStore.data.map { prefs ->
                parseThemeMode(prefs[THEME_MODE], ThemeMode.SYSTEM)
            }

        suspend fun setThemeMode(mode: ThemeMode) {
            context.dataStore.edit { prefs ->
                prefs[THEME_MODE] = mode.canonicalFamily().name
            }
        }

        val themeVariant: Flow<ThemeVariant> =
            context.dataStore.data.map { prefs ->
                parseThemeVariant(
                    raw = prefs[THEME_VARIANT],
                    fallback = ThemeMode.storedDefaultVariant(prefs[THEME_MODE]) ?: ThemeVariant.DARK,
                )
            }

        suspend fun setThemeVariant(variant: ThemeVariant) {
            context.dataStore.edit { prefs -> prefs[THEME_VARIANT] = variant.name }
        }

        val systemLightThemeMode: Flow<ThemeMode> =
            context.dataStore.data.map { prefs ->
                parseThemeMode(prefs[SYSTEM_LIGHT_THEME_MODE], ThemeMode.MORPHE)
            }

        suspend fun setSystemLightThemeMode(mode: ThemeMode) {
            context.dataStore.edit { prefs ->
                prefs[SYSTEM_LIGHT_THEME_MODE] = mode.canonicalFamily().name
            }
        }

        val systemDarkThemeMode: Flow<ThemeMode> =
            context.dataStore.data.map { prefs ->
                parseThemeMode(prefs[SYSTEM_DARK_THEME_MODE], ThemeMode.MORPHE)
            }

        suspend fun setSystemDarkThemeMode(mode: ThemeMode) {
            context.dataStore.edit { prefs ->
                prefs[SYSTEM_DARK_THEME_MODE] = mode.canonicalFamily().name
            }
        }

        val systemDarkThemeVariant: Flow<ThemeVariant> =
            context.dataStore.data.map { prefs ->
                parseThemeVariant(
                    prefs[SYSTEM_DARK_THEME_VARIANT],
                    systemDarkFallbackVariant(prefs[SYSTEM_DARK_THEME_MODE]),
                )
            }

        suspend fun setSystemDarkThemeVariant(variant: ThemeVariant) {
            context.dataStore.edit { prefs -> prefs[SYSTEM_DARK_THEME_VARIANT] = variant.name }
        }

        /** A stored theme name as today's mode: retired palettes land on their successor, unknown names on [fallback]. */
        private fun parseThemeMode(
            raw: String?,
            fallback: ThemeMode,
        ): ThemeMode = (ThemeMode.fromStored(raw) ?: fallback).canonicalFamily()

        /** The dark slot never falls back to a light style. */
        private fun systemDarkFallbackVariant(raw: String?): ThemeVariant =
            (ThemeMode.storedDefaultVariant(raw) ?: ThemeVariant.DARK).let { if (it == ThemeVariant.LIGHT) ThemeVariant.DARK else it }

        private fun parseThemeVariant(
            raw: String?,
            fallback: ThemeVariant,
        ): ThemeVariant =
            runCatching {
                raw?.let(ThemeVariant::valueOf) ?: fallback
            }.getOrDefault(fallback)

        /**
         * The user's custom themes. Before this list existed there was one custom palette per style;
         * it is read once as a theme named "My theme" until the list is first written.
         */
        val customThemes: Flow<List<CustomTheme>> =
            context.dataStore.data.map { prefs -> readCustomThemes(prefs) }

        /** The custom theme the CUSTOM mode shows: the selected one, or the first when none is selected. */
        val activeCustomTheme: Flow<CustomTheme?> =
            context.dataStore.data.map { prefs ->
                val themes = readCustomThemes(prefs)
                themes.firstOrNull { it.id == prefs[ACTIVE_CUSTOM_THEME] } ?: themes.firstOrNull()
            }

        /**
         * The custom theme one Follow-system slot shows. A slot with no own pick falls
         * back to the shared id, so existing users keep their pick in both slots.
         */
        val activeLightCustomTheme: Flow<CustomTheme?> =
            context.dataStore.data.map { prefs ->
                val themes = readCustomThemes(prefs)
                themes.firstOrNull { it.id == prefs[ACTIVE_CUSTOM_THEME_LIGHT] }
                    ?: themes.firstOrNull { it.id == prefs[ACTIVE_CUSTOM_THEME] }
                    ?: themes.firstOrNull()
            }

        val activeDarkCustomTheme: Flow<CustomTheme?> =
            context.dataStore.data.map { prefs ->
                val themes = readCustomThemes(prefs)
                themes.firstOrNull { it.id == prefs[ACTIVE_CUSTOM_THEME_DARK] }
                    ?: themes.firstOrNull { it.id == prefs[ACTIVE_CUSTOM_THEME] }
                    ?: themes.firstOrNull()
            }

        /** Adds [theme], or replaces the one with its id. */
        suspend fun saveCustomTheme(theme: CustomTheme) {
            context.dataStore.edit { prefs ->
                val themes = readCustomThemes(prefs)
                val updated =
                    if (themes.any { it.id == theme.id }) {
                        themes.map { if (it.id == theme.id) theme else it }
                    } else {
                        (themes + theme).take(CustomTheme.MAX_COUNT)
                    }
                prefs[CUSTOM_THEMES] = CustomThemeCodec.encodeList(updated)
            }
        }

        suspend fun deleteCustomTheme(id: String) {
            context.dataStore.edit { prefs ->
                val remaining = readCustomThemes(prefs).filterNot { it.id == id }
                prefs[CUSTOM_THEMES] = CustomThemeCodec.encodeList(remaining)
                if (prefs[ACTIVE_CUSTOM_THEME] == id) prefs.remove(ACTIVE_CUSTOM_THEME)
                if (prefs[ACTIVE_CUSTOM_THEME_LIGHT] == id) prefs.remove(ACTIVE_CUSTOM_THEME_LIGHT)
                if (prefs[ACTIVE_CUSTOM_THEME_DARK] == id) prefs.remove(ACTIVE_CUSTOM_THEME_DARK)
                if (remaining.isEmpty() && prefs[THEME_MODE] == ThemeMode.CUSTOM.name) prefs[THEME_MODE] = ThemeMode.DARK.name
            }
        }

        suspend fun setActiveCustomTheme(id: String) {
            context.dataStore.edit { prefs -> prefs[ACTIVE_CUSTOM_THEME] = id }
        }

        /** Puts custom theme [id] in one Follow-system slot, leaving the other slot alone. */
        suspend fun setSystemSlotCustomTheme(lightSlot: Boolean, id: String) {
            context.dataStore.edit { prefs ->
                prefs[if (lightSlot) ACTIVE_CUSTOM_THEME_LIGHT else ACTIVE_CUSTOM_THEME_DARK] = id
            }
        }

        /** Selects [id] and switches the app to it in [variant]. */
        suspend fun useCustomTheme(
            id: String,
            variant: ThemeVariant,
        ) {
            context.dataStore.edit { prefs ->
                prefs[ACTIVE_CUSTOM_THEME] = id
                prefs[THEME_MODE] = ThemeMode.CUSTOM.name
                prefs[THEME_VARIANT] = variant.name
            }
        }

        /**
         * Adds [imported] to the list, giving a new id to any that clashes with an existing theme,
         * up to the limit. Returns how many were added.
         */
        suspend fun importCustomThemes(
            imported: List<CustomTheme>,
            newId: () -> String,
        ): Int {
            var added = 0
            context.dataStore.edit { prefs ->
                val themes = readCustomThemes(prefs).toMutableList()
                imported.forEach { theme ->
                    if (themes.size >= CustomTheme.MAX_COUNT) return@forEach
                    themes += if (themes.any { it.id == theme.id }) theme.copy(id = newId()) else theme
                    added++
                }
                prefs[CUSTOM_THEMES] = CustomThemeCodec.encodeList(themes)
            }
            return added
        }

        private fun readCustomThemes(prefs: Preferences): List<CustomTheme> {
            prefs[CUSTOM_THEMES]?.let { return CustomThemeCodec.decode(it) }
            val legacy = decodeLegacyCustomPalettes(prefs[CUSTOM_THEME_PALETTES], prefs[CUSTOM_THEME_COLORS]) ?: return emptyList()
            return listOf(legacyCustomTheme(legacy, LEGACY_CUSTOM_THEME_ID, context.getString(R.string.settings_custom_theme_default_name)))
        }

        // Settings
        val trendingRegion: Flow<String> =
            context.dataStore.data.map { prefs ->
                prefs[TRENDING_REGION] ?: "US"
            }

        suspend fun setTrendingRegion(region: String) {
            context.dataStore.edit { prefs ->
                prefs[TRENDING_REGION] = region
            }
        }

        val bedtimeReminder: Flow<Boolean> =
            context.dataStore.data.map { prefs ->
                prefs[BEDTIME_REMINDER] ?: false
            }

        val breakReminder: Flow<Boolean> =
            context.dataStore.data.map { prefs ->
                prefs[BREAK_REMINDER] ?: false
            }

        private val ledgerJson = Json { ignoreUnknownKeys = true }

        /** Real per-day watch time, keyed by ISO date; empty until the first session lands. */
        val watchLedger: Flow<Map<LocalDate, Long>> =
            context.dataStore.data.map { prefs ->
                val raw = prefs[WATCH_LEDGER_JSON] ?: return@map emptyMap()
                runCatching {
                    ledgerJson.decodeFromString<Map<String, Long>>(raw)
                        .mapNotNull { (day, ms) ->
                            runCatching { LocalDate.parse(day) }.getOrNull()?.let { it to ms }
                        }.toMap()
                }.getOrDefault(emptyMap())
            }

        /** First day the ledger covers; null until any session is recorded. */
        val watchLedgerStart: Flow<LocalDate?> =
            context.dataStore.data.map { prefs ->
                prefs[WATCH_LEDGER_START]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            }

        /** Adds [ms] of real watch time to [day]'s ledger entry, keeping the last 30 days. */
        suspend fun addWatchLedgerMs(day: LocalDate, ms: Long) {
            if (ms <= 0) return
            context.dataStore.edit { prefs ->
                val current =
                    runCatching {
                        prefs[WATCH_LEDGER_JSON]?.let {
                            ledgerJson.decodeFromString<Map<String, Long>>(it)
                        }.orEmpty()
                    }.getOrDefault(emptyMap())
                val cutoff = day.minusDays(30)
                val updated =
                    current.filterKeys { key ->
                        runCatching { LocalDate.parse(key) }.getOrNull()?.let { it >= cutoff } ?: false
                    } + (day.toString() to ((current[day.toString()] ?: 0L) + ms))
                prefs[WATCH_LEDGER_JSON] = ledgerJson.encodeToString(updated)
                if (prefs[WATCH_LEDGER_START] == null) prefs[WATCH_LEDGER_START] = day.toString()
            }
        }

        suspend fun setBedtimeReminder(enabled: Boolean) {
            context.dataStore.edit { prefs ->
                prefs[BEDTIME_REMINDER] = enabled
            }
        }

        val bedtimeStartHour: Flow<Int> = context.dataStore.data.map { prefs -> prefs[BEDTIME_START_HOUR] ?: 23 } // Default 11 PM
        val bedtimeStartMinute: Flow<Int> = context.dataStore.data.map { prefs -> prefs[BEDTIME_START_MINUTE] ?: 0 }
        val bedtimeEndHour: Flow<Int> = context.dataStore.data.map { prefs -> prefs[BEDTIME_END_HOUR] ?: 7 } // Default 7 AM
        val bedtimeEndMinute: Flow<Int> = context.dataStore.data.map { prefs -> prefs[BEDTIME_END_MINUTE] ?: 0 }

        suspend fun setBedtimeSchedule(
            startHour: Int,
            startMinute: Int,
            endHour: Int,
            endMinute: Int,
        ) {
            context.dataStore.edit { prefs ->
                prefs[BEDTIME_START_HOUR] = startHour
                prefs[BEDTIME_START_MINUTE] = startMinute
                prefs[BEDTIME_END_HOUR] = endHour
                prefs[BEDTIME_END_MINUTE] = endMinute
            }
        }

        suspend fun setBreakReminder(enabled: Boolean) {
            context.dataStore.edit { prefs ->
                prefs[BREAK_REMINDER] = enabled
            }
        }

        val breakFrequency: Flow<Int> = context.dataStore.data.map { prefs -> prefs[BREAK_FREQUENCY] ?: 30 } // Default 30 min

        suspend fun setBreakFrequency(minutes: Int) {
            context.dataStore.edit { prefs ->
                prefs[BREAK_FREQUENCY] = minutes
            }
        }


    }
