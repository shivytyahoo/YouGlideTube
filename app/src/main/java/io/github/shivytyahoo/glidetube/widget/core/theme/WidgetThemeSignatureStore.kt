package io.github.shivytyahoo.glidetube.widget.core.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.shivytyahoo.glidetube.data.local.safePreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.widgetThemeStore by safePreferencesDataStore(name = "widget_theme")

private val LAST_SIGNATURE = stringPreferencesKey("last_signature")

internal suspend fun Context.lastWidgetThemeSignature(): String? = widgetThemeStore.data.map { it[LAST_SIGNATURE] }.first()

internal suspend fun Context.writeWidgetThemeSignature(signature: String) {
    widgetThemeStore.edit { prefs -> prefs[LAST_SIGNATURE] = signature }
}

private val PREVIEW_STAMP = stringPreferencesKey("preview_stamp")

internal suspend fun Context.lastWidgetPreviewStamp(): String? = widgetThemeStore.data.map { it[PREVIEW_STAMP] }.first()

internal suspend fun Context.writeWidgetPreviewStamp(stamp: String) {
    widgetThemeStore.edit { prefs -> prefs[PREVIEW_STAMP] = stamp }
}
