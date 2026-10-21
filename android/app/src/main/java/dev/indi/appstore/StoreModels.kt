package dev.indi.appstore

data class Catalog(val schemaVersion: Int, val updatedAt: String, val apps: List<StoreApp>)

data class StoreApp(
    val packageName: String, val name: String, val summary: String, val description: String,
    val category: String, val author: String, val repository: String, val tags: List<String>,
    val iconUrl: String, val screenshots: List<String>, val downloadCount: Long,
    val versions: List<StoreVersion>,
) {
    val latest: StoreVersion? get() = versions.maxByOrNull { it.versionCode }
}

data class StoreVersion(
    val versionCode: Long, val versionName: String, val minSdk: Int, val sha256: String,
    val size: Long, val downloadUrl: String, val githubUrl: String, val publishedAt: String,
    val releaseNotes: String, val signerSha256: String,
)

data class StoreUiState(
    val origin: String = "", val apps: List<StoreApp> = emptyList(),
    val installed: Map<String, Long> = emptyMap(), val downloads: List<DownloadEntry> = emptyList(),
    val refreshing: Boolean = false, val error: String? = null, val lastUpdated: String = "",
)
