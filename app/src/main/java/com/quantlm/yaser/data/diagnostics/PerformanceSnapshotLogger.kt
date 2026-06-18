package com.quantlm.yaser.data.diagnostics

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Choreographer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Periodic system-state sampler that runs only while an inference operation
 * is in flight (model load or token generation). Emits structured snapshots
 * to a dedicated file so they do not churn [AppEventLogger]'s event log,
 * and so the user can include them in diagnostic exports.
 *
 * Sampled per tick (500 ms):
 *   - JVM heap: free / total / max (MB)
 *   - Native heap: allocated (MB) via [Debug.getNativeHeapAllocatedSize]
 *   - VmRSS (current resident), VmHWM (peak resident),
 *     VmPeak (peak virtual address space) — all MB, from /proc/self/status
 *   - MemAvailable, Cached (MB) from /proc/meminfo
 *   - majflt, minflt delta since previous tick from /proc/self/stat
 *   - Thermal status (NONE..SHUTDOWN) from [PowerManager.getCurrentThermalStatus]
 *   - availableProcessors() — drops when the OS hot-unplugs cores
 *   - worst_frame_ms: longest inter-frame interval seen on the Main thread
 *     since the previous sample (via Choreographer.FrameCallback). A
 *     healthy UI shows ~16; values above ~100 mean the main thread hung
 *     and the user experienced a visible stutter.
 *
 * Reference-counted [begin] / [end] so concurrent load + generation does not
 * double-sample. When the reference count reaches zero the sampler stops
 * and the next [begin] starts it fresh. [endAll] resets the counter
 * forcibly — call from stop paths so a missed [end] cannot leak the sampler
 * active across user-cancelled generations.
 */
object PerformanceSnapshotLogger {

    private const val TAG = "PerfSnap"
    private const val LOG_DIR_NAME = "diagnostics"
    private const val LOG_FILE_NAME = "perf_snapshots.log"
    private const val ROTATED_LOG_FILE_NAME = "perf_snapshots.log.1"

    // Two-file rotation, same scheme as AppEventLogger: an O(1) rename when
    // the live file passes this size. The old line-count trim re-read the
    // ENTIRE file on every 500 ms sample tick — steady measurable I/O and
    // allocation pressure during the very generations being measured.
    private const val MAX_LOG_BYTES = 1_500_000L
    private const val SAMPLE_INTERVAL_MS = 500L

    @Volatile
    private var appContext: Context? = null

    private val activeReasons = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var samplerJob: Job? = null
    private val fileLock = Any()
    private val tsFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Previous fault counters, kept across ticks of the same active span so
    // we can emit a per-tick delta (the absolute value is uninteresting).
    private var prevMajFlt: Long = 0L
    private var prevMinFlt: Long = 0L
    private var prevSampleAtMs: Long = 0L

    // 2F.3: Choreographer-based main-thread health probe. The FrameCallback
    // re-arms itself every frame and measures inter-frame intervals. The
    // sample worker reads `worstFrameNanosSinceLastSample` once per tick and
    // resets it. Lives on the Main looper; only active while a sampling span
    // is open.
    private val worstFrameNanosSinceLastSample = AtomicLong(0L)
    @Volatile private var lastFrameNanos: Long = 0L
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var frameCallback: Choreographer.FrameCallback? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getLogFilePath(context: Context? = null): String? {
        val ctx = context?.applicationContext ?: appContext ?: return null
        return getLogFile(ctx).absolutePath
    }

    /**
     * Read up to [maxLines] most-recent lines of the perf log. Returns an
     * empty list if no perf log has been written yet.
     */
    fun readRecentLines(maxLines: Int = 600, context: Context? = null): List<String> {
        val ctx = context?.applicationContext ?: appContext ?: return emptyList()
        return synchronized(fileLock) {
            try {
                val current = getLogFile(ctx)
                val lines = if (current.exists()) current.readLines() else emptyList()
                if (lines.size >= maxLines) {
                    lines.takeLast(maxLines)
                } else {
                    // Not enough in the live file — prepend the rotated half.
                    val rotated = getRotatedLogFile(ctx)
                    val older = if (rotated.exists()) rotated.readLines() else emptyList()
                    (older + lines).takeLast(maxLines)
                }
            } catch (e: Exception) {
                listOf("Could not read perf snapshot log: ${e.message ?: "unknown error"}")
            }
        }
    }

    /**
     * Begin (or join) an active sampling span identified by [reason]. Safe to
     * nest: the sampler stays on while at least one [begin] has not been
     * matched with [end]. [reason] is logged at start so a reader can tell
     * what triggered the span.
     */
    fun begin(reason: String) {
        if (appContext == null) return
        val count = activeReasons.incrementAndGet()
        appendLine("BEGIN reason=$reason activeCount=$count")
        if (count == 1) {
            startSampler()
        }
    }

    /** End a previously started span. */
    fun end(reason: String) {
        if (appContext == null) return
        val count = activeReasons.decrementAndGet().coerceAtLeast(0)
        appendLine("END reason=$reason activeCount=$count")
        if (count == 0) {
            stopSampler()
        }
    }

