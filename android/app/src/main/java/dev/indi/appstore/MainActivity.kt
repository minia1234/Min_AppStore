package dev.indi.appstore

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val model: StoreViewModel by viewModels()
    private var pendingInstall: String? = null
    private var installationStarting = false
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val unknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val id = pendingInstall
        pendingInstall = null
        if (id != null && packageManager.canRequestPackageInstalls()) lifecycleScope.launch {
            storeRepository.dao.download(id)?.let { install(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingInstall = savedInstanceState?.getString("pendingInstall")
        enableEdgeToEdge()
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            StoreScreen(state, model::refresh, { value ->
                model.saveOrigin(value)
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }, model::queue, model::cancel, ::install, { packageName ->
                packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
                    ?: model.showError(this@MainActivity.getString(R.string.app_not_launchable))
            }, model::queueUpdates, model::dismissError)
        }
    }

    override fun onResume() { super.onResume(); model.onResume() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingInstall", pendingInstall)
        super.onSaveInstanceState(outState)
    }

    private fun install(entry: DownloadEntry) {
        if (installationStarting || entry.status != "READY") return
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = entry.id
            try { unknownSources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { model.showError(this@MainActivity.getString(R.string.install_permission_needed)) }
            return
        }
        installationStarting = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { ApkInstaller.install(this@MainActivity, entry) } }
            catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                model.showError(failure.storeMessage(this@MainActivity, R.string.install_start_failed))
            } finally { installationStarting = false }
        }
    }
}
