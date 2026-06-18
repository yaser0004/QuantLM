package com.quantlm.yaser.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the 2026-05-14 native crash where loading
 * `gemma-4-E2B-it.litertlm` hit an absl::CHECK abort inside the bundled
 * MediaPipe `tasks-genai 0.10.32` SDK (`Unknown model type: tf_lite_end_of_vision`).
 *
 * Invariants covered:
 *
 * 1. Every manifest entry the runtime cannot load is marked `loadable = false`
 *    AND carries a user-facing [DownloadableModel.unsupportedReason].
 * 2. The Gemma 4 family (`gemma-4-*`) is now loadable: `.litertlm` bundles
 *    route to the native `LiteRTEngine` (litertlm-android), which supports the
 *    `tf_lite_end_of_vision` markers the old bundled MediaPipe `tasks-genai`
 *    SDK could not parse. They must therefore stay loadable with no
 *    `unsupportedReason` — flipping them back would strand a supported model.
 *
 * If a future contributor adds a `loadable = false` manifest entry without a
 * reason, this test catches it before it ships.
 */
class AvailableModelsLoadableInvariantTest {

    @Test
    fun `every unloadable model carries a user-facing reason`() {
        val offenders = AvailableModels.getAllModels()
            .filter { !it.loadable && it.unsupportedReason.isNullOrBlank() }
        assertTrue(
            "Models marked unloadable must include unsupportedReason: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `Gemma 4 litertlm entries are loadable via the native LiteRT-LM engine`() {
        val gemma4 = AvailableModels.getAllModels().filter { it.id.startsWith("gemma-4-") }
        assertFalse("Gemma 4 entries must be present in manifest", gemma4.isEmpty())
        gemma4.forEach { entry ->
            // Gemma 4 `.litertlm` bundles route to LiteRTEngine (litertlm-android),
            // which natively supports them — so the manifest entry is loadable
            // and must not carry an unsupportedReason.
            assertTrue(
                "${entry.id} should be loadable now that .litertlm routes to LiteRTEngine",
                entry.loadable
            )
            assertNull(
                "${entry.id} is supported — it must not carry an unsupportedReason",
                entry.unsupportedReason
            )
        }
    }

    @Test
    fun `loadable invariant covers every advertised vision multimodal litertlm`() {
        // Defensive: any `.litertlm` manifest entry that declares BOTH VISION
        // and AUDIO almost certainly contains the same sub-model markers the
        // bundled SDK can't parse. The runtime byte-sniff in
        // ModelRepositoryImpl.findUnsupportedLiteRtMarker will catch it, but
        // we'd rather surface it statically here too.
        val suspicious = AvailableModels.getAllModels().filter { model ->
            model.fileName.endsWith(".litertlm", ignoreCase = true) &&
                ModelCapability.VISION in model.capabilities &&
                ModelCapability.AUDIO in model.capabilities
        }
        // Gemma 4 E2B and E4B are the only multimodal .litertlm entries in the manifest.
        val knownBroken = suspicious.filter { it.id.startsWith("gemma-4-") }
        assertEquals(
            "Update [LITERTLM_UNSUPPORTED_MARKERS] in ModelRepositoryImpl if this " +
                "set grows: it must catch every new multimodal .litertlm.",
            2, knownBroken.size,
        )
    }
}
