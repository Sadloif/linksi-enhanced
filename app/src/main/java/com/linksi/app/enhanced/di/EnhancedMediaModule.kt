package com.linksi.app.enhanced.di

import android.content.Context
import androidx.work.WorkManager
import com.linksi.app.enhanced.download.DownloadEngine
import com.linksi.app.enhanced.download.WorkManagerDownloadEngine
import com.linksi.app.enhanced.media.ExtractorRegistry
import com.linksi.app.enhanced.media.direct.DirectFileExtractor
import com.linksi.app.enhanced.media.ytdlp.YtDlpExtractor
import com.linksi.app.enhanced.resolver.DataStoreMediaResolver
import com.linksi.app.enhanced.resolver.MediaResolver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Wiring for the download engine (specification section 23).
 *
 * This is a **new** module on purpose: `di/AppModule.kt` belongs to the core app and stays
 * untouched, so the enhanced download module can be removed again by deleting files rather than by
 * editing them.
 *
 * Everything here is lazy. Hilt creates a `@Singleton` only when something first asks for it, so
 * merely installing this module starts no work, opens no connection and registers no service
 * (specification section 26).
 */
@Module
@InstallIn(SingletonComponent::class)
object EnhancedMediaModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        // A stalled connection should not hold a download slot for long...
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // ...but a slow server may legitimately pause between chunks, and this timeout is per read,
        // not for the whole response.
        .readTimeout(60, TimeUnit.SECONDS)
        // No whole-call timeout: a large file is supposed to take a long time.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * The direct-file extractor is a singleton because the registry holds it and the worker calls
     * it directly; it is stateless, so sharing is safe.
     */
    @Provides
    @Singleton
    fun provideDirectFileExtractor(client: OkHttpClient): DirectFileExtractor =
        DirectFileExtractor(client)

    /**
     * The extractor list the media layer sees, best first.
     *
     * The direct-file probe outranks the site engine (100 against 50) because it costs two cheap
     * requests, and it is tried before an engine that has to start a Python interpreter. Creating
     * this list starts nothing: [YtDlpExtractor] only initialises its engine on the first
     * `analyze`, and `isAvailable` merely reads the ABI report and stats two files.
     */
    @Provides
    @Singleton
    fun provideExtractorRegistry(
        directFile: DirectFileExtractor,
        ytDlp: YtDlpExtractor
    ): ExtractorRegistry = ExtractorRegistry(listOf(directFile, ytDlp))

    /**
     * Binds the optional resolver behind a DataStore-backed wrapper. No network client is created
     * and no setting is read until a local extraction has already failed.
     */
    @Provides
    @Singleton
    fun provideMediaResolver(resolver: DataStoreMediaResolver): MediaResolver = resolver

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    /** The engine is bound to its interface so the UI never depends on WorkManager. */
    @Provides
    @Singleton
    fun provideDownloadEngine(engine: WorkManagerDownloadEngine): DownloadEngine = engine
}
