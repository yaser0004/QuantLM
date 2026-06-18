package com.quantlm.yaser.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the app-lock credential format and — critically — the legacy
 * migration contract: values written by the original unsalted-SHA-256 scheme
 * must keep verifying, or existing users are locked out of the app.
 */
class CredentialHasherTest {

    @Test
    fun v2Hash_roundTrips() {
        val stored = CredentialHasher.hash("1234")
        assertTrue(stored.startsWith("v2:"))
        assertFalse(CredentialHasher.isLegacy(stored))
        assertTrue(CredentialHasher.matches("1234", stored))
        assertFalse(CredentialHasher.matches("1235", stored))
    }

    @Test
    fun v2Hash_isSaltedPerCredential() {
        // Same input must produce different stored values (random salt) while
        // both still verify.
        val a = CredentialHasher.hash("1234")
        val b = CredentialHasher.hash("1234")
        assertNotEquals(a, b)
        assertTrue(CredentialHasher.matches("1234", a))
        assertTrue(CredentialHasher.matches("1234", b))
    }

    @Test
    fun legacySha256Value_stillVerifies() {
        // Exactly what AppPreferences.hashCode() used to persist.
        val legacyStored = CredentialHasher.legacySha256Hex("1234")
        assertTrue(CredentialHasher.isLegacy(legacyStored))
        assertTrue(CredentialHasher.matches("1234", legacyStored))
        assertFalse(CredentialHasher.matches("1235", legacyStored))
    }

    @Test
    fun legacyKnownVector_matches() {
        // SHA-256("1234") — pinned so a refactor of legacySha256Hex cannot
        // silently change the digest and lock out migrated users.
        val sha256Of1234 = "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4"
        assertTrue(CredentialHasher.matches("1234", sha256Of1234))
    }

    @Test
    fun malformedStoredValues_neverMatch() {
        assertFalse(CredentialHasher.matches("1234", "v2:"))
        assertFalse(CredentialHasher.matches("1234", "v2:abc:def"))
        assertFalse(CredentialHasher.matches("1234", "v2:150000:!!!notbase64!!!:alsobad"))
        assertFalse(CredentialHasher.matches("1234", "v2:0:AAAA:AAAA"))
        assertFalse(CredentialHasher.matches("1234", "v2:99999999:AAAA:AAAA")) // over iteration cap
    }
}
