package com.quantlm.yaser.data.audio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AudioInputManager"

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
        if (!exists()) mkdirs()
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
        audioDir.listFiles()?.forEach { it.delete() }
    }
}
