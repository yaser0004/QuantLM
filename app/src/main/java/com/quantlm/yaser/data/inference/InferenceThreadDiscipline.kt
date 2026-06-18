package com.quantlm.yaser.data.inference

import android.os.Process
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Single chokepoint that enforces "inference work runs at a low scheduler
 * priority with a bounded thread count". Every engine load / generate path
 * must route its IO-thread block through [runWithInferencePriority] so the
 * OS scheduler unambiguously prefers System UI, launcher and other apps
 * when CPU is contended.
 *
 * Why this exists: native LLM inference saturates every core it can reach.
 * At default (nice 0) priority that starves system_server, producing visible
 * device-wide jank and occasional "System UI not responding" ANRs even when
 * QuantLM is in the background. Reducing this one process's scheduling
 * weight costs ~10–20 % per-token speed and removes the system-wide impact.
 *
 * Engine-agnostic by design: any future engine (ONNX, Llama 4, …) only has
 * to wrap its inference dispatch in [runWithInferencePriority] to inherit
 * the discipline. Pure JVM API — no device-specific tuning.
 */
object InferenceThreadDiscipline {

    /**
     * Nice value applied to the JVM thread that runs inference, and inherited
     * by any worker threads MediaPipe / LiteRT / llama.cpp spawn from it on
     * Linux. Compose's UI thread runs at [Process.THREAD_PRIORITY_DISPLAY]
     * (-4); foreground app code at 0. We sit at +5 — firmly deprioritized
     * below UI but *not* across Android's BG scheduler-group cutoff.
     *
     * The cutoff matters: at `Process.THREAD_PRIORITY_BACKGROUND` (= +10)
     * Android moves the thread into the BG cgroup, which CPU-caps it to a
     * small slice of one core. We initially used +11 (BG + LESS_FAVORABLE)
     * and it produced *worse* UI smoothness — the kernel scheduler thrashed
     * with deferred wake-ups and off-CPU stalls during MediaPipe's prefill
     * burst, which surfaced as 1–3 second `Davey!` frame hangs. Staying at
     * +5 keeps the scheduler's preference order intact (UI -4 < app 0 <
     * inference +5) without engaging cgroup throttling. Do not raise this
     * back across +10 without explicit re-evaluation.
     *
     * `@PublishedApi internal` because [runWithInferencePriority] is `inline`
     * and Kotlin requires constants referenced from an inline body to be at
     * least as visible as the function.
     */
    @PublishedApi
    internal const val INFERENCE_NICE = 5

    /**
     * Headroom we leave for the OS even on a small device. With 2 cores
     * reserved, System UI's main thread + system_server + the app's own UI
     * thread always have somewhere to run while inference saturates the rest.
     */
    private const val RESERVED_CORES_FOR_SYSTEM = 2

    /**
     * Run [block] with the calling thread's priority lowered to
     * [INFERENCE_NICE]. The original priority is restored in a `finally`,
     * so the thread is safe to return to the dispatcher pool. Best-effort:
     * if setpriority is denied (extremely rare on Android), the block still
     * runs at whatever priority the thread already had.
     */
    inline fun <T> runWithInferencePriority(block: () -> T): T {
        val tid = Process.myTid()
        val previous = try {
            Process.getThreadPriority(tid)
        } catch (_: Throwable) {
            Process.THREAD_PRIORITY_DEFAULT
        }
        try {
            Process.setThreadPriority(tid, INFERENCE_NICE)
        } catch (_: Throwable) {
            // Some hardened devices refuse setpriority; ignore and proceed.
        }
        try {
            return block()
        } finally {
            try {
                Process.setThreadPriority(tid, previous)
            } catch (_: Throwable) {
                // Best-effort restore.
            }
        }
    }

    /**
     * Cap [requested] thread count to leave [RESERVED_CORES_FOR_SYSTEM]
     * cores free for system processes. Floors at 1.
     *
     * Examples:
     *  - 8-core phone, requested=4 → 4 (≤ availableProcessors − 2 = 6)
     *  - 8-core phone, requested=8 → 6
     *  - 4-core phone, requested=4 → 2
     *  - 2-core phone, requested=4 → 1
     */
    fun computeInferenceThreadCount(requested: Int): Int {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cap = (cores - RESERVED_CORES_FOR_SYSTEM).coerceAtLeast(1)
        return requested.coerceIn(1, cap)
    }

    /**
     * Coroutine-context variant of [runWithInferencePriority] for engines
     * whose load/generate paths SUSPEND (LiteRT, MediaPipe: mutex waits,
     * native-callback bridges, future watchers).
     *
     * The inline wrapper restores priority only when its block *returns* — a
     * coroutine suspending inside it parked its pooled dispatcher thread at
     * [INFERENCE_NICE] until resume, deprioritizing whatever unrelated work
     * that thread picked up next. A [ThreadContextElement] is invoked by the
     * coroutine runtime on every resume/suspend on whichever thread the
     * coroutine lands: nice is applied for exactly the slices that run
     * inference code, and each thread's previous priority is restored the
     * moment the coroutine leaves it.
     *
     * Usage: `withContext(Dispatchers.IO + InferenceThreadDiscipline.inferencePriority()) { … }`.
     * The llama.cpp paths keep [runWithInferencePriority]: their native calls
     * are fully synchronous, where the inline form is already correct.
     */
    fun inferencePriority(): CoroutineContext.Element = InferencePriorityElement()
}

private class InferencePriorityElement :
    ThreadContextElement<Int>,
    AbstractCoroutineContextElement(Key) {

    private companion object Key : CoroutineContext.Key<InferencePriorityElement>

    override fun updateThreadContext(context: CoroutineContext): Int {
        val tid = Process.myTid()
        val previous = try {
            Process.getThreadPriority(tid)
        } catch (_: Throwable) {
            Process.THREAD_PRIORITY_DEFAULT
        }
        try {
            Process.setThreadPriority(tid, InferenceThreadDiscipline.INFERENCE_NICE)
        } catch (_: Throwable) {
            // Some hardened devices refuse setpriority; proceed at current priority.
        }
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Int) {
        try {
            Process.setThreadPriority(Process.myTid(), oldState)
        } catch (_: Throwable) {
            // Best-effort restore.
        }
    }
}
