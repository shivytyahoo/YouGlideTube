package io.github.shivytyahoo.glidetube.widget.core

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.shivytyahoo.glidetube.widget.core.refresh.WidgetContentSync
import io.github.shivytyahoo.glidetube.widget.downloads.DownloadsSource
import io.github.shivytyahoo.glidetube.widget.onrepeat.OnRepeatSource
import io.github.shivytyahoo.glidetube.widget.playlist.PlaylistWidgetSource
import io.github.shivytyahoo.glidetube.widget.recent.RecentlyPlayedSource

/**
 * Glance widgets can't use constructor injection (the framework instantiates them),
 * so Hilt singletons are reached through this entry point.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun recentlyPlayedSource(): RecentlyPlayedSource

    fun downloadsSource(): DownloadsSource

    fun onRepeatSource(): OnRepeatSource

    fun playlistWidgetSource(): PlaylistWidgetSource


    fun widgetContentSync(): WidgetContentSync
}

fun widgetEntryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
