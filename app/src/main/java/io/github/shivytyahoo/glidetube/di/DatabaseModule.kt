package io.github.shivytyahoo.glidetube.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.shivytyahoo.glidetube.data.local.AppDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase = AppDatabase.getDatabase(context)

    @Provides
    fun provideDownloadDao(database: AppDatabase): io.github.shivytyahoo.glidetube.data.local.dao.DownloadDao = database.downloadDao()

    @Provides
    fun provideDownloadCollectionDao(database: AppDatabase): io.github.shivytyahoo.glidetube.data.local.dao.DownloadCollectionDao =
        database.downloadCollectionDao()
}
