package dev.indi.appstore

import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

object StoreRules {
    fun isUpdate(installed: Long, latest: Long): Boolean = latest > installed

    fun normalizeOrigin(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.path.isEmpty() || uri.path == "/")) {
            "경로·계정 정보가 없는 HTTPS 서버 주소를 입력해 주세요."
        }
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

    fun apkProblem(actualPackage: String, actualVersion: Long, actualSigners: Set<String>, expectedPackage: String, expectedVersion: Long, expectedSigner: String, installedVersion: Long?): String? = when {
        actualPackage != expectedPackage -> "APK 패키지 이름이 일치하지 않습니다."
        actualVersion != expectedVersion -> "APK 버전이 일치하지 않습니다."
        actualSigners != setOf(expectedSigner) -> "APK 서명이 등록된 서명과 다릅니다."
        installedVersion != null && actualVersion < installedVersion -> "설치된 앱보다 이전 버전은 설치할 수 없습니다."
        else -> null
    }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
