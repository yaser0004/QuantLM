package com.quantlm.yaser.data.inference

import android.util.Log
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the three inference engine singletons and centralizes the
 * "exactly one engine carries native state at a time" invariant.
 *
 * The model repository was already unloading the previously-active engine before
 * switching, but partial-init failures and exception paths could leave a second
 * engine with native allocations still mapped — the Graphics buffer in particular
 * stays mapped after a failed GPU init. This registry ensures the inactive engines
 * have their native state dropped whenever the active one is settled, and exposes
 * a memory-trim hook so we can free inactive engines from
 * `Application.onTrimMemory` instead of waiting for the OS to LMK us.
 */
@Singleton
class EngineRegistry @Inject constructor(
    val llama: LlamaEngine,
    val liteRT: LiteRTEngine,
    val mediaPipe: MediaPipeEngine,
) {

    enum class Active { NONE, LLAMA, LITERT, MEDIAPIPE }

    @Volatile
    private var active: Active = Active.NONE

    fun setActive(active: Active) {
        this.active = active
        AppEventLogger.info(
            component = TAG,
            action = "set_active",
            details = "active=$active"
        )
    }

    /**
     * Force-unload every engine except the active one. Safe to call repeatedly;
     * each engine's `unloadModel` is a no-op when nothing is loaded.
     */
    fun releaseInactive() {
        if (active != Active.LLAMA) safeUnload("llama", llama::unloadModel)
        if (active != Active.LITERT) safeUnload("litert", liteRT::unloadModel)
        if (active != Active.MEDIAPIPE) safeUnload("mediapipe", mediaPipe::unloadModel)
    }

    /**
     * Drop *everything*, including the active engine. Called when the user
     * explicitly unloads, or under critical memory pressure when we'd rather
     * lose the model than be killed.
     */
    fun releaseAll() {
        safeUnload("llama", llama::unloadModel)
        safeUnload("litert", liteRT::unloadModel)
        safeUnload("mediapipe", mediaPipe::unloadModel)
        active = Active.NONE
    }

    private inline fun safeUnload(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "Unload of $name engine threw; ignoring", t)
        }
    }

    companion object {
        private const val TAG = "EngineRegistry"
    }
}
