package com.musicplayer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.musicplayer.worker.MediaScanWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MusicPlayerApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    // Coil's defaults cap the disk cache at 2% of free space (max 250MB),
    // which thrashes against a large library's album art (thousands of
    // distinct images keyed by albumId) — art gets evicted and re-decoded
    // from the slow content://media albumart provider on every cold start
    // and scroll-back. Size the disk cache for a large library instead.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            // content:// album art URIs carry no cache headers, so this
            // avoids Coil treating every load as needing revalidation.
            .respectCacheHeaders(false)
            .build()

    override fun onCreate() {
        super.onCreate()
        
        // Register Hilt Worker Factory manually to ensure it's used
        WorkManager.initialize(
            this,
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()
        )

        // Schedule media scan
        val wm = WorkManager.getInstance(this)
        MediaScanWorker.enqueueInitialScan(wm)
        MediaScanWorker.enqueuePeriodicScan(wm)
    }
}
