package com.quantlm.yaser.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.domain.tts.TtsVoiceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "generation_settings")

class GenerationPreferences(private val context: Context) {
    
    companion object {
        private const val TAG = "GenerationPreferences"

        private val TEMPERATURE = floatPreferencesKey("temperature")
        private val MAX_TOKENS = intPreferencesKey("max_tokens")
        private val TOP_P = floatPreferencesKey("top_p")
        private val TOP_K = intPreferencesKey("top_k")
        private val REPEAT_PENALTY = floatPreferencesKey("repeat_penalty")
        private val REPEAT_LAST_N = intPreferencesKey("repeat_last_n")
        private val MIN_P = floatPreferencesKey("min_p")
        private val TFS_Z = floatPreferencesKey("tfs_z")
        private val TYPICAL_P = floatPreferencesKey("typical_p")
        private val MIROSTAT = intPreferencesKey("mirostat")
        private val MIROSTAT_TAU = floatPreferencesKey("mirostat_tau")
        private val MIROSTAT_ETA = floatPreferencesKey("mirostat_eta")
        private val CONTEXT_LENGTH = intPreferencesKey("context_length")
        private val CPU_THREADS = intPreferencesKey("cpu_threads")
        private val GPU_LAYERS = intPreferencesKey("gpu_layers")
        private val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        private val TTS_VOICE_GENDER = stringPreferencesKey("tts_voice_gender")
        private val TTS_VOICE_PROFILE = stringPreferencesKey("tts_voice_profile")
        private const val LEGACY_DEFAULT_PROMPT = "You are a helpful AI assistant."
        private const val LEGACY_NO_EMOJI_PROMPT = "You are a helpful AI assistant. Do not use emoji characters in your responses - use text-based emoticons like :) or <3 instead."
        
        // Defaults
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_MAX_TOKENS = 512  // Higher default to avoid mid-sentence cutoffs
        const val DEFAULT_TOP_P = 0.9f
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_REPEAT_PENALTY = 1.1f
        const val DEFAULT_REPEAT_LAST_N = 64
        const val DEFAULT_MIN_P = 0.05f
        const val DEFAULT_TFS_Z = 1.0f
        const val DEFAULT_TYPICAL_P = 1.0f
        const val DEFAULT_MIROSTAT = 0
        const val DEFAULT_MIROSTAT_TAU = 5.0f
        const val DEFAULT_MIROSTAT_ETA = 0.1f
        const val DEFAULT_CONTEXT_LENGTH = 2048
        const val DEFAULT_CPU_THREADS = 4
        const val DEFAULT_GPU_LAYERS = 0
        const val DEFAULT_SYSTEM_PROMPT = ""
    }
    
    data class GenerationSettings(
        val temperature: Float = DEFAULT_TEMPERATURE,
        val maxTokens: Int = DEFAULT_MAX_TOKENS,
        val topP: Float = DEFAULT_TOP_P,
        val topK: Int = DEFAULT_TOP_K,
        val repeatPenalty: Float = DEFAULT_REPEAT_PENALTY,
        val repeatLastN: Int = DEFAULT_REPEAT_LAST_N,
        val minP: Float = DEFAULT_MIN_P,
        val tfsZ: Float = DEFAULT_TFS_Z,
        val typicalP: Float = DEFAULT_TYPICAL_P,
        val mirostat: Int = DEFAULT_MIROSTAT,
        val mirostatTau: Float = DEFAULT_MIROSTAT_TAU,
        val mirostatEta: Float = DEFAULT_MIROSTAT_ETA,
        val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        val ttsVoiceProfile: TtsVoiceProfile = TtsVoiceProfile.CLASSIC_LEGACY
    )

    data class HardwareSettings(
        val contextLength: Int = DEFAULT_CONTEXT_LENGTH,
        val cpuThreads: Int = DEFAULT_CPU_THREADS,
        val gpuLayers: Int = DEFAULT_GPU_LAYERS
    )
    
    suspend fun saveSettings(settings: GenerationSettings) {
        context.settingsDataStore.edit { preferences ->
            preferences[TEMPERATURE] = settings.temperature
            preferences[MAX_TOKENS] = settings.maxTokens
            preferences[TOP_P] = settings.topP
            preferences[TOP_K] = settings.topK
            preferences[REPEAT_PENALTY] = settings.repeatPenalty
            preferences[REPEAT_LAST_N] = settings.repeatLastN
            preferences[MIN_P] = settings.minP
            preferences[TFS_Z] = settings.tfsZ
            preferences[TYPICAL_P] = settings.typicalP
            preferences[MIROSTAT] = settings.mirostat
            preferences[MIROSTAT_TAU] = settings.mirostatTau
            preferences[MIROSTAT_ETA] = settings.mirostatEta
            preferences[SYSTEM_PROMPT] = settings.systemPrompt
            preferences[TTS_VOICE_PROFILE] = settings.ttsVoiceProfile.storageValue
        }
        AppEventLogger.info(
            component = TAG,
            action = "save_settings",
            details = "temp=${settings.temperature}, maxTokens=${settings.maxTokens}, topP=${settings.topP}, topK=${settings.topK}, voice=${settings.ttsVoiceProfile.storageValue}"
        )
    }
    
