package dev.indi.appstore

import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class StoreDeviceTest {
    @Test fun downloadedArchiveHasThePublishedSigningCertificate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "apks/signature-check.apk")
        org.junit.Assume.assumeTrue("USB integration APK fixture is required", file.exists())
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)!!
        val certificates = info.signingInfo?.apkContentsSigners.orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex()
        }.toSet()
        assertEquals("Published v1 APK signing identity on actual Android PackageManager", setOf("ff4876b1492f2eebba6f0ef460e6f065fc33c328fb38f5d2c1e8b4fab17f02cf"), certificates)
    }
    @Test fun cancelledDownloadsCannotBecomeReadyAndQueuedWorkSurvivesDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "queue-check-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, StoreDatabase::class.java, name).build()
        try {
            val entry = DownloadEntry("dev.test.app:1", "dev.test.app", "테스트", 1, "1", total = 3, iconUrl = "", payload = "{}")
            db.storeDao().putDownload(entry)
            db.close()
            db = Room.databaseBuilder(context, StoreDatabase::class.java, name).build()
            assertEquals(entry.id, db.storeDao().nextDownload()?.id)
            db.storeDao().progress(entry.id, 2)
            db.storeDao().status(entry.id, "CANCELLED")
            assertEquals(0, db.storeDao().ready(entry.id, "test.apk"))
            assertNull(db.storeDao().nextDownload())
            assertEquals("CANCELLED", db.storeDao().download(entry.id)?.status)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun onlyInstalledEntriesReleaseTheirApkPath() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "installed-check-${System.nanoTime()}.db"
        val db = Room.databaseBuilder(context, StoreDatabase::class.java, name).build()
        try {
            val dao = db.storeDao()
            dao.putDownload(DownloadEntry("dev.test.app:1", "dev.test.app", "테스트", 1, "1", status = "INSTALLED", total = 3, filePath = "a.apk", iconUrl = "", payload = "{}"))
            dao.putDownload(DownloadEntry("dev.test.app:2", "dev.test.app", "테스트", 2, "2", status = "READY", total = 3, filePath = "b.apk", iconUrl = "", payload = "{}"))
            assertEquals(listOf("dev.test.app:1"), dao.installedFiles().map { it.id })
            dao.clearFile("dev.test.app:1")
            assertTrue(dao.installedFiles().isEmpty())
            assertEquals("b.apk", dao.download("dev.test.app:2")?.filePath)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun actualInstalledApkPassesPinnedIdentityAndRejectsWrongPackageAndSigner() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = MessageDigest.getInstance("SHA-256").digest(info.signingInfo!!.apkContentsSigners.single().toByteArray()).hex()
        val file = File(context.applicationInfo.sourceDir)
        val version = StoreVersion(info.longVersionCode, info.versionName!!, context.applicationInfo.minSdkVersion,
            "0".repeat(64), file.length(), "https://example.test/app.apk", "https://example.test/app.apk", "", "", signer)
        ApkInstaller.validate(context, file, context.packageName, version)
        assertThrows(IllegalStateException::class.java) { ApkInstaller.validate(context, file, "dev.wrong.app", version) }
        assertThrows(IllegalStateException::class.java) { ApkInstaller.validate(context, file, context.packageName, version.copy(signerSha256 = "0".repeat(64))) }
    }
}
