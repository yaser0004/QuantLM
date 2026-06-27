package com.quantlm.yaser.data.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioInputManager"

/** Sample rate expected by audio-capable models (Gemma 3n audio encoder). */
private const val TARGET_SAMPLE_RATE = 16000

/**
 * State of the audio input
 */
sealed class AudioInputState {
    object Idle : AudioInputState()
    object Listening : AudioInputState()
    object Processing : AudioInputState()
    data class Result(val text: String) : AudioInputState()
    data class Error(val message: String) : AudioInputState()
}

/**
 * Audio recording state for manual recording
 */
sealed class RecordingState {
    object Idle : RecordingState()
    object Recording : RecordingState()
    object Processing : RecordingState()
    data class Complete(val filePath: String, val durationMs: Long) : RecordingState()
    data class Error(val message: String) : RecordingState()
}

/**
 * Manages audio input for speech-to-text queries.
 * Supports both live speech recognition (online) and audio recording for later transcription.
 */
@Singleton
class AudioInputManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private var recordingStartTime = 0L
    
    private val _audioInputState = MutableStateFlow<AudioInputState>(AudioInputState.Idle)
    val audioInputState: StateFlow<AudioInputState> = _audioInputState.asStateFlow()
    
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()
    
    private val audioDir = File(context.cacheDir, "audio_recordings").apply {
        if (!exists() && !mkdirs()) {
            AppEventLogger.warn(TAG, "audio_dir_create_failed", "path=$absolutePath")
        }
    }
    
    /**
     * Check if speech recognition is available
     */
    fun isSpeechRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }
    
    /**
     * Check if audio record permission is granted
     */
    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Start listening for speech input (uses Google Speech Recognition)
     */
    fun startListening(
        language: String = Locale.getDefault().toLanguageTag(),
        onResult: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (!hasRecordPermission()) {
            val error = "Microphone permission not granted"
            _audioInputState.value = AudioInputState.Error(error)
            onError(error)
            return
        }
        
        if (!isSpeechRecognitionAvailable()) {
            val error = "Speech recognition not available"
            _audioInputState.value = AudioInputState.Error(error)
            onError(error)
            return
        }
        
        // Release any existing recognizer
        speechRecognizer?.destroy()
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Ready for speech")
                    _audioInputState.value = AudioInputState.Listening
                }
                
                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Speech begun")
                }
                
                override fun onRmsChanged(rmsdB: Float) {
                    // Audio level changed - could be used for visual feedback
                }
                
                override fun onBufferReceived(buffer: ByteArray?) {}
                
                override fun onEndOfSpeech() {
                    Log.d(TAG, "Speech ended")
                    _audioInputState.value = AudioInputState.Processing
                }
                
                override fun onError(error: Int) {
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                        else -> "Unknown error: $error"
                    }
                    Log.e(TAG, "Speech recognition error: $errorMessage")
                    _audioInputState.value = AudioInputState.Error(errorMessage)
                    onError(errorMessage)
                }
                
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    Log.d(TAG, "Speech result: $text")
                    _audioInputState.value = AudioInputState.Result(text)
                    onResult(text)
                }
                
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialText = matches?.firstOrNull() ?: ""
                    Log.d(TAG, "Partial result: $partialText")
                }
                
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        
        speechRecognizer?.startListening(intent)
        _audioInputState.value = AudioInputState.Listening
    }
    
    /**
     * Stop listening
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
        _audioInputState.value = AudioInputState.Idle
    }
    
    /**
     * Cancel speech recognition
     */
    fun cancelListening() {
        speechRecognizer?.cancel()
        _audioInputState.value = AudioInputState.Idle
    }
    
    /**
     * Reset to idle state
     */
    fun resetState() {
        _audioInputState.value = AudioInputState.Idle
        _recordingState.value = RecordingState.Idle
    }
    
    /**
     * Start recording audio to file (for offline transcription)
     */
    @Suppress("MissingPermission")
    fun startRecording(): Boolean {
        if (!hasRecordPermission()) {
            _recordingState.value = RecordingState.Error("Microphone permission not granted")
            return false
        }
        
        if (isRecording) return false
        
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                _recordingState.value = RecordingState.Error("Failed to initialize audio recorder")
                return false
            }
            
            val outputFile = File(audioDir, "recording_${System.currentTimeMillis()}.pcm")
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            _recordingState.value = RecordingState.Recording
            
            audioRecord?.startRecording()
            
            recordingThread = Thread {
                // This runs on its own thread: an uncaught exception here would
                // crash the whole process, so capture failures and surface them
                // through RecordingState instead.
                try {
                    val buffer = ByteArray(bufferSize)
                    FileOutputStream(outputFile).use { output ->
                        while (isRecording) {
                            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                            if (read > 0) {
                                output.write(buffer, 0, read)
                            }
                        }
                    }

                    val durationMs = System.currentTimeMillis() - recordingStartTime
                    _recordingState.value = RecordingState.Complete(outputFile.absolutePath, durationMs)
                } catch (e: Exception) {
                    AppEventLogger.error(TAG, "recording_write_failed", "file=${outputFile.name}", e)
                    isRecording = false
                    _recordingState.value = RecordingState.Error("Recording failed: ${e.message ?: "I/O error"}")
                }
            }
            recordingThread?.start()
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _recordingState.value = RecordingState.Error("Failed to start recording: ${e.message}")
            return false
        }
    }
    
    /**
     * Stop recording
     */
    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingThread?.interrupt()
            recordingThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }
    
    /**
     * Clean up resources
     */
    fun release() {
        stopListening()
        stopRecording()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
    
    /**
     * Delete all recorded audio files
     */
    fun clearRecordings() {
        val files = audioDir.listFiles() ?: return
        var failures = 0
        files.forEach { if (!it.delete()) failures++ }
        if (failures > 0) {
            AppEventLogger.warn(TAG, "clear_recordings_partial", "failed=$failures/${files.size}")
        }
    }

    private val audioScribeDir = File(context.cacheDir, "audio_scribe").apply {
        if (!exists() && !mkdirs()) {
            AppEventLogger.warn(TAG, "audio_scribe_dir_create_failed", "path=$absolutePath")
        }
    }

    /**
     * Phase 2 (§3.8): copy a user-picked audio file into app-internal cache so
     * downstream code (engine, send-message pipeline, persistence) gets a
     * stable path that survives the original `content://` Uri's lifetime.
     *
     * Returns null on read failure.
     */
    fun pickAudioFile(uri: android.net.Uri): File? {
        return try {
            val ext = context.contentResolver.getType(uri)?.let { type ->
                when {
                    type.contains("wav") -> "wav"
                    type.contains("m4a") || type.contains("mp4") -> "m4a"
                    type.contains("mpeg") || type.contains("mp3") -> "mp3"
                    type.contains("ogg") -> "ogg"
                    else -> "audio"
                }
            } ?: "audio"
            val dest = File(audioScribeDir, "scribe_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy audio file from $uri", e)
            null
        }
    }

    /**
     * Wrap a raw PCM recording (16 kHz mono 16-bit, as produced by
     * [startRecording]) in a 44-byte WAV header so it becomes a self-describing
     * container the model runtime can decode. Returns the new `.wav` file.
     */
    fun pcmToWav(pcmFile: File): File? {
        return try {
            val pcm = pcmFile.readBytes()
            val dest = File(audioScribeDir, "rec_${System.currentTimeMillis()}.wav")
            writeWav(dest, pcm, TARGET_SAMPLE_RATE)
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert PCM recording to WAV", e)
            null
        }
    }

    /**
     * Copy a user-picked audio file and transcode it to a 16 kHz mono 16-bit
     * WAV file the model runtime can consume. Handles compressed formats
     * (mp3/m4a/ogg) via [MediaExtractor] + [MediaCodec]. Returns null on failure.
     */
    fun prepareAudioForModel(uri: android.net.Uri): File? {
        val copied = pickAudioFile(uri) ?: return null
        return try {
            decodeToWav(copied)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare picked audio for the model", e)
            null
        } finally {
            copied.delete()
        }
    }

    /** Write [pcm] (16-bit little-endian, mono) to [dest] as a WAV file. */
    private fun writeWav(dest: File, pcm: ByteArray, sampleRate: Int) {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)
        fun putInt(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = (value shr 8 and 0xff).toByte()
            header[offset + 2] = (value shr 16 and 0xff).toByte()
            header[offset + 3] = (value shr 24 and 0xff).toByte()
        }
        fun putShort(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = (value shr 8 and 0xff).toByte()
        }
        "RIFF".forEachIndexed { i, c -> header[i] = c.code.toByte() }
        putInt(4, 36 + pcm.size)
        "WAVE".forEachIndexed { i, c -> header[8 + i] = c.code.toByte() }
        "fmt ".forEachIndexed { i, c -> header[12 + i] = c.code.toByte() }
        putInt(16, 16)              // PCM sub-chunk size
        putShort(20, 1)             // audio format = PCM
        putShort(22, channels)
        putInt(24, sampleRate)
        putInt(28, byteRate)
        putShort(32, channels * bitsPerSample / 8)
        putShort(34, bitsPerSample)
        "data".forEachIndexed { i, c -> header[36 + i] = c.code.toByte() }
        putInt(40, pcm.size)
        FileOutputStream(dest).use { out ->
            out.write(header)
            out.write(pcm)
        }
    }

    /**
     * Decode any [MediaExtractor]-readable audio file to 16 kHz mono 16-bit
     * WAV. Downmixes multi-channel audio and resamples to [TARGET_SAMPLE_RATE].
     */
    private fun decodeToWav(src: File): File? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(src.absolutePath)
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    extractor.selectTrack(i)
                    trackFormat = f
                    break
                }
            }
            val format = trackFormat ?: run {
                Log.e(TAG, "No audio track in ${src.name}")
                return null
            }
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        val chunk = ByteArray(bufferInfo.size)
                        outBuf.get(chunk)
                        outBuf.clear()
                        pcmOut.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                }
            }

            var pcm = pcmOut.toByteArray()
            if (srcChannels > 1) pcm = downmixToMono(pcm, srcChannels)
            if (srcSampleRate != TARGET_SAMPLE_RATE) {
                pcm = resample(pcm, srcSampleRate, TARGET_SAMPLE_RATE)
            }
            val dest = File(audioScribeDir, "picked_${System.currentTimeMillis()}.wav")
            writeWav(dest, pcm, TARGET_SAMPLE_RATE)
            return dest
        } catch (e: Exception) {
            // Corrupt/unsupported audio, missing format keys, or codec failure:
            // degrade to null so the caller reports it rather than crashing.
            AppEventLogger.error(TAG, "audio_decode_failed", "file=${src.name}", e)
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** Average interleaved 16-bit PCM channels down to a single mono channel. */
    private fun downmixToMono(pcm: ByteArray, channels: Int): ByteArray {
        val frameCount = pcm.size / 2 / channels
        val out = ByteArray(frameCount * 2)
        for (frame in 0 until frameCount) {
            var sum = 0
            for (ch in 0 until channels) {
                val idx = (frame * channels + ch) * 2
                val sample = (pcm[idx].toInt() and 0xff) or (pcm[idx + 1].toInt() shl 8)
                sum += sample.toShort().toInt()
            }
            val mono = (sum / channels).toShort().toInt()
            out[frame * 2] = (mono and 0xff).toByte()
            out[frame * 2 + 1] = (mono shr 8 and 0xff).toByte()
        }
        return out
    }

    /** Linear-interpolation resampler for mono 16-bit PCM. */
    private fun resample(pcm: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        val srcSamples = pcm.size / 2
        if (srcSamples == 0) return pcm
        fun sampleAt(i: Int): Int {
            val idx = i * 2
            return ((pcm[idx].toInt() and 0xff) or (pcm[idx + 1].toInt() shl 8)).toShort().toInt()
        }
        val dstSamples = (srcSamples.toLong() * dstRate / srcRate).toInt().coerceAtLeast(1)
        val out = ByteArray(dstSamples * 2)
        for (i in 0 until dstSamples) {
            val srcPos = i.toDouble() * srcRate / dstRate
            val i0 = srcPos.toInt().coerceIn(0, srcSamples - 1)
            val i1 = (i0 + 1).coerceAtMost(srcSamples - 1)
            val frac = srcPos - i0
            val value = (sampleAt(i0) * (1 - frac) + sampleAt(i1) * frac).toInt()
            out[i * 2] = (value and 0xff).toByte()
            out[i * 2 + 1] = (value shr 8 and 0xff).toByte()
        }
        return out
    }
}
