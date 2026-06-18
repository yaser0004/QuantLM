package com.quantlm.yaser.data.repository

import android.util.Log
import java.io.File

/**
 * Detects a model file's actual container format from its magic bytes, so
 * engine routing does not rely solely on the (user-renamable) file extension.
 *
 * Only formats with an unambiguous magic signature are detected:
 *  - GGUF — ASCII `GGUF` at offset 0.
 *  - ZIP-based bundle (MediaPipe `.task`) — `PK` magic in the leading bytes.
 *
 * `.litertlm` has no documented stable magic, so it is deliberately not
 * content-detected; callers fall back to the file extension for it.
 */
object ModelFormatDetector {

    private const val TAG = "ModelFormatDetector"

    enum class DetectedFormat { GGUF, ZIP_BUNDLE }

    /** Sniff the file header. Returns null when the format is undetermined. */
    fun detect(file: File): DetectedFormat? {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(8)
                val read = input.read(header)
                if (read < 4) return null
                when {
                    header[0] == 'G'.code.toByte() &&
                        header[1] == 'G'.code.toByte() &&
                        header[2] == 'U'.code.toByte() &&
                        header[3] == 'F'.code.toByte() -> DetectedFormat.GGUF

                    containsZipMagic(header, read) -> DetectedFormat.ZIP_BUNDLE

                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read header of ${file.name}: ${e.message}")
            null
        }
    }

    private fun containsZipMagic(buf: ByteArray, len: Int): Boolean {
        for (i in 0 until (len - 1)) {
            if (buf[i] == 'P'.code.toByte() && buf[i + 1] == 'K'.code.toByte()) return true
        }
        return false
    }
}
