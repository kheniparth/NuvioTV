package com.nuvio.tv.livetv

import android.content.Context
import com.nuvio.tv.livetv.data.LiveTvCache
import com.nuvio.tv.livetv.data.LiveTvSourceProvider
import com.nuvio.tv.livetv.data.SettingsLiveTvSourceProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LiveTvModule {
    @Binds
    @Singleton
    abstract fun sources(impl: SettingsLiveTvSourceProvider): LiveTvSourceProvider

    companion object {
        @Provides
        @Singleton
        @JvmStatic
        fun cache(@ApplicationContext context: Context): LiveTvCache =
            LiveTvCache(File(context.cacheDir, "live_tv"))
    }
}
