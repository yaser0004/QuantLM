package com.quantlm.yaser.domain.model

import java.io.File

/**
 * Pure, engine-agnostic preflight validation for downloaded model files.
 *
 * Rationale: the native runtimes QuantLM bundles do NOT fail gracefully on
 * malformed input. llama.cpp's loader can `GGML_ABORT` on a corrupt header,
 * and a truncated/HTML-error-page download fed to a native loader can take
 * the whole process down with SIGABRT — a crash the JVM cannot catch.
 *
 * Catching the problem here, on the JVM side before the JNI boundary, turns a
 * hard crash into a clean [Result.Invalid] with a user-facing reason. This
 * object is intentionally pure (no Android, no I/O beyond reading a header)
 * so it is fully unit-testable — see `ModelFileValidatorTest`.
 */
object ModelFileValidator {

    /** GGUF files begin with the ASCII magic "GGUF" (0x47 0x47 0x55 0x46). */
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

    /**
     * A real model is always larger than this. Anything smaller is a
     * truncated download or an error page saved under a model file name.
     */
    const val MIN_MODEL_BYTES = 1_000_000L

    /** How many leading bytes are inspected for magic / payload sniffing. */
    private const val HEADER_PROBE_BYTES = 512

    sealed class Result {
        object Valid : Result()
        data class Invalid(val reason: String) : Result()
    }

    /** True when [header] starts with the GGUF magic bytes. */
    fun ggufMagicMatches(header: ByteArray): Boolean {
        if (header.size < GGUF_MAGIC.size) return false
        return GGUF_MAGIC.indices.all { header[it] == GGUF_MAGIC[it] }
    }

    /**
     * Detect the common failure mode where a gated/expired download saved an
     * HTML or JSON error page instead of the model binary.
     */
    fun looksLikeHttpErrorPayload(preview: ByteArray): Boolean {
        if (preview.isEmpty()) return false
        val text = String(preview, Charsets.UTF_8).trimStart().lowercase()
        return text.startsWith("<!doctype html") ||
            text.startsWith("<html") ||
            text.startsWith("{\"error\"") ||
            (text.contains("huggingface") && text.contains("access"))
    }

    /**
     * Validate a file destined for the llama.cpp (GGUF) engine.
     *
     * Returns [Result.Invalid] with a user-facing reason for anything that
     * would otherwise be handed to `nativeLoadModel` and risk a native crash:
     * a missing file, a truncated download, a saved error page, or a file
     * whose signature is not GGUF at all (wrong format routed to llama).
     */
    fun validateGguf(file: File): Result {
        if (!file.exists()) {
            return Result.Invalid("Model file not found. Please re-download the model.")
        }
        val length = file.length()
        if (length < MIN_MODEL_BYTES) {
            return Result.Invalid(
                "Model file is too small ($length bytes) — the download is incomplete " +
                    "or corrupted. Please delete and re-download the model."
            )
        }
        val header = readHeader(file) ?: return Result.Invalid(
            "Model file could not be read. Please delete and re-download the model."
        )
        if (looksLikeHttpErrorPayload(header)) {
            return Result.Invalid(
                "Model file is invalid (a download error page was saved instead of the " +
                    "model). Please delete and re-download the model."
            )
        }
        if (!ggufMagicMatches(header)) {
            return Result.Invalid(
                "Model file is not a valid GGUF model (bad file signature). It may be " +
                    "corrupted or in an unsupported format. Please delete and re-download."
            )
        }
        return Result.Valid
    }

    private fun readHeader(file: File): ByteArray? {
        return try {
            file.inputStream().use { input ->
                val buf = ByteArray(HEADER_PROBE_BYTES)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read <= 0) null else buf.copyOf(read)
            }
        } catch (_: Exception) {
            null
        }
    }
}
