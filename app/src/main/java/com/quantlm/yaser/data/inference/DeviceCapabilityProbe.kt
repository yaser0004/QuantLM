package com.quantlm.yaser.data.inference

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cheap, synchronous device-capability checks consulted before a model load,
 * so a GPU-incapable device is not made to sit through a doomed 10–30 s GPU
 * init attempt before falling back.
 *
 * These are coarse hardware-feature queries only — they do not load any
 * inference runtime.
 */
@Singleton
class DeviceCapabilityProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        const val TAG = "DeviceCapabilityProbe"
    }

    /** Whether the device advertises a Vulkan-capable GPU. */
    fun isVulkanAvailable(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)

    /** Major version of the device's OpenGL ES support (e.g. 3 for ES 3.x). */
    fun openGlEsMajorVersion(): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.deviceConfigurationInfo.reqGlEsVersion ushr 16
        } catch (e: Exception) {
            Log.w(TAG, "Could not query OpenGL ES version: ${e.message}")
            0
        }
    }

    /**
     * Whether on-device GPU inference is plausible. The GPU engines — Vulkan
     * for llama.cpp, GL ES compute for MediaPipe — need at least one of these.
     */
    fun supportsGpuInference(): Boolean =
        isVulkanAvailable() || openGlEsMajorVersion() >= 3

    /**
     * Returns a user-facing reason string if this device is known to crash when
     * loading Gemma 4 multimodal `.litertlm` models with LiteRT-LM 0.11.0, or
     * null if no barrier applies.
     *
     * Two upstream bugs make Gemma 4 unrunnable on certain chipsets:
     *  - Samsung Exynos: Engine.initialize() triggers an absl::CHECK abort
     *    (SIGABRT, uncatchable from JVM). GitHub google-ai-edge/LiteRT-LM#1864.
     *  - MediaTek Dimensity: nativeSendMessage SIGSEGV on the second sequential
     *    call, making multi-turn chat impossible. GitHub LiteRT-LM#1849.
     * Both will be re-evaluated when a newer litertlm-android release is available.
     */
    fun gemma4LoadBarrier(): String? {
        val hw = Build.HARDWARE.lowercase()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.lowercase()
        } else ""

        return when {
            // Samsung Exynos: hardware "exynos*" / "s5e*"; SOC_MODEL "exynos*" / "s5e*"
            hw.startsWith("exynos") || hw.startsWith("s5e") ||
            soc.startsWith("exynos") || soc.startsWith("s5e") ->
                "Gemma 4 is not yet stable on Samsung Exynos chips " +
                "(Engine.initialize() crashes in LiteRT-LM 0.11.0 on this SoC). " +
                "Check for a QuantLM update."

            // MediaTek: hardware "mt6*" / "mt8*"; SOC_MODEL "mt*"; or "dimensity" anywhere
            hw.startsWith("mt6") || hw.startsWith("mt8") || hw.contains("dimensity") ||
            soc.startsWith("mt6") || soc.startsWith("mt8") || soc.contains("dimensity") ->
                "Gemma 4 multi-turn chat crashes on MediaTek Dimensity chips " +
                "(SIGSEGV in nativeSendMessage in LiteRT-LM 0.11.0). " +
                "Check for a QuantLM update."

            else -> null
        }
    }
}