    fun getSettings(): Flow<GenerationSettings> {
        return context.settingsDataStore.data.map { preferences ->
            val storedPrompt = preferences[SYSTEM_PROMPT]
            val resolvedPrompt = when (storedPrompt) {
                null -> DEFAULT_SYSTEM_PROMPT
                LEGACY_DEFAULT_PROMPT -> DEFAULT_SYSTEM_PROMPT
                LEGACY_NO_EMOJI_PROMPT -> DEFAULT_SYSTEM_PROMPT
                else -> storedPrompt
            }

            GenerationSettings(
                temperature = preferences[TEMPERATURE] ?: DEFAULT_TEMPERATURE,
                maxTokens = preferences[MAX_TOKENS] ?: DEFAULT_MAX_TOKENS,
                topP = preferences[TOP_P] ?: DEFAULT_TOP_P,
                topK = preferences[TOP_K] ?: DEFAULT_TOP_K,
                repeatPenalty = preferences[REPEAT_PENALTY] ?: DEFAULT_REPEAT_PENALTY,
                repeatLastN = preferences[REPEAT_LAST_N] ?: DEFAULT_REPEAT_LAST_N,
                minP = preferences[MIN_P] ?: DEFAULT_MIN_P,
                tfsZ = preferences[TFS_Z] ?: DEFAULT_TFS_Z,
                typicalP = preferences[TYPICAL_P] ?: DEFAULT_TYPICAL_P,
                mirostat = preferences[MIROSTAT] ?: DEFAULT_MIROSTAT,
                mirostatTau = preferences[MIROSTAT_TAU] ?: DEFAULT_MIROSTAT_TAU,
                mirostatEta = preferences[MIROSTAT_ETA] ?: DEFAULT_MIROSTAT_ETA,
                systemPrompt = resolvedPrompt,
                ttsVoiceProfile = TtsVoiceProfile.fromStorage(
                    preferences[TTS_VOICE_PROFILE],
                    preferences[TTS_VOICE_GENDER]
                )
            )
        }
    }

    fun getHardwareSettings(defaultCpuThreads: Int = DEFAULT_CPU_THREADS): Flow<HardwareSettings> {
        return context.settingsDataStore.data.map { preferences ->
            HardwareSettings(
                contextLength = preferences[CONTEXT_LENGTH] ?: DEFAULT_CONTEXT_LENGTH,
                cpuThreads = preferences[CPU_THREADS] ?: defaultCpuThreads,
                gpuLayers = preferences[GPU_LAYERS] ?: DEFAULT_GPU_LAYERS
            )
        }
    }
    
    suspend fun saveTemperature(value: Float) {
        context.settingsDataStore.edit { preferences ->
            preferences[TEMPERATURE] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_temperature", details = "value=$value")
    }
    
    suspend fun saveMaxTokens(value: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[MAX_TOKENS] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_max_tokens", details = "value=$value")
    }
    
    suspend fun saveTopP(value: Float) {
        context.settingsDataStore.edit { preferences ->
            preferences[TOP_P] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_top_p", details = "value=$value")
    }
    
    suspend fun saveTopK(value: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[TOP_K] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_top_k", details = "value=$value")
    }
    
    suspend fun saveRepeatPenalty(value: Float) {
        context.settingsDataStore.edit { preferences ->
            preferences[REPEAT_PENALTY] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_repeat_penalty", details = "value=$value")
    }
    
    suspend fun saveRepeatLastN(value: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[REPEAT_LAST_N] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_repeat_last_n", details = "value=$value")
    }
    
    suspend fun saveMinP(value: Float) {
        context.settingsDataStore.edit { preferences ->
            preferences[MIN_P] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_min_p", details = "value=$value")
    }
    
    suspend fun saveTfsZ(value: Float) {
        context.settingsDataStore.edit { preferences ->
            preferences[TFS_Z] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_tfs_z", details = "value=$value")
    }
    
    suspend fun saveTypicalP(value: Float) {
        context.settingsDataStore.edit { preferences ->
            preferences[TYPICAL_P] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_typical_p", details = "value=$value")
    }
    
    suspend fun saveMirostat(value: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[MIROSTAT] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_mirostat", details = "value=$value")
    }
    
    suspend fun saveMirostatTau(value: Float) {
        context.settingsDataStore.edit { preferences ->
            preferences[MIROSTAT_TAU] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_mirostat_tau", details = "value=$value")
    }
    
    suspend fun saveMirostatEta(value: Float) {
        context.settingsDataStore.edit { preferences ->
            preferences[MIROSTAT_ETA] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_mirostat_eta", details = "value=$value")
    }

    suspend fun saveContextLength(value: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[CONTEXT_LENGTH] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_context_length", details = "value=$value")
    }

    suspend fun saveCpuThreads(value: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[CPU_THREADS] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_cpu_threads", details = "value=$value")
    }

    suspend fun saveGpuLayers(value: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[GPU_LAYERS] = value
        }
        AppEventLogger.debug(component = TAG, action = "save_gpu_layers", details = "value=$value")
    }

    suspend fun saveSystemPrompt(value: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[SYSTEM_PROMPT] = value
        }
        AppEventLogger.info(component = TAG, action = "save_system_prompt", details = "length=${value.length}")
    }

    suspend fun saveTtsVoiceProfile(profile: TtsVoiceProfile) {
        context.settingsDataStore.edit { preferences ->
            preferences[TTS_VOICE_PROFILE] = profile.storageValue
        }
        AppEventLogger.info(component = TAG, action = "save_tts_voice_profile", details = "profile=${profile.storageValue}")
    }
}
