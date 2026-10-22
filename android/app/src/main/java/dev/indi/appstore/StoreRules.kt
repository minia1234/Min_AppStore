package dev.indi.appstore

import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

object StoreRules {
    fun isUpdate(installed: Long, latest: Long): Boolean = latest > installed

    fun normalizeOrigin(value: String): String {
        val uri = URI(value.trim())
        if (!(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.path.isEmpty() || uri.path == "/"))) throw StoreFailure(R.string.invalid_origin)
        return uri.toASCIIString().trimEnd('/')
    }

    fun verifyBytes(input: InputStream, size: Long, sha256: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            if (count > size) return false
            digest.update(buffer, 0, read)
        }
        return count == size && digest.digest().hex() == sha256.lowercase()
    }

    fun apkProblem(actualPackage: String, actualVersion: Long, actualSigners: Set<String>, expectedPackage: String, expectedVersion: Long, expectedSigner: String, installedVersion: Long?): Int? = when {
        actualPackage != expectedPackage -> R.string.package_mismatch
        actualVersion != expectedVersion -> R.string.version_mismatch
        actualSigners != setOf(expectedSigner) -> R.string.signer_mismatch
        installedVersion != null && actualVersion < installedVersion -> R.string.downgrade_blocked
        else -> null
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
