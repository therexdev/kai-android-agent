package com.kai.agent.tasks

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.kai.agent.MainActivity
import com.kai.agent.data.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class TaskScheduler(private val context: Context) {
    private val manager=WorkManager.getInstance(context)
    fun enqueue(id: String) {
        val request=OneTimeWorkRequestBuilder<AgentRunWorker>().setInputData(workDataOf("id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag("kai-runs").build()
        // APPEND_OR_REPLACE preserves a running worker's cancellation/receipt cleanup before a resume.
        manager.enqueueUniqueWork("kai-run:$id",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }
    fun cancel(id: String) { manager.cancelUniqueWork("kai-run:$id") }
    fun schedule(routine: AgentRoutine) {
        if(!routine.enabled)return
        val request=OneTimeWorkRequestBuilder<RoutineWorker>().setInputData(workDataOf("id" to routine.id,"revision" to routine.revision,"due" to routine.nextRunAt))
            .setInitialDelay((routine.nextRunAt-System.currentTimeMillis()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag("kai-routine:${routine.id}").build()
        manager.enqueueUniqueWork("kai-routine:${routine.id}:${routine.revision}:${routine.nextRunAt}",ExistingWorkPolicy.KEEP,request)
    }
    fun cancelRoutine(id: String) { manager.cancelAllWorkByTag("kai-routine:$id") }
    fun reconcile(state: KaiState) {
        state.runs.filter { it.status==RunStatus.QUEUED }.forEach { enqueue(it.id) }
        state.routines.filter { it.enabled }.forEach(::schedule)
    }
    fun notify(run: AgentRun) {
        if(run.status !in setOf(RunStatus.COMPLETED,RunStatus.NEEDS_APPROVAL,RunStatus.BLOCKED,RunStatus.FAILED))return
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("kai_tasks","KAI tasks and approvals",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=Intent(context,MainActivity::class.java).setAction("com.kai.agent.TASK").putExtra("runId",run.id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending=PendingIntent.getActivity(context,run.id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title=when(run.status) { RunStatus.COMPLETED->"Task finished";RunStatus.NEEDS_APPROVAL->"Action needs your approval";else->"Task needs attention" }
        val public=NotificationCompat.Builder(context,"kai_tasks").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("KAI task update").build()
        val notification=NotificationCompat.Builder(context,"kai_tasks").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(run.title).setContentIntent(pending).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        try { NotificationManagerCompat.from(context).notify(run.id.hashCode(),notification) } catch(_: SecurityException) { /* In-app status remains available. */ }
    }
}
class AgentRunWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel="kai_execution"
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"KAI running tasks",NotificationManager.IMPORTANCE_LOW))
        val cancel=WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification=NotificationCompat.Builder(applicationContext,channel).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("KAI is working").setContentText("Progress is saved. Open KAI to review this task.").setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).addAction(android.R.drawable.ic_media_pause,"Pause",cancel).build()
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(id.hashCode(),notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(id.hashCode(),notification)
    }
    override suspend fun doWork(): Result {
        val handle=KaiStores.acquire(applicationContext)
        return try {
            handle.store.load()
            val id=inputData.getString("id") ?: return Result.failure()
            try { setForeground(getForegroundInfo()) } catch(e: CancellationException) { throw e }
            catch(_: Exception) {
                val run=handle.store.state.value.runs.firstOrNull { it.id==id }
                if(run!=null && !run.terminal) handle.store.setRunStatus(id,RunStatus.PAUSED)
                return Result.success()
            }
            val result=TaskAgentEngine(handle.store).run(id,UUID.randomUUID().toString())
            result?.let { TaskScheduler(applicationContext).notify(it) }
            if(result?.status==RunStatus.QUEUED && runAttemptCount<3) Result.retry()
            else {
                if(result?.status==RunStatus.QUEUED) handle.store.setRunStatus(result.id,RunStatus.PAUSED)
                Result.success()
            }
        } catch(e: CancellationException) {
            withContext(NonCancellable) {
                val runId=inputData.getString("id")
                val run=handle.store.state.value.runs.firstOrNull { it.id==runId }
                if(run?.status==RunStatus.QUEUED) runCatching { handle.store.setRunStatus(run.id,RunStatus.PAUSED) }
            }
            throw e
        } catch(_: Exception) { if(runAttemptCount<3) Result.retry() else Result.failure() } finally { handle.close() }
    }
}
class RoutineWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result {
        val handle=KaiStores.acquire(applicationContext)
        return try {
            val store=handle.store;store.load()
            val id=inputData.getString("id") ?: return Result.failure()
            val run=store.claimRoutine(id,inputData.getLong("revision",-1),inputData.getLong("due",-1))
            val scheduler=TaskScheduler(applicationContext)
            if(run?.status==RunStatus.QUEUED)scheduler.enqueue(run.id)
            store.state.value.routines.firstOrNull { it.id==id }?.let(scheduler::schedule)
            Result.success()
        } catch(e: CancellationException) { throw e } catch(_: Exception) { Result.retry() } finally { handle.close() }
    }
}
