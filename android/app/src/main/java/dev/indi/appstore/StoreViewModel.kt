package dev.indi.appstore

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StoreViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.storeRepository
    private val mutableState = MutableStateFlow(StoreUiState(origin = repo.origin))
    val state: StateFlow<StoreUiState> = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            repo.dao.observeCatalog().collectLatest { cached ->
                val catalog = cached?.takeIf { it.origin == repo.origin }?.let {
                    runCatching { repo.gson.fromJson(it.payload, Catalog::class.java).also(CatalogRules::validate) }.getOrNull()
                }
                val apps = catalog?.apps.orEmpty()
                val installed = repo.installed(apps)
                mutableState.update { it.copy(origin = repo.origin, apps = apps, installed = installed, lastUpdated = catalog?.updatedAt.orEmpty()) }
            }
        }
        viewModelScope.launch { repo.dao.observeDownloads().collect { downloads -> mutableState.update { it.copy(downloads = downloads) } } }
        if (repo.origin.isNotBlank()) refresh()
    }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                mutableState.update { it.copy(error = failure.storeMessage(repo.context)) }
            }
        }
    }
    fun refresh() = action {
        mutableState.update { it.copy(refreshing = true, error = null) }
        try { withContext(Dispatchers.IO) { repo.refresh() }; onResume() }
        finally { mutableState.update { it.copy(refreshing = false) } }
    }
    fun saveOrigin(value: String) = action {
        withContext(Dispatchers.IO) { repo.configure(value) }
        mutableState.update { it.copy(origin = repo.origin) }
        refresh()
    }
    fun queue(app: StoreApp, version: StoreVersion) = action { repo.queue(app, version) }
    fun queueUpdates() = action {
        state.value.apps.forEach { app ->
            val installed = repo.installedVersion(app.packageName)
            app.latest?.takeIf { installed != null && it.versionCode > installed && it.minSdk <= android.os.Build.VERSION.SDK_INT }
                ?.let { repo.queue(app, it) }
        }
    }
    fun cancel(id: String) = action { repo.dao.status(id, "CANCELLED") }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    fun showError(message: String) { mutableState.update { it.copy(error = message) } }
    fun onResume() = action {
        withContext(Dispatchers.IO) { repo.reconcileInstalls() }
        val installed = repo.installed(state.value.apps)
        mutableState.update { it.copy(installed = installed) }
    }
}
