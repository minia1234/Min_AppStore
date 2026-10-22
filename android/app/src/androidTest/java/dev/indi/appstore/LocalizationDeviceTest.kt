package dev.indi.appstore

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationDeviceTest {
    private fun context(language: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
    }

    @Test fun bothLanguagesResolveTextAndFormattedCounts() {
        val en = context("en")
        val ko = context("ko")
        assertEquals("Store", en.getString(R.string.tab_store))
        assertEquals("스토어", ko.getString(R.string.tab_store))
        assertEquals("Installed: 2 · Updates: 1", en.getString(R.string.installed_summary, 2, 1))
        assertEquals("설치된 앱 2개 · 업데이트 1개", ko.getString(R.string.installed_summary, 2, 1))
        assertEquals("1 app update available", en.resources.getQuantityString(R.plurals.update_notification, 1, 1))
        assertEquals("2 app updates available", en.resources.getQuantityString(R.plurals.update_notification, 2, 2))
    }

    @Test fun validationErrorsUseTheCurrentLanguageWithoutChangingTheRule() {
        val problem = StoreRules.apkProblem("dev.wrong.app", 1, setOf("signer"), "dev.expected.app", 1, "signer", null)!!
        assertEquals("The APK package name does not match.", context("en").getString(problem))
        assertEquals("APK 패키지 이름이 일치하지 않습니다.", context("ko").getString(problem))
        val error = assertThrows(StoreFailure::class.java) { StoreRules.normalizeOrigin("http://example.test") }
        assertEquals(context("en").getString(R.string.invalid_origin), error.storeMessage(context("en")))
        assertEquals(context("ko").getString(R.string.invalid_origin), error.storeMessage(context("ko")))
    }

    @Test fun otherLanguagesFallBackToEnglish() {
        assertEquals("Store", context("fr").getString(R.string.tab_store))
    }
}
