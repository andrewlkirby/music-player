package com.musicplayer.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.musicplayer.data.repository.MusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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

        // Manual re-scan (e.g. Settings' "Rescan All Folders"): unlike
        // enqueueInitialScan's KEEP policy, this always runs — replacing any
        // in-progress scan rather than being skipped by it.
        fun enqueueManualScan(workManager: WorkManager) {
            val request = OneTimeWorkRequestBuilder<MediaScanWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(false)
                        .build()
                )
                .build()
            workManager.enqueueUniqueWork(
                WORK_NAME_INITIAL,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        // The app used to schedule a 6h periodic rescan (WORK_NAME_PERIODIC).
        // Removed in favor of the manual rescan button — the user rarely adds
        // songs, so a recurring background scan was pure battery/CPU cost for
        // no benefit. WorkManager persists unique periodic work across app
        // updates, so devices that already registered it need this explicit
        // cancel; new installs never enqueue it in the first place.
        fun cancelPeriodicScan(workManager: WorkManager) {
            workManager.cancelUniqueWork(WORK_NAME_PERIODIC)
        }
    }
}
