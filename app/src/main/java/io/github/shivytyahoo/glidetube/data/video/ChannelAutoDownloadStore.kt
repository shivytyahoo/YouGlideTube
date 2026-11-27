package io.github.shivytyahoo.glidetube.data.video

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.shivytyahoo.glidetube.data.local.safePreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.channelAutoDownloadDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "channel_auto_download")

/** One channel opted into "Save new videos", with the name the list shows it under. */
data class OptedInChannel(
    val channelId: String,
    val name: String,
    val optedInAtMs: Long,
)

private const val CHANNEL_NAME_SUFFIX = ":name"

/**
 * Per-channel "Save new videos": a channel id maps to the epoch it was opted in at, so the runner
 * only ever saves uploads published after the opt-in. Separate from the open-video auto-download,
 * which only ever saves a video the viewer opened.
 */
@Singleton
class ChannelAutoDownloadStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val store get() = context.channelAutoDownloadDataStore

        /** Channel id to opt-in epoch; empty when nothing opted in. */
        val optedIn: Flow<Map<String, Long>> =
            store.data.map { prefs ->
                prefs
                    .asMap()
                    .entries
                    .mapNotNull { (key, value) ->
                        (value as? Long)?.let { key.name to it }
                    }.toMap()
            }

        /** Opted-in channels, newest opt-in first, for the Downloads settings list. */
        val channels: Flow<List<OptedInChannel>> =
            store.data.map { prefs ->
                prefs
                    .asMap()
                    .entries
                    .mapNotNull { (key, value) ->
                        (value as? Long)?.let { epoch ->
                            OptedInChannel(
                                channelId = key.name,
                                name = prefs[stringPreferencesKey(key.name + CHANNEL_NAME_SUFFIX)] ?: key.name,
                                optedInAtMs = epoch,
                            )
                        }
                    }.sortedByDescending { it.optedInAtMs }
            }

        fun isOptedIn(channelId: String): Flow<Boolean> = optedIn.map { channelId in it }

        /**
         * Opts [channelId] in at now, or out. The periodic runner is scheduled on the first
         * opt-in and cancelled when the last one leaves, so no work runs for an empty set.
         */
        suspend fun setOptedIn(
            channelId: String,
            channelName: String,
            optedIn: Boolean,
        ) {
            store.edit { prefs ->
                if (optedIn) {
                    prefs[longPreferencesKey(channelId)] = System.currentTimeMillis()
                    prefs[stringPreferencesKey(channelId + CHANNEL_NAME_SUFFIX)] = channelName
                } else {
                    prefs.remove(longPreferencesKey(channelId))
                    prefs.remove(stringPreferencesKey(channelId + CHANNEL_NAME_SUFFIX))
                }
            }
            if (this.optedIn.first().isEmpty()) {
                ChannelAutoDownloadWorker.cancel(context)
            } else {
                ChannelAutoDownloadWorker.schedule(context)
            }
        }
    }