    /**
     * 2F.1: Forcibly close every open sampling span, regardless of how many
     * [begin] calls were not matched by [end]. Call from stop / cancel paths
     * (`InferenceRepositoryImpl.stopGeneration`, model unload) so a leaked
     * span doesn't keep the sampler running across the next generation —
     * which would surface as bogus `activeCount=2` in future log exports.
     */
    fun endAll(reason: String) {
        if (appContext == null) return
        val previous = activeReasons.getAndSet(0)
        if (previous > 0) {
            appendLine("END_ALL reason=$reason cleared=$previous")
            stopSampler()
        }
    }

    /**
     * Wrap a suspending block in a sampling span. Convenience helper.
     */
    suspend inline fun <T> withSampling(reason: String, block: () -> T): T {
        begin(reason)
        try {
            return block()
        } finally {
            end(reason)
        }
    }

    private fun startSampler() {
        samplerJob?.cancel()
        // Reset deltas so the first sample of a fresh span doesn't get spiked
        // by accumulated faults from before the sampler resumed.
        prevMajFlt = 0L
        prevMinFlt = 0L
        prevSampleAtMs = 0L
        worstFrameNanosSinceLastSample.set(0L)
        lastFrameNanos = 0L
        startFrameSampler()
        samplerJob = scope.launch {
            while (isActive) {
                try {
                    sampleOnce()
                } catch (t: Throwable) {
                    // A read of /proc can race with task teardown; log once and continue.
                    Log.w(TAG, "perf sample failed", t)
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun stopSampler() {
        samplerJob?.cancel()
        samplerJob = null
        stopFrameSampler()
    }

    /**
     * 2F.3: post a [Choreographer.FrameCallback] on the Main looper that
     * re-registers itself each frame. Tracks the worst inter-frame interval
     * seen since the last sample tick. Cost: one `postFrameCallback` per
     * Vsync (~16 ms), no allocation in the hot path. Inactive while no
     * sampling span is open.
     */
    private fun startFrameSampler() {
        if (frameCallback != null) return
        mainHandler.post {
            // If a stop happened between scheduling and execution, bail.
            if (samplerJob?.isActive != true) return@post
            val choreographer = Choreographer.getInstance()
            val cb = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val previous = lastFrameNanos
                    if (previous > 0L) {
                        val delta = frameTimeNanos - previous
                        // Race-tolerant max-update: we may lose a sample
                        // across the sample-tick getAndSet boundary, which
                        // is fine for diagnostic granularity.
                        val currentWorst = worstFrameNanosSinceLastSample.get()
                        if (delta > currentWorst) {
                            worstFrameNanosSinceLastSample.set(delta)
                        }
                    }
                    lastFrameNanos = frameTimeNanos
                    // Re-arm only while the sampler is still alive. Otherwise
                    // we'd keep this callback firing forever after stop.
                    if (frameCallback === this && samplerJob?.isActive == true) {
                        choreographer.postFrameCallback(this)
                    }
                }
            }
            frameCallback = cb
            choreographer.postFrameCallback(cb)
        }
    }

    private fun stopFrameSampler() {
        val cb = frameCallback ?: return
        frameCallback = null
        mainHandler.post {
            runCatching { Choreographer.getInstance().removeFrameCallback(cb) }
        }
    }

    private fun sampleOnce() {
        val ctx = appContext ?: return
        val rt = Runtime.getRuntime()
        val freeMb = rt.freeMemory() / (1024 * 1024)
        val totalMb = rt.totalMemory() / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        val usedMb = (totalMb - freeMb).coerceAtLeast(0L)
        val nativeMb = try {
            Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        } catch (_: Throwable) {
            -1L
        }

        val procStatus = readProcStatus()
        val vmRssKb = procStatus["VmRSS"] ?: -1L
        // VmPeak = peak *virtual* address space (mmap reservations, can
        // legitimately reach tens of GB on 64-bit Android — meaningless as
        // a physical memory signal). VmHWM = peak resident set size, which
        // is what "peak RSS" actually means. Both are emitted with clear
        // labels so future analysis isn't misled.
        val vmPeakKb = procStatus["VmPeak"] ?: -1L
        val vmHwmKb = procStatus["VmHWM"] ?: -1L

        val memInfo = readMemInfo()
        val memAvailKb = memInfo["MemAvailable"] ?: -1L
        val cachedKb = memInfo["Cached"] ?: -1L

        val faults = readProcStat()
        val now = System.currentTimeMillis()
        val majDelta: Long
        val minDelta: Long
        if (prevSampleAtMs == 0L) {
            majDelta = 0L
            minDelta = 0L
        } else {
            majDelta = (faults.first - prevMajFlt).coerceAtLeast(0L)
            minDelta = (faults.second - prevMinFlt).coerceAtLeast(0L)
        }
        prevMajFlt = faults.first
        prevMinFlt = faults.second
        prevSampleAtMs = now

        val thermal = readThermalStatus(ctx)
        val cores = Runtime.getRuntime().availableProcessors()

        // 2F.3: drain the worst frame-time seen since last sample. Reset to 0
        // so the next tick reports its own window. Race window is one frame;
        // acceptable for diagnostics.
        val worstFrameNanos = worstFrameNanosSinceLastSample.getAndSet(0L)
        val worstFrameMs = worstFrameNanos / 1_000_000L

        val line = buildString {
            append("SAMPLE")
            append(" jvm_used_mb=").append(usedMb)
            append(" jvm_max_mb=").append(maxMb)
            append(" native_mb=").append(nativeMb)
            append(" rss_mb=").append(kbToMb(vmRssKb))
            // 2F.2: real peak RSS comes from VmHWM, not VmPeak.
            append(" peak_rss_mb=").append(kbToMb(vmHwmKb))
            append(" peak_vsize_mb=").append(kbToMb(vmPeakKb))
            append(" sys_avail_mb=").append(kbToMb(memAvailKb))
            append(" sys_cached_mb=").append(kbToMb(cachedKb))
            append(" majflt_delta=").append(majDelta)
            append(" minflt_delta=").append(minDelta)
            append(" thermal=").append(thermal)
            append(" cores=").append(cores)
            append(" worst_frame_ms=").append(worstFrameMs)
            append(" active=").append(activeReasons.get())
        }
        appendLine(line)
    }

    private fun appendLine(payload: String) {
        val ctx = appContext ?: return
        val ts = tsFormatter.format(Date())
        val line = "$ts | $payload"
        synchronized(fileLock) {
            try {
                val file = getLogFile(ctx)
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
                if (file.length() > MAX_LOG_BYTES) {
                    rotate(ctx, file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing perf snapshot log", e)
            }
        }
    }

    // O(1) size cap: the live file becomes the .1 file (replacing the old one)
    // and sampling continues into a fresh live file.
    private fun rotate(ctx: Context, current: File) {
        val rotated = getRotatedLogFile(ctx)
        if (rotated.exists()) rotated.delete()
        if (!current.renameTo(rotated)) {
            Log.w(TAG, "Perf log rotation rename failed; truncating live log")
            current.writeText("")
        }
    }

    private fun getLogFile(context: Context): File {
        return File(File(context.filesDir, LOG_DIR_NAME), LOG_FILE_NAME)
    }

    private fun getRotatedLogFile(context: Context): File {
        return File(File(context.filesDir, LOG_DIR_NAME), ROTATED_LOG_FILE_NAME)
    }

    private fun readProcStatus(): Map<String, Long> {
        return try {
            val map = HashMap<String, Long>()
            File("/proc/self/status").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) return@forEach
                    val key = line.substring(0, colon)
                    if (key != "VmRSS" && key != "VmPeak" && key != "VmHWM") return@forEach
                    val rest = line.substring(colon + 1).trim()
                    val firstSpace = rest.indexOf(' ')
                    val numberPart = if (firstSpace > 0) rest.substring(0, firstSpace) else rest
                    val value = numberPart.toLongOrNull() ?: return@forEach
                    map[key] = value
                }
            }
            map
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun readMemInfo(): Map<String, Long> {
        return try {
            val map = HashMap<String, Long>()
            File("/proc/meminfo").bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon <= 0) return@forEach
                    val key = line.substring(0, colon)
                    if (key != "MemAvailable" && key != "Cached") return@forEach
                    val rest = line.substring(colon + 1).trim()
                    val firstSpace = rest.indexOf(' ')
                    val numberPart = if (firstSpace > 0) rest.substring(0, firstSpace) else rest
                    val value = numberPart.toLongOrNull() ?: return@forEach
                    map[key] = value
                }
            }
            map
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    /**
     * /proc/self/stat fields (1-indexed in `man 5 proc`):
     *   10 = minflt, 12 = majflt.
     *
     * Field 2 is "comm" which may contain spaces and is wrapped in
     * parentheses, so we parse from after the closing paren to be safe.
     */
    private fun readProcStat(): Pair<Long, Long> {
        return try {
            val raw = File("/proc/self/stat").readText()
            val close = raw.lastIndexOf(')')
            if (close < 0) return 0L to 0L
            // After ") ", field 3 starts. So token index 0 = field 3.
            val fields = raw.substring(close + 1).trim().split(' ')
            // minflt is field 10 → tokens[7], majflt is field 12 → tokens[9].
            val minflt = fields.getOrNull(7)?.toLongOrNull() ?: 0L
            val majflt = fields.getOrNull(9)?.toLongOrNull() ?: 0L
            majflt to minflt
        } catch (_: Throwable) {
            0L to 0L
        }
    }

    private fun readThermalStatus(ctx: Context): String {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "n/a"
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return "n/a"
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN"
            }
        } catch (_: Throwable) {
            "n/a"
        }
    }

    private fun kbToMb(kb: Long): Long {
        if (kb < 0) return -1L
        return kb / 1024
    }

    /** Test/teardown hook; not exposed publicly. */
    internal fun shutdown() {
        stopSampler()
        scope.cancel()
    }
}
