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
        check(currentOrigin.isNotBlank()) { "먼저 스토어 서버 주소를 설정해 주세요." }
        val cached = dao.catalog()?.takeIf { it.origin == currentOrigin }
        val api = Retrofit.Builder().baseUrl("$currentOrigin/").client(client).build().create(CatalogApi::class.java)
        val response = api.catalog(cached?.etag?.takeIf { it.isNotEmpty() })
        if (response.code() == 304 && cached != null) return@withLock
        check(response.isSuccessful) { "스토어에 연결할 수 없습니다. HTTP ${response.code()}" }
        response.body().use { body ->
            check(body != null) { "서버에서 빈 응답을 받았습니다." }
            check(!body.source().request(4 * 1024 * 1024 + 1L)) { "앱 목록이 허용 크기를 초과했습니다." }
            val payload = body.string()
            val catalog = gson.fromJson(payload, Catalog::class.java)
            CatalogRules.validate(catalog)
            dao.cache(CatalogCache(origin = currentOrigin, payload = payload, etag = response.headers()["ETag"].orEmpty()))
        }
    }

    suspend fun queue(app: StoreApp, version: StoreVersion) {
        CatalogRules.validateVersion(version)
        require(version.minSdk <= Build.VERSION.SDK_INT) { "이 Android 버전에서 설치할 수 없습니다." }
        val installed = installedVersion(app.packageName)
        require(installed == null || version.versionCode >= installed) { "이전 버전으로 낮출 수 없습니다." }
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
                entry.packageName !in active -> dao.status(entry.id, "READY", "설치가 완료되지 않았습니다. 다시 설치할 수 있습니다.")
            }
        }
    }

}
