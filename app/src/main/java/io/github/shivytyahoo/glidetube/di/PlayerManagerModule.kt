package io.github.shivytyahoo.glidetube.di

import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.shivytyahoo.glidetube.data.localmedia.LocalMediaIds
import io.github.shivytyahoo.glidetube.data.localmedia.LocalSubtitles
import io.github.shivytyahoo.glidetube.data.engagement.SessionExclusions
import io.github.shivytyahoo.glidetube.data.video.OfflineSubtitleStore
import io.github.shivytyahoo.glidetube.data.video.VideoDownloadManager
import io.github.shivytyahoo.glidetube.player.EnhancedPlayerManager
import io.github.shivytyahoo.glidetube.player.FeedExclusionsSource
import io.github.shivytyahoo.glidetube.player.LocalCaptionSource
import io.github.shivytyahoo.glidetube.player.LocalCaptions
import io.github.shivytyahoo.glidetube.player.LocalCopySource
import io.github.shivytyahoo.glidetube.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NetworkIoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/**
 * Transitional providers around the player-path singletons so consumers can inject them instead of
 * reaching for the static accessors. Every provider delegates to the existing singleton, so object
 * identity and first-creation timing are unchanged (see CLAUDE.md, DI rule 10).
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerManagerModule {
    @Provides
    fun provideEnhancedPlayerManager(
        videoDownloadManager: VideoDownloadManager,
        sessionExclusions: SessionExclusions,
        localSubtitles: Lazy<LocalSubtitles>,
        offlineSubtitleStore: Lazy<OfflineSubtitleStore>,
    ): EnhancedPlayerManager =
        EnhancedPlayerManager.getInstance().also {
            it.localCopySource =
                LocalCopySource { videoId ->
                    LocalMediaIds.videoUri(videoId)?.toString() ?: videoDownloadManager.localCopyPath(videoId)
                }
            it.localCaptionSource =
                LocalCaptionSource { videoId, path ->
                    val subtitles = localSubtitles.get()
                    val own =
                        if (LocalMediaIds.isLocal(videoId)) subtitles.beside(path) else offlineSubtitleStore.get().load(videoId)
                    LocalCaptions(own + subtitles.picked(videoId), subtitles.offsetMs(videoId))
                }
            it.feedExclusionsSource = FeedExclusionsSource { sessionExclusions.snapshot() }
        }

    @Provides
    @NetworkIoDispatcher
    fun provideNetworkIoDispatcher(): CoroutineDispatcher = PerformanceDispatcher.networkIO

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = PerformanceDispatcher.diskIO
}
