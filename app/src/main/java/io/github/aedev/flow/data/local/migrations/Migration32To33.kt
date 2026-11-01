package io.github.aedev.flow.data.local.migrations

import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec

@DeleteTable.Entries(
    DeleteTable(tableName = "subscription_groups"),
    DeleteTable(tableName = "recognition_history"),
    DeleteTable(tableName = "notes"),
    DeleteTable(tableName = "sync_log"),
    DeleteTable(tableName = "sync_peers"),
    DeleteTable(tableName = "playlists"),
    DeleteTable(tableName = "playlist_video_cross_ref"),
    DeleteTable(tableName = "watch_history"),
    DeleteTable(tableName = "notifications"),
    DeleteTable(tableName = "home_feed_cache"),
    DeleteTable(tableName = "videos"),
    DeleteTable(tableName = "subscription_feed_cache"),
    DeleteTable(tableName = "music_home_cache"),
    DeleteTable(tableName = "music_home_chips_cache"),
    DeleteTable(tableName = "music_graph_track"),
    DeleteTable(tableName = "music_graph_artist"),
    DeleteTable(tableName = "music_graph_album"),
    DeleteTable(tableName = "music_graph_playlist"),
    DeleteTable(tableName = "music_graph_edge"),
)
class Migration32To33 : AutoMigrationSpec
