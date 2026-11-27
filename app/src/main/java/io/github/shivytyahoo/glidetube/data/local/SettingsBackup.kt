package io.github.shivytyahoo.glidetube.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The whole settings state as one file: every preferences store the app has opened, each entry
 * tagged with its type so an import can rebuild it. An import validates the file completely
 * before touching anything, and the current files are kept beside the new ones first, so a bad
 * import can be undone by putting the kept files back. Stores never opened in this session are
 * not in the file — they hold nothing but defaults.
 */
@Singleton
class SettingsBackup
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            private const val TAG = "SettingsBackup"
            private const val FORMAT_VERSION = 1
            private const val APP_ID = "youglidetube"
            private val json =
                Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                }
        }

        sealed interface ImportResult {
            data class Restored(
                val stores: Int,
            ) : ImportResult

            data object Invalid : ImportResult
        }

        @Serializable
        private data class BackupFile(
            val format: Int,
            val app: String,
            val exportedAt: Long,
            val stores: Map<String, Map<String, BackupValue>>,
        )

        @Serializable
        private data class BackupValue(
            val type: String,
            val value: JsonElement,
        )

        /** The store names this export covers, for the screen to say what is in the file. */
        fun coveredStores(): List<String> = DataStoreRegistry.snapshot().keys.sorted()

        /** The export as JSON text, ready to write to the file the viewer chose. */
        suspend fun export(): String =
            withContext(Dispatchers.IO) {
                val stores = DataStoreRegistry.snapshot()
                val encoded =
                    stores.entries.associate { (name, store) ->
                        name to
                            store.data.first().asMap().entries.associate { (key, value) ->
                                key.name to encode(value)
                            }
                    }
                json.encodeToString(
                    BackupFile.serializer(),
                    BackupFile(
                        format = FORMAT_VERSION,
                        app = APP_ID,
                        exportedAt = System.currentTimeMillis(),
                        stores = encoded,
                    ),
                )
            }

        /**
         * Validates [text] fully, keeps the current files, then applies it. Nothing is applied
         * when the file is not a backup of this app, or when any value in it is unreadable. A
         * store in the file that is not open in this session is skipped, not failed.
         */
        @Suppress("UNCHECKED_CAST")
        suspend fun import(text: String): ImportResult =
            withContext(Dispatchers.IO) {
                val file =
                    runCatching { json.decodeFromString(BackupFile.serializer(), text) }.getOrNull()
                        ?: return@withContext ImportResult.Invalid
                if (file.format != FORMAT_VERSION || file.app != APP_ID) return@withContext ImportResult.Invalid
                val stores = DataStoreRegistry.snapshot()
                val plan = mutableListOf<Triple<String, DataStore<Preferences>, List<Pair<Preferences.Key<*>, Any>>>>()
                for ((name, entries) in file.stores) {
                    val store = stores[name] ?: continue
                    val decoded = mutableListOf<Pair<Preferences.Key<*>, Any>>()
                    for ((key, encoded) in entries) {
                        decoded += decode(key, encoded) ?: return@withContext ImportResult.Invalid
                    }
                    plan += Triple(name, store, decoded)
                }
                if (plan.isEmpty()) return@withContext ImportResult.Invalid
                preserveCurrent(plan.map { it.first })
                for ((_, store, decoded) in plan) {
                    store.edit { prefs ->
                        prefs.clear()
                        for ((key, value) in decoded) {
                            prefs[key as Preferences.Key<Any>] = value
                        }
                    }
                }
                ImportResult.Restored(plan.size)
            }

        private fun encode(value: Any): BackupValue =
            when (value) {
                is String -> BackupValue("string", JsonPrimitive(value))
                is Int -> BackupValue("int", JsonPrimitive(value))
                is Long -> BackupValue("long", JsonPrimitive(value))
                is Float -> BackupValue("float", JsonPrimitive(value))
                is Boolean -> BackupValue("boolean", JsonPrimitive(value))
                is Set<*> -> BackupValue("stringSet", JsonArray(value.filterIsInstance<String>().map(::JsonPrimitive)))
                else -> throw IllegalArgumentException("Cannot back up a ${value::class.simpleName} preference")
            }

        private fun decode(
            name: String,
            encoded: BackupValue,
        ): Pair<Preferences.Key<*>, Any>? {
            val value = encoded.value
            return when (encoded.type) {
                "string" -> {
                    val primitive = value as? JsonPrimitive ?: return null
                    Pair<Preferences.Key<*>, Any>(stringPreferencesKey(name), primitive.content)
                }

                "int" -> {
                    val primitive = value as? JsonPrimitive ?: return null
                    Pair<Preferences.Key<*>, Any>(intPreferencesKey(name), primitive.intOrNull ?: return null)
                }

                "long" -> {
                    val primitive = value as? JsonPrimitive ?: return null
                    Pair<Preferences.Key<*>, Any>(longPreferencesKey(name), primitive.longOrNull ?: return null)
                }

                "float" -> {
                    val primitive = value as? JsonPrimitive ?: return null
                    Pair<Preferences.Key<*>, Any>(floatPreferencesKey(name), primitive.floatOrNull ?: return null)
                }

                "boolean" -> {
                    val primitive = value as? JsonPrimitive ?: return null
                    Pair<Preferences.Key<*>, Any>(booleanPreferencesKey(name), primitive.booleanOrNull ?: return null)
                }

                "stringSet" -> {
                    val array = value as? JsonArray ?: return null
                    val strings = array.map { (it as? JsonPrimitive)?.content ?: return null }.toSet()
                    Pair<Preferences.Key<*>, Any>(stringSetPreferencesKey(name), strings)
                }

                else -> {
                    null
                }
            }
        }

        // The current files, kept beside the new ones before anything is applied, so a bad import
        // can be undone by putting the kept files back. Best effort — a copy that fails must never
        // turn a validated import into a crash.
        private fun preserveCurrent(names: List<String>) {
            val dir = File(context.filesDir, "datastore")
            for (name in names) {
                runCatching {
                    val current = File(dir, "$name.preferences_pb")
                    if (!current.isFile) return@runCatching
                    val kept = File(dir, "$name.backup-${System.currentTimeMillis()}")
                    if (!kept.exists()) current.copyTo(kept)
                }.onFailure { Log.w(TAG, "Could not preserve $name before import", it) }
            }
        }
    }
