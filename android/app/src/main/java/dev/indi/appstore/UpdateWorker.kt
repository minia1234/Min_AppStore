package dev.indi.appstore

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = applicationContext.storeRepository
        if (repo.origin.isBlank()) return@withContext Result.success()
        try {
            repo.refresh()
            val cache = repo.dao.catalog() ?: return@withContext Result.success()
            val catalog = repo.gson.fromJson(cache.payload, Catalog::class.java)
            val installed = repo.installed(catalog.apps)
            val updates = catalog.apps.filter { app ->
                val current = installed[app.packageName]
                current != null && app.latest?.let { it.versionCode > current && it.minSdk <= android.os.Build.VERSION.SDK_INT } == true
            }
            if (updates.isEmpty()) {
                NotificationManagerCompat.from(applicationContext).cancel(1)
            } else if (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                val intent = PendingIntent.getActivity(applicationContext, 0,
                    Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                NotificationManagerCompat.from(applicationContext).notify(1,
                    NotificationCompat.Builder(applicationContext, "updates").setSmallIcon(R.drawable.ic_store)
                        .setContentTitle("${updates.size}개 앱의 업데이트가 있습니다")
                        .setContentText(updates.take(3).joinToString { it.name })
                        .setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true).build())
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }
}
