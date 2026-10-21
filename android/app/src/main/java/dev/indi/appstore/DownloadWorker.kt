package dev.indi.appstore

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val repo = context.storeRepository
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val entry = repo.dao.nextDownload() ?: break
            val directory = File(applicationContext.filesDir, "apks").apply { mkdirs() }
            // Filenames use a hash so catalog strings can never escape app-private storage.
            val key = java.security.MessageDigest.getInstance("SHA-256").digest(entry.id.toByteArray()).hex()
            val file = File(directory, "$key.apk")
            val partial = File(directory, "$key.part")
            try {
                val version = repo.gson.fromJson(entry.payload, StoreVersion::class.java)
                CatalogRules.validateVersion(version)
                repo.dao.progress(entry.id, 0)
                if (file.exists() && file.inputStream().use { StoreRules.verifyBytes(it, version.size, version.sha256) }) {
                    ApkInstaller.validate(applicationContext, file, entry.packageName, version)
                    repo.dao.ready(entry.id, file.absolutePath)
                    continue
                }
                check(directory.usableSpace > version.size + 32L * 1024 * 1024) { "APK를 저장할 공간이 부족합니다." }
                download(entry, version, partial)
                ApkInstaller.validate(applicationContext, partial, entry.packageName, version)
                check(repo.dao.download(entry.id)?.status != "CANCELLED") { "다운로드가 취소되었습니다." }
                if (file.exists()) check(file.delete()) { "기존 다운로드 파일을 교체할 수 없습니다." }
                check(partial.renameTo(file)) { "다운로드 파일을 저장할 수 없습니다." }
                if (repo.dao.ready(entry.id, file.absolutePath) == 0) file.delete()
            } catch (cancelled: CancellationException) {
                partial.delete()
                throw cancelled
            } catch (failure: Exception) {
                partial.delete()
                if (repo.dao.download(entry.id)?.status != "CANCELLED") {
                    repo.dao.status(entry.id, "FAILED", failure.message ?: "다운로드 실패. 다시 시도해 주세요.")
                }
            }
        }
        Result.success()
    }

    private suspend fun download(entry: DownloadEntry, version: StoreVersion, target: File) {
        var lastError: Exception? = null
        for (url in listOf(version.downloadUrl, version.githubUrl).distinct()) {
            currentCoroutineContext().ensureActive()
            if (repo.dao.download(entry.id)?.status == "CANCELLED") throw IOException("다운로드가 취소되었습니다.")
            try {
                val call = repo.client.newCall(Request.Builder().url(url).build())
                call.execute().use { response ->
                    check(response.isSuccessful) { "다운로드 서버 오류: HTTP ${response.code}" }
                    check(response.request.url.isHttps) { "HTTPS 연결이 아닙니다." }
                    val body = response.body ?: throw IOException("다운로드 파일이 없습니다.")
                    if (body.contentLength() >= 0) check(body.contentLength() == version.size) { "APK 크기가 일치하지 않습니다." }
                    var count = 0L
                    var lastProgress = 0L
                    body.byteStream().use { input -> target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            check(count <= version.size) { "APK 크기가 등록된 크기를 초과했습니다." }
                            output.write(buffer, 0, read)
                            val now = System.currentTimeMillis()
                            if (now - lastProgress >= 250) {
                                check(repo.dao.download(entry.id)?.status != "CANCELLED") { "다운로드가 취소되었습니다." }
                                repo.dao.progress(entry.id, count)
                                lastProgress = now
                            }
                        }
                    } }
                    check(target.inputStream().use { StoreRules.verifyBytes(it, version.size, version.sha256) }) { "SHA-256 검증 실패. 파일을 설치하지 않습니다." }
                    return
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { lastError = failure; target.delete() }
        }
        throw lastError ?: IOException("다운로드할 수 없습니다.")
    }
}
