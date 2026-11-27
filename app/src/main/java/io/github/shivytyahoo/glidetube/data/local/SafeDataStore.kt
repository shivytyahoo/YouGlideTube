package io.github.shivytyahoo.glidetube.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

private const val SAFE_DATA_STORE_TAG = "SafeDataStore"
private const val READ_RETRY_ATTEMPTS = 3
private const val READ_RETRY_DELAY_MS = 100L

/**
 * Every preferences store the app has opened, by file name. Backup, import and reset walk this
 * instead of a hardcoded list, so a store added later is covered without a second edit. A store
 * that was never opened is not here — and has nothing to back up.
 */
object DataStoreRegistry {
    private val stores = ConcurrentHashMap<String, DataStore<Preferences>>()

    internal fun register(
        name: String,
        store: DataStore<Preferences>,
    ) {
        stores.putIfAbsent(name, store)
    }

    fun snapshot(): Map<String, DataStore<Preferences>> = stores.toMap()
}

fun safePreferencesDataStore(name: String): ReadOnlyProperty<Context, DataStore<Preferences>> {
    // The corruption handler runs without a Context; the first read happens after getValue, so the
    // context it needs is waiting here by then.
    val contextRef = AtomicReference<Context?>(null)
    val delegate =
        preferencesDataStore(
            name = name,
            corruptionHandler =
                ReplaceFileCorruptionHandler { corruption ->
                    Log.w(SAFE_DATA_STORE_TAG, "Resetting corrupted preferences DataStore: $name", corruption)
                    preserveCorruptedFile(contextRef.get(), name)
                    emptyPreferences()
                },
        )
    var wrapped: DataStore<Preferences>? = null
    return object : ReadOnlyProperty<Context, DataStore<Preferences>> {
        override fun getValue(
            thisRef: Context,
            property: KProperty<*>,
        ): DataStore<Preferences> =
            wrapped ?: synchronized(this) {
                wrapped ?: ReadSafeDataStore(name, delegate.getValue(thisRef, property)).also {
                    contextRef.set(thisRef.applicationContext)
                    DataStoreRegistry.register(name, it)
                    wrapped = it
                }
            }
    }
}

/**
 * A corrupt file is still the user's data: keep a timestamped copy beside it before the handler
 * replaces it with empty preferences, so the loss is diagnosable instead of silent. Best effort —
 * a copy that fails must never turn a recoverable reset into a crash.
 */
private fun preserveCorruptedFile(
    context: Context?,
    name: String,
) {
    if (context == null) return
    runCatching {
        val dir = File(context.filesDir, "datastore")
        val corrupt = File(dir, "$name.preferences_pb")
        if (!corrupt.isFile) return
        val preserved = File(dir, "$name.broken-${System.currentTimeMillis()}")
        if (!preserved.exists()) corrupt.copyTo(preserved)
        Log.w(SAFE_DATA_STORE_TAG, "Preserved corrupted preferences DataStore: $name -> ${preserved.name}")
    }.onFailure { Log.w(SAFE_DATA_STORE_TAG, "Could not preserve corrupted preferences DataStore: $name", it) }
}

internal class ReadSafeDataStore(
    private val name: String,
    private val delegate: DataStore<Preferences>,
    private val retryDelayMs: Long = READ_RETRY_DELAY_MS,
) : DataStore<Preferences> {
    // A failed read must not crash the collector. Retrying first keeps observers subscribed to
    // later writes; only a read that keeps failing falls back to defaults and ends the stream.
    override val data: Flow<Preferences> =
        delegate.data
            .retryWhen { cause, attempt ->
                val retry = cause is IOException && attempt < READ_RETRY_ATTEMPTS
                if (retry) delay(retryDelayMs * (attempt + 1))
                retry
            }.catch { cause ->
                if (cause !is IOException) throw cause
                Log.w(SAFE_DATA_STORE_TAG, "Reading preferences DataStore failed, using defaults: $name", cause)
                emit(emptyPreferences())
            }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = delegate.updateData(transform)
}
