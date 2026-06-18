package com.quantlm.yaser.data.local

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Credential hashing for the app lock (PIN / password / pattern).
 *
 * Format v2: `v2:<iterations>:<base64 salt>:<base64 hash>` — PBKDF2-HMAC-SHA256
 * with a random per-credential salt. The app originally stored unsalted
 * SHA-256 hex, which an offline attacker can brute-force instantly for a
 * 4–8 digit PIN; [matches] still verifies that legacy form so existing locks
 * keep working, and AppPreferences re-hashes to v2 after a successful legacy
 * match (transparent upgrade — no user lockout, no forced reset).
 *
 * Pure JVM (java.util.Base64, javax.crypto) so it is unit-testable.
 */
internal object CredentialHasher {

    private const val VERSION_PREFIX = "v2"

    // ~50–150 ms on a mid-range phone: imperceptible at unlock, but ~10^5
    // times slower than raw SHA-256 for an offline brute force of a short PIN.
    private const val ITERATIONS = 150_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    // Upper bound when parsing a stored iteration count so a corrupted or
    // tampered preferences file cannot make verification spin for minutes.
    private const val MAX_ITERATIONS = 1_000_000

    fun isLegacy(stored: String): Boolean = !stored.startsWith("$VERSION_PREFIX:")

    /** Derive a fresh v2 hash with a new random salt. */
    fun hash(input: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = pbkdf2(input, salt, ITERATIONS)
        val b64 = Base64.getEncoder()
        return "$VERSION_PREFIX:$ITERATIONS:${b64.encodeToString(salt)}:${b64.encodeToString(derived)}"
    }

    /** Constant-time match against either the v2 format or legacy SHA-256 hex. */
    fun matches(input: String, stored: String): Boolean {
        if (isLegacy(stored)) {
            return MessageDigest.isEqual(
                legacySha256Hex(input).toByteArray(),
                stored.toByteArray()
            )
        }
        val parts = stored.split(":")
        if (parts.size != 4) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..MAX_ITERATIONS } ?: return false
        return try {
            val decoder = Base64.getDecoder()
            val salt = decoder.decode(parts[2])
            val expected = decoder.decode(parts[3])
            MessageDigest.isEqual(pbkdf2(input, salt, iterations), expected)
        } catch (_: IllegalArgumentException) {
            false // malformed base64
        }
    }

    private fun pbkdf2(input: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(input.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    /** The unsalted hash this app shipped with — kept for verification only. */
    fun legacySha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
