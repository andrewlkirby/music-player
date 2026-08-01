package com.musicplayer.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.musicplayer.data.repository.MusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class MediaScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: MusicRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            repository.scanMediaStore()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry()
            else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME_INITIAL = "media_scan_initial"
        const val WORK_NAME_PERIODIC = "media_scan_periodic"

        fun enqueueInitialScan(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<MediaScanWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME_INITIAL,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun enqueuePeriodicScan(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<MediaScanWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
