package io.github.aedev.flow.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.local.AppDatabase
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
    fun provideDownloadDao(database: AppDatabase): io.github.aedev.flow.data.local.dao.DownloadDao = database.downloadDao()

    @Provides
    fun provideDownloadCollectionDao(database: AppDatabase): io.github.aedev.flow.data.local.dao.DownloadCollectionDao =
        database.downloadCollectionDao()
}
