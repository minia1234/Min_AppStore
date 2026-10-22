package dev.indi.appstore

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class InstallResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        process(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); process(intent) }

    @Suppress("DEPRECATION")
    private fun process(callback: Intent) {
        val id = callback.getStringExtra("downloadId") ?: run { finish(); return }
        val status = callback.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (android.os.Build.VERSION.SDK_INT >= 33) callback.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else callback.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmation != null) {
                try { startActivity(confirmation); finish(); return }
                catch (_: Exception) { /* Persist a retryable result if the system confirmation cannot open. */ }
            }
        }
        lifecycleScope.launch {
            storeRepository.dao.status(id, if (status == PackageInstaller.STATUS_SUCCESS) "INSTALLED" else "READY",
                if (status == PackageInstaller.STATUS_SUCCESS) "" else this@InstallResultActivity.getString(R.string.installation_failed))
            finish()
        }
    }
}
