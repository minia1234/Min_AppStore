package dev.indi.appstore

import com.google.gson.Gson
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogRulesTest {
    private val gson = Gson()
    private fun valid() = Catalog(1, "2026-10-21T00:00:00Z", listOf(StoreApp("dev.test.app", "테스트", "요약", "설명", "도구", "개발자", "owner/repo",
        listOf("도구"), "", emptyList(), 0, listOf(StoreVersion(1, "1.0", 26, "a".repeat(64), 3,
            "https://store.test/download.apk", "https://github.com/owner/repo/releases/download/v1/app.apk", "2026-10-21T00:00:00Z", "", "b".repeat(64))))))

    @Test fun realCatalogShapeIsAccepted() { CatalogRules.validate(valid()) }

    @Test fun omittedUiFieldsAreRejectedBeforeCaching() {
        for (field in listOf("category", "repository", "publishedAt", "releaseNotes", "author", "tags", "screenshots")) {
            val tree = gson.toJsonTree(valid()).asJsonObject
            val app = tree.getAsJsonArray("apps")[0].asJsonObject
            if (field in listOf("publishedAt", "releaseNotes")) app.getAsJsonArray("versions")[0].asJsonObject.remove(field)
            else app.remove(field)
            assertThrows("Missing $field", IllegalArgumentException::class.java) { CatalogRules.validate(gson.fromJson(tree, Catalog::class.java)) }
        }
    }
    @Test fun unsafeUrlsAndDuplicateVersionsAreRejected() {
        val app = valid().apps.single()
        assertThrows(IllegalArgumentException::class.java) { CatalogRules.validate(valid().copy(apps = listOf(app.copy(iconUrl = "http://unsafe.test/icon.png")))) }
        assertThrows(IllegalArgumentException::class.java) { CatalogRules.validate(valid().copy(apps = listOf(app.copy(versions = app.versions + app.versions)))) }
    }
}
