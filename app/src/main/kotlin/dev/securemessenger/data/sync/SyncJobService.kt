package dev.securemessenger.data.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dagger.hilt.android.AndroidEntryPoint
import dev.securemessenger.data.AuthRepository
import dev.securemessenger.data.MessageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SyncJobService : JobService() {
    @Inject lateinit var accounts: AuthRepository
    @Inject lateinit var messages: MessageRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            try {
                if (accounts.restore() != null) {
                    MessageNotifier.showMessages(this@SyncJobService, messages.sync())
                }
                jobFinished(params, false)
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                jobFinished(params, true)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val JOB_ID = 704173
        const val SYNC_INTERVAL_MILLIS = 15 * 60 * 1000L

        fun schedule(context: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(SYNC_INTERVAL_MILLIS)
                .setPersisted(true)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}
