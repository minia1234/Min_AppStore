package dev.indi.appstore

import java.net.URI
import java.time.Instant

object CatalogRules {
    fun validate(catalog: Catalog) {
        try {
            if (!(catalog.schemaVersion == 1 && catalog.apps.size <= 500 && validTimestamp(catalog.updatedAt))) throw StoreFailure(R.string.unsupported_catalog)
            if (!(catalog.apps.map { it.packageName }.distinct().size == catalog.apps.size)) throw StoreFailure(R.string.duplicate_apps)
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
        } catch (missing: NullPointerException) { throw StoreFailure(R.string.catalog_fields_missing, missing) }
    }

    fun validateVersion(version: StoreVersion) {
        try {
            require(version.versionCode > 0 && version.versionName.isNotBlank() && version.versionName.length <= 200 && version.minSdk > 0)
            if (!(version.size in 1..(512L * 1024 * 1024) && version.sha256.matches(Regex("[0-9a-f]{64}")) && version.signerSha256.matches(Regex("[0-9a-f]{64}")))) throw StoreFailure(R.string.invalid_apk_metadata)
            if (!(safeHttps(version.downloadUrl) && safeHttps(version.githubUrl))) throw StoreFailure(R.string.https_download_only)
            require(validTimestamp(version.publishedAt) && version.releaseNotes.length <= 100_000)
        } catch (missing: NullPointerException) { throw StoreFailure(R.string.release_fields_missing, missing) }
    }

    private fun validTimestamp(value: String) = runCatching { Instant.parse(value) }.isSuccess
    private fun safeHttps(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null
    }.getOrDefault(false)
}
