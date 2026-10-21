package dev.indi.appstore

import java.net.URI
import java.time.Instant

object CatalogRules {
    fun validate(catalog: Catalog) {
        try {
            require(catalog.schemaVersion == 1 && catalog.apps.size <= 500 && validTimestamp(catalog.updatedAt)) { "지원하지 않는 카탈로그입니다." }
            require(catalog.apps.map { it.packageName }.distinct().size == catalog.apps.size) { "중복된 앱이 있습니다." }
            catalog.apps.forEach { app ->
                require(app.packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) && app.name.isNotBlank())
                require(app.name.length <= 200 && app.summary.length <= 1000 && app.description.length <= 100_000)
                require(app.category.length <= 80 && app.author.length <= 200 && app.repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
                require(app.tags.size <= 50 && app.tags.all { it.length <= 80 } && app.screenshots.size <= 20 && app.downloadCount >= 0)
                require(app.iconUrl.isEmpty() || safeHttps(app.iconUrl))
                require(app.screenshots.all { safeHttps(it) })
                require(app.versions.isNotEmpty() && app.versions.size <= 100)
                require(app.versions.map { it.versionCode }.distinct().size == app.versions.size)
                app.versions.forEach(::validateVersion)
            }
        } catch (missing: NullPointerException) { throw IllegalArgumentException("카탈로그에 필수 정보가 누락되었습니다.", missing) }
    }

    fun validateVersion(version: StoreVersion) {
        try {
            require(version.versionCode > 0 && version.versionName.isNotBlank() && version.versionName.length <= 200 && version.minSdk > 0)
            require(version.size in 1..(512L * 1024 * 1024) && version.sha256.matches(Regex("[0-9a-f]{64}")) && version.signerSha256.matches(Regex("[0-9a-f]{64}"))) { "APK 검증 정보가 올바르지 않습니다." }
            require(safeHttps(version.downloadUrl) && safeHttps(version.githubUrl)) { "HTTPS 다운로드만 지원합니다." }
            require(validTimestamp(version.publishedAt) && version.releaseNotes.length <= 100_000)
        } catch (missing: NullPointerException) { throw IllegalArgumentException("릴리스에 필수 정보가 누락되었습니다.", missing) }
    }

    private fun validTimestamp(value: String) = runCatching { Instant.parse(value) }.isSuccess
    private fun safeHttps(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null
    }.getOrDefault(false)
}
