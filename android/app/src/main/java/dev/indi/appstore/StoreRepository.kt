package dev.indi.appstore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.Room
import androidx.work.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import java.io.File
import java.util.concurrent.TimeUnit

interface CatalogApi {
    @GET("api/catalog") suspend fun catalog(@Header("If-None-Match") etag: String?): Response<ResponseBody>
}

class StoreRepository(val context: Context) {
    val gson = Gson()
    val db = Room.databaseBuilder(context, StoreDatabase::class.java, "store.db").build()
    val dao = db.storeDao()
    val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).callTimeout(8, TimeUnit.MINUTES)
        .followSslRedirects(false).build()
    private val prefs = context.getSharedPreferences("store", Context.MODE_PRIVATE)
    private val refreshLock = Mutex()
    val origin: String get() = prefs.getString("origin", BuildConfig.STORE_ORIGIN).orEmpty()

    suspend fun configure(value: String) = refreshLock.withLock {
        val normalized = StoreRules.normalizeOrigin(value)
        if (normalized != origin) {
            dao.cancelPending()
            dao.clearCatalog()
            prefs.edit().putString("origin", normalized).commit()
        }
    }

    suspend fun refresh() = refreshLock.withLock {
        val currentOrigin = origin
        check(currentOrigin.isNotBlank()) { context.getString(R.string.origin_required) }
        val cached = dao.catalog()?.takeIf { it.origin == currentOrigin }
        val api = Retrofit.Builder().baseUrl("$currentOrigin/").client(client).build().create(CatalogApi::class.java)
        val response = api.catalog(cached?.etag?.takeIf { it.isNotEmpty() })
        if (response.code() == 304 && cached != null) return@withLock
        check(response.isSuccessful) { context.getString(R.string.store_http_error, response.code()) }
        response.body().use { body ->
            check(body != null) { context.getString(R.string.empty_response) }
            check(!body.source().request(4 * 1024 * 1024 + 1L)) { context.getString(R.string.catalog_too_large) }
            val payload = body.string()
            val catalog = gson.fromJson(payload, Catalog::class.java)
            CatalogRules.validate(catalog)
            dao.cache(CatalogCache(origin = currentOrigin, payload = payload, etag = response.headers()["ETag"].orEmpty()))
        }
    }

    suspend fun queue(app: StoreApp, version: StoreVersion) {
        CatalogRules.validateVersion(version)
        require(version.minSdk <= Build.VERSION.SDK_INT) { context.getString(R.string.android_unsupported) }
        val installed = installedVersion(app.packageName)
        require(installed == null || version.versionCode >= installed) { context.getString(R.string.cannot_downgrade) }
        val id = "${app.packageName}:${version.versionCode}"
        val old = dao.download(id)
        if (old?.status in listOf("QUEUED", "DOWNLOADING")) { scheduleDownloads(); return }
        if (old?.status == "INSTALLING") return
        dao.putDownload(DownloadEntry(id = id, packageName = app.packageName, name = app.name,
            versionCode = version.versionCode, versionName = version.versionName, total = version.size,
            iconUrl = app.iconUrl, payload = gson.toJson(version)))
        scheduleDownloads()
    }

    suspend fun recoverDownloads() { if (dao.nextDownload() != null) scheduleDownloads() }

    private fun scheduleDownloads() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork("downloads", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    suspend fun installed(apps: List<StoreApp>): Map<String, Long> = withContext(Dispatchers.IO) {
        apps.mapNotNull { app -> installedVersion(app.packageName)?.let { app.packageName to it } }.toMap()
    }

    @Suppress("DEPRECATION")
    fun installedVersion(packageName: String): Long? = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) { null }

    suspend fun reconcileInstalls() {
        val active = context.packageManager.packageInstaller.mySessions.mapNotNull { it.appPackageName }.toSet()
        dao.installing().forEach { entry ->
            when {
                (installedVersion(entry.packageName) ?: 0) >= entry.versionCode -> dao.status(entry.id, "INSTALLED")
                entry.packageName !in active -> dao.status(entry.id, "READY", context.getString(R.string.installation_incomplete))
            }
        }
        // Installed APKs are dead weight; reinstalling the same version downloads it again.
        dao.installedFiles().forEach { entry ->
            val file = File(entry.filePath)
            if (file.delete() || !file.exists()) dao.clearFile(entry.id)
        }
    }

}
