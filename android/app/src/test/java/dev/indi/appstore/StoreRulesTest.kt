package dev.indi.appstore

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class StoreRulesTest {
    @Test fun versionCodeDeterminesUpdate() {
        assertTrue(StoreRules.isUpdate(9, 10))
        assertFalse(StoreRules.isUpdate(10, 10))
        assertFalse(StoreRules.isUpdate(11, 10))
    }
    @Test fun onlyHttpsOriginsCanBeConfigured() {
        assertEquals("https://store.example", StoreRules.normalizeOrigin(" https://store.example/ "))
        listOf("http://store.example", "https://u:p@store.example", "https://store.example/a", "https://store.example/?x=1", "file:///tmp", "https://store.example/#a").forEach {
            assertThrows(IllegalArgumentException::class.java) { StoreRules.normalizeOrigin(it) }
        }
    }
    @Test fun downloadedBytesMustMatchHashAndExactSize() {
        val bytes = "abc".toByteArray()
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertTrue(StoreRules.verifyBytes(ByteArrayInputStream(bytes), 3, hash))
        assertFalse(StoreRules.verifyBytes(ByteArrayInputStream(bytes), 4, hash))
        assertFalse(StoreRules.verifyBytes(ByteArrayInputStream(bytes), 3, "0".repeat(64)))
    }
    @Test fun apkIdentityAndSignerMustMatchPinnedRelease() {
        assertNull(StoreRules.apkProblem("dev.test.app", 12, setOf("a".repeat(64)), "dev.test.app", 12, "a".repeat(64), null))
        assertNotNull(StoreRules.apkProblem("dev.evil.app", 12, setOf("a".repeat(64)), "dev.test.app", 12, "a".repeat(64), null))
        assertNotNull(StoreRules.apkProblem("dev.test.app", 11, setOf("a".repeat(64)), "dev.test.app", 12, "a".repeat(64), null))
        assertNotNull(StoreRules.apkProblem("dev.test.app", 12, setOf("b".repeat(64)), "dev.test.app", 12, "a".repeat(64), null))
        assertNotNull(StoreRules.apkProblem("dev.test.app", 12, setOf("a".repeat(64)), "dev.test.app", 12, "a".repeat(64), 13))
    }
}
