package com.quantlm.yaser.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression coverage for the GGUF preflight validator.
 *
 * Before this validator existed, the llama.cpp (GGUF) load path handed the
 * downloaded file straight to `nativeLoadModel`. A truncated download, a saved
 * HTTP error page, or a wrong-format file could make the native loader abort
 * the process (SIGABRT) — a crash the JVM cannot catch. These tests lock in
 * that every such file is rejected on the JVM side with a user-facing reason
 * instead of reaching native code.
 */
class ModelFileValidatorTest {

    private fun tempFile(name: String, bytes: ByteArray): File {
        val f = File.createTempFile("modelvalidator_$name", ".bin")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f
    }

    /** A byte array that begins with the GGUF magic, padded to [size]. */
    private fun ggufBytes(size: Int): ByteArray =
        ByteArray(size).also { buf ->
            buf[0] = 0x47; buf[1] = 0x47; buf[2] = 0x55; buf[3] = 0x46 // "GGUF"
        }

    @Test
    fun `gguf magic matches valid header`() {
        assertTrue(ModelFileValidator.ggufMagicMatches(ggufBytes(64)))
    }

    @Test
    fun `gguf magic rejects non-gguf header`() {
        assertFalse(ModelFileValidator.ggufMagicMatches("NOTAGGUF".toByteArray()))
        assertFalse(ModelFileValidator.ggufMagicMatches(byteArrayOf(0x47, 0x47))) // too short
        assertFalse(ModelFileValidator.ggufMagicMatches(ByteArray(0)))
    }

    @Test
    fun `http error payloads are detected`() {
        assertTrue(ModelFileValidator.looksLikeHttpErrorPayload("<!DOCTYPE html><html>".toByteArray()))
        assertTrue(ModelFileValidator.looksLikeHttpErrorPayload("  <html>oops</html>".toByteArray()))
        assertTrue(ModelFileValidator.looksLikeHttpErrorPayload("{\"error\":\"gated\"}".toByteArray()))
        assertFalse(ModelFileValidator.looksLikeHttpErrorPayload(ggufBytes(64)))
        assertFalse(ModelFileValidator.looksLikeHttpErrorPayload(ByteArray(0)))
    }

    @Test
    fun `validateGguf rejects a missing file`() {
        val missing = File("/nonexistent/path/model.gguf")
        val result = ModelFileValidator.validateGguf(missing)
        assertTrue(result is ModelFileValidator.Result.Invalid)
    }

    @Test
    fun `validateGguf rejects a truncated download`() {
        val tiny = tempFile("tiny", ggufBytes(4096)) // valid magic but far too small
        val result = ModelFileValidator.validateGguf(tiny)
        assertTrue(
            "tiny file must be rejected as incomplete",
            result is ModelFileValidator.Result.Invalid
        )
    }

    @Test
    fun `validateGguf rejects a saved error page`() {
        // Large enough to pass the size gate, but the content is an HTML page.
        val htmlBytes = ("<!DOCTYPE html><html><body>403 Forbidden</body></html>")
            .toByteArray()
            .copyOf(ModelFileValidator.MIN_MODEL_BYTES.toInt() + 1024)
        val errorPage = tempFile("errorpage", htmlBytes)
        val result = ModelFileValidator.validateGguf(errorPage)
        assertTrue(result is ModelFileValidator.Result.Invalid)
    }

    @Test
    fun `validateGguf rejects a wrong-format file`() {
        // Large enough, not an error page, but not GGUF either (e.g. a TFLite
        // file mistakenly routed to the llama engine).
        val notGguf = tempFile("notgguf", ByteArray(ModelFileValidator.MIN_MODEL_BYTES.toInt() + 1024))
        val result = ModelFileValidator.validateGguf(notGguf)
        assertTrue(result is ModelFileValidator.Result.Invalid)
    }

    @Test
    fun `validateGguf accepts a well-formed gguf file`() {
        val good = tempFile("good", ggufBytes(ModelFileValidator.MIN_MODEL_BYTES.toInt() + 4096))
        val result = ModelFileValidator.validateGguf(good)
        assertEquals(ModelFileValidator.Result.Valid, result)
    }
}
