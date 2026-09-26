package com.personal.fitnessledger.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object XiaomiSyncScheduler {
    internal const val JOB_ID=740019
    fun schedule(context: Context): Boolean {
        val scheduler=context.getSystemService(JobScheduler::class.java)
        if(scheduler.getPendingJob(JOB_ID)!=null) return true
        return try {
            scheduler.schedule(JobInfo.Builder(JOB_ID,ComponentName(context,XiaomiWeightSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setRequiresBatteryNotLow(true)
                .setPeriodic(2*60*60*1000L,30*60*1000L).setPersisted(true).build())==JobScheduler.RESULT_SUCCESS
        } catch(_: RuntimeException) { false }
    }
    fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
}

/** No foreground service, wake-lock loop, exact alarm or notification scraping. */
class XiaomiWeightSyncJob: JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var work: Job?=null
    override fun onStartJob(params: JobParameters): Boolean {
        work=scope.launch {
            try { XiaomiSyncManager.get(applicationContext).sync(backgroundJob=true) }
            finally { if(isActive) jobFinished(params,false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); work=null; return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
