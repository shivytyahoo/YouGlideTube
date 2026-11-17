package io.github.shivytyahoo.glidetube.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.shivytyahoo.glidetube.data.local.PlayerPreferences
import io.github.shivytyahoo.glidetube.data.repository.YouTubeRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {
    @Provides
    @Singleton
    fun provideYouTubeRepository(playerPreferences: PlayerPreferences): YouTubeRepository = YouTubeRepository.getInstance(playerPreferences)

    @Provides
    @Singleton
    fun provideHomeFeedCacheRepository(
        @ApplicationContext context: Context,
    ): io.github.shivytyahoo.glidetube.data.local.HomeFeedCacheRepository =
        io.github.shivytyahoo.glidetube.data.local
            .HomeFeedCacheRepository(context)

    // VideoDownloadManager is now @Singleton @Inject — Hilt provides it automatically
    @Provides
    @Singleton
    fun providePlayerPreferences(
        @ApplicationContext context: Context,
    ): io.github.shivytyahoo.glidetube.data.local.PlayerPreferences =
        io.github.shivytyahoo.glidetube.data.local
            .PlayerPreferences(context)

    /** Transitional: the music player manager still reaches the queue store through getInstance, so this hands injected consumers the same instance. */
    @Provides
    @Singleton
    fun provideQueuePersistence(
        @ApplicationContext context: Context,
    ): io.github.shivytyahoo.glidetube.data.local.QueuePersistence =
        io.github.shivytyahoo.glidetube.data.local.QueuePersistence
            .getInstance(context)

}
