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
    var category by rememberSaveable { mutableStateOf("전체") }
    var sort by rememberSaveable { mutableStateOf("최신") }
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
                TopAppBar(title = { Text(if (detail != null) "앱 정보" else "MinAppStore", fontWeight = FontWeight.Bold) },
                    navigationIcon = { if (detail != null) IconButton(onClick = { selected = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로") } },
                    actions = { if (state.origin.isNotBlank()) IconButton(onClick = onRefresh, enabled = !state.refreshing) { Icon(Icons.Outlined.Refresh, "목록 새로고침") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    listOf("스토어" to Icons.Outlined.Storefront, "내 앱" to Icons.Outlined.Apps,
                        "다운로드" to Icons.Outlined.Download, "설정" to Icons.Outlined.Settings).forEachIndexed { index, item ->
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
                            IconButton(onClick = onDismissError) { Icon(Icons.Outlined.Close, "오류 메시지 닫기") }
                        }
                    }
                } }
                when {
                    state.origin.isBlank() -> {
                        item { Hero("나만의 앱 컬렉션", "직접 만든 앱을 한곳에서 발견하고,\n안전하게 설치하고 업데이트하세요.") }
                        item { OriginSettings(state.origin, onSaveOrigin) }
                        item { EmptyMessage(Icons.Outlined.VerifiedUser, "내 스토어에 연결하세요", "공개 앱 목록만 불러옵니다. 관리자 키나 GitHub 토큰은 필요하지 않습니다.") }
                    }
                    detail != null -> {
                        item { AppHeading(detail) }
                        item { AppAction(detail, state.installed[detail.packageName], download, onOpen) }
                        item { Text(detail.description.ifBlank { detail.summary }, style = MaterialTheme.typography.bodyLarge) }
                        if (detail.screenshots.isNotEmpty()) item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(detail.screenshots) { url -> AsyncImage(model = url, contentDescription = "${detail.name} 스크린샷",
                                    modifier = Modifier.width(180.dp).height(320.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Fit) }
                            }
                        }
                        item {
                            val uriHandler = LocalUriHandler.current
                            InfoCard {
                                Text("개발자 · ${detail.author}")
                                Text("분류 · ${detail.category}")
                                Text("GitHub 릴리스 다운로드 · ${detail.downloadCount}회")
                                if (detail.tags.isNotEmpty()) Text(detail.tags.joinToString("  ") { "#$it" }, color = MaterialTheme.colorScheme.primary)
                                if (detail.repository.isNotBlank()) TextButton(onClick = { uriHandler.openUri("https://github.com/${detail.repository}") }) {
                                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(detail.repository)
                                }
                            }
                        }
                        item { SectionTitle("버전 기록", "설치된 버전보다 낮은 버전은 설치할 수 없습니다.") }
                        items(detail.versions.sortedByDescending { it.versionCode }, key = { it.versionCode }) { version ->
                            VersionCard(detail, version, state.installed[detail.packageName], download)
                        }
                    }
                    tab == 0 -> {
                        item { Text("나의 앱을 한곳에", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge) }
                        item {
                            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("앱, 설명, 태그 검색") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "검색어 지우기") } },
                                singleLine = true, shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
                        }
                        item {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                (listOf("전체") + state.apps.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()).forEach { value ->
                                    FilterChip(selected = category == value, onClick = { category = value }, label = { Text(value) })
                                }
                            }
                        }
                        updates.firstOrNull()?.let { app -> item {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp)) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("새로운 버전이 있어요", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
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
                                Text("앱 둘러보기", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                listOf("최신", "인기", "최근 업데이트").forEach { value ->
                                    TextButton(onClick = { sort = value }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                        Text(value, color = if (sort == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = if (sort == value) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                                }
                            }
                        }
                        val filtered = state.apps.filter { app ->
                            (category == "전체" || app.category == category) &&
                                (query.isBlank() || listOf(app.name, app.summary, app.description, app.tags.joinToString(" ")).any { it.contains(query.trim(), ignoreCase = true) })
                        }.let { apps -> when (sort) {
                            "인기" -> apps.sortedByDescending { it.downloadCount }
                            "최근 업데이트" -> apps.sortedByDescending { it.latest?.publishedAt.orEmpty() }
                            else -> apps.sortedByDescending { it.versions.minOfOrNull { version -> version.publishedAt }.orEmpty() }
                        } }
                        if (filtered.isEmpty()) item { EmptyMessage(Icons.Outlined.Search, if (state.apps.isEmpty()) "아직 등록된 앱이 없어요" else "검색 결과가 없어요", "새로고침하거나 검색 조건을 변경해 보세요.") }
                        items(filtered, key = { it.packageName }) { app -> AppRow(app, state.installed[app.packageName], { selected = app.packageName }, download, onOpen) }
                    }
                    tab == 1 -> {
                        item { Hero("언제나 최신으로", "설치된 앱 ${state.apps.count { state.installed.containsKey(it.packageName) }}개 · 업데이트 ${updates.size}개") }
                        if (updates.isNotEmpty()) item {
                            Button(onClick = onBatchUpdate, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                                Icon(Icons.Outlined.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text("업데이트 ${updates.size}개 다운로드")
                            }
                        }
                        state.apps.find { it.packageName == selfPackage }?.let { self -> item {
                            SectionTitle("MinAppStore 업데이트", "스토어 앱도 같은 방식으로 업데이트합니다.")
                            Spacer(Modifier.height(12.dp)); AppRow(self, state.installed[selfPackage], { selected = selfPackage }, download, onOpen)
                        } }
                        item { SectionTitle("설치된 앱", "이 스토어에 등록된 앱만 표시합니다.") }
                        val installedApps = state.apps.filter { state.installed.containsKey(it.packageName) }.sortedBy { it.name }
                        if (installedApps.isEmpty()) item { EmptyMessage(Icons.Outlined.Apps, "설치된 앱이 없어요", "둘러보기에서 마음에 드는 앱을 설치해 보세요.") }
                        items(installedApps, key = { it.packageName }) { app -> AppRow(app, state.installed[app.packageName], { selected = app.packageName }, download, onOpen) }
                    }
                    tab == 2 -> {
                        item { SectionTitle("다운로드", "파일 검증 후 Android 설치 화면에서 직접 승인합니다.") }
                        if (state.downloads.isEmpty()) item { EmptyMessage(Icons.Outlined.Download, "다운로드 내역이 없어요", "앱을 다운로드하면 진행 상황이 여기에 표시됩니다.") }
                        items(state.downloads, key = { it.id }) { entry ->
                            DownloadCard(entry, onCancel, onInstall) {
                                state.apps.find { it.packageName == entry.packageName }?.let { app ->
                                    app.versions.find { it.versionCode == entry.versionCode }?.let { download(app, it) }
                                }
                            }
                        }
                    }
                    else -> {
                        item { SectionTitle("스토어 설정", "나만의 앱 스토어 연결을 관리하세요.") }
                        item { OriginSettings(state.origin, onSaveOrigin) }
                        item {
                            val uriHandler = LocalUriHandler.current
                            OutlinedButton(onClick = { uriHandler.openUri("${state.origin}/admin") }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp)); Text("브라우저에서 저장소 연동 관리")
                            }
                        }
                        item { InfoCard {
                            Text("MinAppStore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("마지막 목록 갱신 · ${state.lastUpdated.ifBlank { "아직 갱신하지 않음" }}")
                            Text("앱 파일은 SHA-256, 패키지, 버전, 서명을 확인한 뒤 설치합니다.")
                            Text("설치에는 Android의 승인이 필요합니다. 업데이트 다운로드는 순서대로 처리됩니다.")
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
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = "$name 아이콘", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
        Text(when { installed != null && !update -> "열기"; !compatible -> "지원하지 않음"; update -> "업데이트"; else -> "설치" })
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
                Text(when { !compatible -> "기기 미지원"; downgrade -> "이전 버전"; installed == version.versionCode -> "설치됨"; else -> "설치" })
            }
        }
        Text(version.releaseNotes.ifBlank { "등록된 변경 사항이 없습니다." }, style = MaterialTheme.typography.bodyMedium)
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
                    "QUEUED" -> "대기 중"; "DOWNLOADING" -> "다운로드 중"; "READY" -> "설치 준비 완료"; "INSTALLING" -> "설치 승인 대기";
                    "INSTALLED" -> "설치 완료"; "CANCELLED" -> "취소됨"; "FAILED" -> "실패"; else -> entry.status
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (entry.status == "DOWNLOADING") {
            if (entry.total > 0) LinearProgressIndicator(progress = { (entry.bytes.toFloat() / entry.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("${fileSize(entry.bytes)} / ${if (entry.total > 0) fileSize(entry.total) else "크기 확인 중"}", style = MaterialTheme.typography.bodySmall)
        }
        if (entry.error.isNotBlank()) Text(entry.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        when (entry.status) {
            "QUEUED", "DOWNLOADING" -> TextButton(onClick = { onCancel(entry.id) }) { Text("다운로드 취소") }
            "READY" -> Button(onClick = { onInstall(entry) }) { Text("Android 설치 화면 열기") }
            "FAILED", "CANCELLED" -> TextButton(onClick = onRetry) { Text("다시 다운로드") }
        }
    }
}

@Composable
private fun OriginSettings(origin: String, onSave: (String) -> Unit) {
    var url by rememberSaveable(origin) { mutableStateOf(origin) }
    val parsed = Uri.parse(url.trim())
    val valid = parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.userInfo == null && parsed.query == null && parsed.fragment == null && (parsed.path.isNullOrEmpty() || parsed.path == "/")
    InfoCard {
        Text("스토어 연결", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("배포한 Cloudflare Worker의 HTTPS 주소를 입력하세요.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            label = { Text("스토어 URL") }, placeholder = { Text("https://my-store.workers.dev") },
            isError = url.isNotBlank() && !valid, supportingText = { Text("경로·계정 정보가 없는 HTTPS 주소를 입력하세요.") })
        Button(onClick = { onSave(url.trim().trimEnd('/')) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text(if (origin.isBlank()) "내 스토어 시작하기" else "주소 저장 및 새로고침") }
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
    bytes >= 1_000_000_000 -> String.format(Locale.KOREA, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.KOREA, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.KOREA, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
