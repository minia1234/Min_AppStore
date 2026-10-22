package dev.indi.appstore

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.security.MessageDigest

object ApkInstaller {
    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File, packageName: String, version: StoreVersion) {
        // Android 13's archive parser collects certificates only with GET_SIGNATURES set.
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNATURES
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error(context.getString(R.string.invalid_apk))
        val actualVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installed = try { context.packageManager.getPackageInfo(packageName, flags) }
            catch (_: PackageManager.NameNotFoundException) { null }
        val installedVersion = installed?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
        StoreRules.apkProblem(archive.packageName, actualVersion, signers(archive), packageName,
            version.versionCode, version.signerSha256, installedVersion)?.let { error(context.getString(it)) }
        check((archive.applicationInfo?.minSdkVersion ?: version.minSdk) <= Build.VERSION.SDK_INT) { context.getString(R.string.android_unsupported) }
        check(archive.applicationInfo?.minSdkVersion == version.minSdk) { context.getString(R.string.min_sdk_mismatch) }
        if (installed != null) check(version.signerSha256 in signers(installed, history = true)) { context.getString(R.string.installed_signer_mismatch) }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo, history: Boolean = false): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
            if (history && !it.hasMultipleSigners()) it.signingCertificateHistory else it.apkContentsSigners
        } else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
    }

    suspend fun install(context: Context, entry: DownloadEntry) {
        val repo = context.storeRepository
        val version = repo.gson.fromJson(entry.payload, StoreVersion::class.java)
        val file = File(entry.filePath)
        check(file.canonicalFile.parentFile == File(context.filesDir, "apks").canonicalFile) { context.getString(R.string.unsafe_apk_path) }
        check(file.exists() && file.inputStream().use { StoreRules.verifyBytes(it, version.size, version.sha256) }) { context.getString(R.string.apk_integrity_failed) }
        validate(context, file, entry.packageName, version)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(entry.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, InstallResultActivity::class.java)
                    .setData(Uri.parse("indi-store://install/${Uri.encode(entry.id)}"))
                    .putExtra("downloadId", entry.id)
                val options = if (Build.VERSION.SDK_INT >= 35) ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }.toBundle() else null
                val pending = PendingIntent.getActivity(context, id, callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, options)
                repo.dao.status(entry.id, "INSTALLING")
                session.commit(pending.intentSender)
            }
        } catch (failure: Exception) {
            installer.abandonSession(id)
            repo.dao.status(entry.id, "READY", failure.storeMessage(context))
            throw failure
        }
    }
}
