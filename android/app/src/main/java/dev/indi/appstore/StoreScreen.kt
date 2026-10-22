package dev.indi.appstore

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    state: StoreUiState,
    onRefresh: () -> Unit,
    onSaveOrigin: (String) -> Unit,
    onDownload: (StoreApp, StoreVersion) -> Unit,
    onCancel: (String) -> Unit,
    onInstall: (DownloadEntry) -> Unit,
    onOpen: (String) -> Unit,
    onBatchUpdate: () -> Unit,
    onDismissError: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val download: (StoreApp, StoreVersion) -> Unit = { app, version ->
        onDownload(app, version)
        tab = 2
        selected = null
    }
    val detail = state.apps.find { it.packageName == selected }
    val selfPackage = LocalContext.current.packageName
    val updates = state.apps.filter { app ->
        val installed = state.installed[app.packageName]
        installed != null && app.latest?.let { StoreRules.isUpdate(installed, it.versionCode) } == true
    }
    BackHandler(enabled = selected != null) { selected = null }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) StoreDarkColors else StoreLightColors) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(title = { Text(if (detail != null) stringResource(R.string.app_details) else "MinAppStore", fontWeight = FontWeight.Bold) },
                    navigationIcon = { if (detail != null) IconButton(onClick = { selected = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
                    actions = { if (state.origin.isNotBlank()) IconButton(onClick = onRefresh, enabled = !state.refreshing) { Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh_catalog)) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf(stringResource(R.string.tab_store) to Icons.Outlined.Storefront, stringResource(R.string.tab_my_apps) to Icons.Outlined.Apps,
                        stringResource(R.string.tab_downloads) to Icons.Outlined.Download, stringResource(R.string.tab_settings) to Icons.Outlined.Settings).forEachIndexed { index, item ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index; selected = null },
                            icon = { Icon(item.second, contentDescription = item.first) }, label = { Text(item.first) })
                    }
                }
            }
        ) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.refreshing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { error -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            IconButton(onClick = onDismissError) { Icon(Icons.Outlined.Close, stringResource(R.string.dismiss_error)) }
                        }
                    }
                } }
                when {
                    state.origin.isBlank() -> {
                        item { Hero(stringResource(R.string.collection_title), stringResource(R.string.collection_description)) }
                        item { OriginSettings(state.origin, onSaveOrigin) }
                        item { EmptyMessage(Icons.Outlined.VerifiedUser, stringResource(R.string.connect_title), stringResource(R.string.connect_description)) }
                    }
                    detail != null -> {
                        item { AppHeading(detail) }
                        item { AppAction(detail, state.installed[detail.packageName], download, onOpen) }
                        item { Text(detail.description.ifBlank { detail.summary }, style = MaterialTheme.typography.bodyLarge) }
                        if (detail.screenshots.isNotEmpty()) item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(detail.screenshots) { url -> AsyncImage(model = url, contentDescription = stringResource(R.string.app_screenshot, detail.name),
                                    modifier = Modifier.width(180.dp).height(320.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Fit) }
                            }
                        }
                        item {
                            val uriHandler = LocalUriHandler.current
                            InfoCard {
                                Text(stringResource(R.string.developer_label, detail.author))
                                Text(stringResource(R.string.category_label, detail.category))
                                Text(stringResource(R.string.github_downloads, detail.downloadCount))
                                if (detail.tags.isNotEmpty()) Text(detail.tags.joinToString("  ") { "#$it" }, color = MaterialTheme.colorScheme.primary)
                                if (detail.repository.isNotBlank()) TextButton(onClick = { uriHandler.openUri("https://github.com/${detail.repository}") }) {
                                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(detail.repository)
                                }
                            }
                        }
                        item { SectionTitle(stringResource(R.string.history_title), stringResource(R.string.history_description)) }
                        items(detail.versions.sortedByDescending { it.versionCode }, key = { it.versionCode }) { version ->
                            VersionCard(detail, version, state.installed[detail.packageName], download)
                        }
                    }
                    tab == 0 -> {
                        item { Text(stringResource(R.string.store_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge) }
                        item {
                            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.search_hint)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_search)) } },
                                singleLine = true, shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
                        }
                        item {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf("") + state.apps.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()).forEach { value ->
                                    FilterChip(selected = category == value, onClick = { category = value }, label = { Text(if (value.isEmpty()) stringResource(R.string.all_categories) else value) })
                                }
                            }
                        }
                        updates.firstOrNull()?.let { app -> item {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(R.string.update_available), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        AppIcon(app.name, app.iconUrl, 48)
                                        Column(Modifier.weight(1f)) {
                                            Text(app.name, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("v${app.latest?.versionName}", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
                                        }
                                        AppAction(app, state.installed[app.packageName], download, onOpen)
                                    }
                                }
                            }
                        } }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.browse_apps), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                listOf(R.string.sort_latest, R.string.sort_popular, R.string.sort_updated).forEachIndexed { index, label ->
                                    TextButton(onClick = { sort = index }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        Text(stringResource(label), color = if (sort == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (sort == index) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                                }
                            }
                        }
                        val filtered = state.apps.filter { app ->
                            (category.isEmpty() || app.category == category) &&
                                (query.isBlank() || listOf(app.name, app.summary, app.description, app.tags.joinToString(" ")).any { it.contains(query.trim(), ignoreCase = true) })
                        }.let { apps -> when (sort) {
                            1 -> apps.sortedByDescending { it.downloadCount }
                            2 -> apps.sortedByDescending { it.latest?.publishedAt.orEmpty() }
                            else -> apps.sortedByDescending { it.versions.minOfOrNull { version -> version.publishedAt }.orEmpty() }
                        } }
                        if (filtered.isEmpty()) item { EmptyMessage(Icons.Outlined.Search, if (state.apps.isEmpty()) stringResource(R.string.no_apps) else stringResource(R.string.no_results), stringResource(R.string.search_help)) }
                        items(filtered, key = { it.packageName }) { app -> AppRow(app, state.installed[app.packageName], { selected = app.packageName }, download, onOpen) }
                    }
                    tab == 1 -> {
                        item { Hero(stringResource(R.string.stay_updated), stringResource(R.string.installed_summary, state.apps.count { state.installed.containsKey(it.packageName) }, updates.size)) }
                        if (updates.isNotEmpty()) item {
                            Button(onClick = onBatchUpdate, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                                Icon(Icons.Outlined.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.download_updates, updates.size))
                            }
                        }
                        state.apps.find { it.packageName == selfPackage }?.let { self -> item {
                            SectionTitle(stringResource(R.string.store_update), stringResource(R.string.store_update_help))
                            Spacer(Modifier.height(12.dp)); AppRow(self, state.installed[selfPackage], { selected = selfPackage }, download, onOpen)
                        } }
                        item { SectionTitle(stringResource(R.string.installed_apps), stringResource(R.string.installed_help)) }
                        val installedApps = state.apps.filter { state.installed.containsKey(it.packageName) }.sortedBy { it.name }
                        if (installedApps.isEmpty()) item { EmptyMessage(Icons.Outlined.Apps, stringResource(R.string.no_installed_apps), stringResource(R.string.discover_help)) }
                        items(installedApps, key = { it.packageName }) { app -> AppRow(app, state.installed[app.packageName], { selected = app.packageName }, download, onOpen) }
                    }
                    tab == 2 -> {
                        item { SectionTitle(stringResource(R.string.tab_downloads), stringResource(R.string.downloads_help)) }
                        if (state.downloads.isEmpty()) item { EmptyMessage(Icons.Outlined.Download, stringResource(R.string.no_downloads), stringResource(R.string.downloads_empty_help)) }
                        items(state.downloads, key = { it.id }) { entry ->
                            DownloadCard(entry, onCancel, onInstall) {
                                state.apps.find { it.packageName == entry.packageName }?.let { app ->
                                    app.versions.find { it.versionCode == entry.versionCode }?.let { download(app, it) }
                                }
                            }
                        }
                    }
                    else -> {
                        item { SectionTitle(stringResource(R.string.store_settings), stringResource(R.string.settings_help)) }
                        item { OriginSettings(state.origin, onSaveOrigin) }
                        item {
                            val uriHandler = LocalUriHandler.current
                            OutlinedButton(onClick = { uriHandler.openUri("${state.origin}/admin") }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.manage_repositories))
                            }
                        }
                        item { InfoCard {
                            Text("MinAppStore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.last_catalog_update, state.lastUpdated.ifBlank { stringResource(R.string.never_updated) }))
                            Text(stringResource(R.string.verification_info))
                            Text(stringResource(R.string.approval_info))
                        } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(title: String, subtitle: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(subtitle, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun AppIcon(name: String, url: String, size: Int = 56) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text(name.take(1), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = stringResource(R.string.app_icon, name), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun AppHeading(app: StoreApp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        AppIcon(app.name, app.iconUrl, 80)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(app.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(app.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppRow(app: StoreApp, installed: Long?, onDetails: () -> Unit, onDownload: (StoreApp, StoreVersion) -> Unit, onOpen: (String) -> Unit) {
    Card(onClick = onDetails, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppIcon(app.name, app.iconUrl)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(app.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(app.summary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(listOfNotNull(app.latest?.let { "v${it.versionName}" }, app.category.takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                AppAction(app, installed, onDownload, onOpen)
            }
        }
    }
}

@Composable
private fun AppAction(app: StoreApp, installed: Long?, onDownload: (StoreApp, StoreVersion) -> Unit, onOpen: (String) -> Unit) {
    val version = app.latest
    val update = installed != null && version != null && StoreRules.isUpdate(installed, version.versionCode)
    val compatible = version != null && version.minSdk <= Build.VERSION.SDK_INT
    Button(onClick = { if (installed != null && !update) onOpen(app.packageName) else if (version != null) onDownload(app, version) },
        enabled = (installed != null && !update) || compatible, shape = RoundedCornerShape(12.dp)) {
        Text(when { installed != null && !update -> stringResource(R.string.open_app); !compatible -> stringResource(R.string.not_supported); update -> stringResource(R.string.update_app); else -> stringResource(R.string.install_app) })
    }
}

@Composable
private fun VersionCard(app: StoreApp, version: StoreVersion, installed: Long?, onDownload: (StoreApp, StoreVersion) -> Unit) {
    val downgrade = installed != null && version.versionCode < installed
    val compatible = version.minSdk <= Build.VERSION.SDK_INT
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("v${version.versionName}", fontWeight = FontWeight.Bold)
                Text("${fileSize(version.size)} · API ${version.minSdk}+ · ${version.publishedAt.take(10)}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { onDownload(app, version) }, enabled = compatible && !downgrade && installed != version.versionCode) {
                Text(when { !compatible -> stringResource(R.string.device_unsupported); downgrade -> stringResource(R.string.older_version); installed == version.versionCode -> stringResource(R.string.installed_version); else -> stringResource(R.string.install_app) })
            }
        }
        Text(version.releaseNotes.ifBlank { stringResource(R.string.no_release_notes) }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DownloadCard(entry: DownloadEntry, onCancel: (String) -> Unit, onInstall: (DownloadEntry) -> Unit, onRetry: () -> Unit) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(entry.name, entry.iconUrl, 44)
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("v${entry.versionName} · " + when (entry.status) {
                    "QUEUED" -> stringResource(R.string.status_queued); "DOWNLOADING" -> stringResource(R.string.status_downloading); "READY" -> stringResource(R.string.status_ready); "INSTALLING" -> stringResource(R.string.status_installing);
                    "INSTALLED" -> stringResource(R.string.status_installed); "CANCELLED" -> stringResource(R.string.status_cancelled); "FAILED" -> stringResource(R.string.status_failed); else -> entry.status
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (entry.status == "DOWNLOADING") {
            if (entry.total > 0) LinearProgressIndicator(progress = { (entry.bytes.toFloat() / entry.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${fileSize(entry.bytes)} / ${if (entry.total > 0) fileSize(entry.total) else stringResource(R.string.checking_size)}", style = MaterialTheme.typography.bodySmall)
        }
        if (entry.error.isNotBlank()) Text(entry.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        when (entry.status) {
            "QUEUED", "DOWNLOADING" -> TextButton(onClick = { onCancel(entry.id) }) { Text(stringResource(R.string.cancel_download)) }
            "READY" -> Button(onClick = { onInstall(entry) }) { Text(stringResource(R.string.open_installer)) }
            "FAILED", "CANCELLED" -> TextButton(onClick = onRetry) { Text(stringResource(R.string.retry_download)) }
        }
    }
}

@Composable
private fun OriginSettings(origin: String, onSave: (String) -> Unit) {
    var url by rememberSaveable(origin) { mutableStateOf(origin) }
    val parsed = Uri.parse(url.trim())
    val valid = parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.userInfo == null && parsed.query == null && parsed.fragment == null && (parsed.path.isNullOrEmpty() || parsed.path == "/")
    InfoCard {
        Text(stringResource(R.string.store_connection), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.worker_url_help), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            label = { Text(stringResource(R.string.store_url)) }, placeholder = { Text("https://my-store.workers.dev") },
            isError = url.isNotBlank() && !valid, supportingText = { Text(stringResource(R.string.origin_help)) })
        Button(onClick = { onSave(url.trim().trimEnd('/')) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text(if (origin.isBlank()) stringResource(R.string.start_store) else stringResource(R.string.save_origin)) }
    }
}

@Composable
private fun EmptyMessage(icon: ImageVector, title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun fileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
