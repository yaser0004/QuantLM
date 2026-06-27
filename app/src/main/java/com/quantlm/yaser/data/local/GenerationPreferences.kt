package com.quantlm.yaser.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.domain.model.ModelProfile
import com.quantlm.yaser.domain.tts.TtsVoiceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        private val STOP_SEQUENCES = stringPreferencesKey("stop_sequences")
        private val ADVANCED_INFERENCE_ENABLED = booleanPreferencesKey("advanced_inference_enabled")
        // Phase 2 (§3.6 / §3.7 / §3.9): persisted capability toggles surfaced by
        // the chat composer's `+` menu. All default to `false` so the user opts
        // in explicitly; existing reads without these keys continue to work.
        private val ENABLE_THINKING = booleanPreferencesKey("enable_thinking")
        private val ENABLE_SPECULATIVE_DECODING = booleanPreferencesKey("enable_speculative_decoding")
        private val ENABLE_AGENT_SKILLS = booleanPreferencesKey("enable_agent_skills")
        // Web Search toggle: when true, every message is answered using freshly
        // scraped web context. Defaults to false — fully offline until opted in.
        private val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")
        // Diagnostics: persist each app session's full log to internal storage.
        // Opt-out (defaults to true): turning it off stops per-session files.
        private val PERSIST_SESSION_LOGS = booleanPreferencesKey("persist_session_logs")
        private val CONTEXT_LENGTH = intPreferencesKey("context_length")
        private val CPU_THREADS = intPreferencesKey("cpu_threads")
        private val GPU_LAYERS = intPreferencesKey("gpu_layers")
        private val HARDWARE_ACCELERATION_MODE = stringPreferencesKey("hardware_acceleration_mode")
        // Opt-in escape hatch: Gemma-4 models are CPU-locked because GPU init can
        // trigger an uncatchable native crash on many devices. When true, that
        // lock is bypassed so the user can try GPU at their own risk.
        private val GEMMA4_GPU_OVERRIDE = booleanPreferencesKey("gemma4_gpu_override")
        private val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        private val TTS_VOICE_GENDER = stringPreferencesKey("tts_voice_gender")
        private val TTS_VOICE_PROFILE = stringPreferencesKey("tts_voice_profile")
        private const val LEGACY_DEFAULT_PROMPT = "You are a helpful AI assistant."
        private const val LEGACY_NO_EMOJI_PROMPT = "You are a helpful AI assistant. Do not use emoji characters in your responses - use text-based emoticons like :) or <3 instead."
        private const val STOP_SEQUENCE_SEPARATOR = "\n"
        
        // Defaults
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_MAX_TOKENS = 2048  // Headroom for long-form answers; history budgeting still trims if context fills.
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
        const val DEFAULT_GPU_LAYERS = 99
        const val DEFAULT_SYSTEM_PROMPT = ""
    }

    enum class HardwareAccelerationMode(val storageValue: String) {
        GPU("gpu"),
        CPU("cpu");

        companion object {
            fun fromStorage(value: String?): HardwareAccelerationMode {
                return entries.firstOrNull { it.storageValue == value } ?: GPU
            }
        }
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
        val stopSequences: List<String> = emptyList(),
        val isAdvancedInferenceEnabled: Boolean = false,
        val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        val ttsVoiceProfile: TtsVoiceProfile = TtsVoiceProfile.CLASSIC_LEGACY,
        // Phase 2 (§3.6): chain-of-thought / `<think>` toggle. Plumbed through
        // [LoadOptions.enableThinking] at load time; runtime detection
        // (`LlamaEngine.nativeChatTemplateSupportsThinking`) gates whether the
        // model honors it.
        val enableThinking: Boolean = false,
        // Phase 2 (§3.7): metadata-only capability toggle today (no runtime path
        // for llama.cpp draft models yet — see Phase 1 §1.4 TODO). Persisted so
        // the UI state survives reloads.
        val enableSpeculativeDecoding: Boolean = false,
        // Phase 2 (§3.9): when true and the active model carries
        // [ModelCapability.AGENT_SKILLS], the system prompt is augmented with
        // the skill manifest at send time.
        val enableAgentSkills: Boolean = false,
        // Web Search: when true, [SendMessageUseCase] fetches and injects web
        // context for every message. When false the app never touches the
        // network for chat. Works with every model (prompt-level augmentation).
        val enableWebSearch: Boolean = false,
        // Diagnostics: when true (default), each app session's full log is saved
        // to internal storage. The user can opt out in Settings.
        val persistSessionLogs: Boolean = true,
    )

    data class HardwareSettings(
        val contextLength: Int = DEFAULT_CONTEXT_LENGTH,
        val cpuThreads: Int = DEFAULT_CPU_THREADS,
        val gpuLayers: Int = DEFAULT_GPU_LAYERS,
        val accelerationMode: HardwareAccelerationMode = HardwareAccelerationMode.GPU,
        // Bypasses the Gemma-4 CPU lock when true. Off by default.
        val gemma4GpuOverride: Boolean = false,
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
            preferences[STOP_SEQUENCES] = encodeStopSequences(settings.stopSequences)
            preferences[ADVANCED_INFERENCE_ENABLED] = settings.isAdvancedInferenceEnabled
            preferences[SYSTEM_PROMPT] = settings.systemPrompt
            preferences[TTS_VOICE_PROFILE] = settings.ttsVoiceProfile.storageValue
            preferences[ENABLE_THINKING] = settings.enableThinking
            preferences[ENABLE_SPECULATIVE_DECODING] = settings.enableSpeculativeDecoding
            preferences[ENABLE_AGENT_SKILLS] = settings.enableAgentSkills
            preferences[ENABLE_WEB_SEARCH] = settings.enableWebSearch
            preferences[PERSIST_SESSION_LOGS] = settings.persistSessionLogs
        }
        AppEventLogger.info(
            component = TAG,
            action = "save_settings",
            details = "temp=${settings.temperature}, maxTokens=${settings.maxTokens}, topP=${settings.topP}, topK=${settings.topK}, stops=${settings.stopSequences.size}, voice=${settings.ttsVoiceProfile.storageValue}"
        )
    }

    suspend fun applyModelProfile(profile: ModelProfile) {
        val current = getSettings().first()
        val updated = current.copy(
            temperature = profile.defaultTemperature,
            repeatPenalty = profile.repetitionPenalty,
            minP = profile.minP,
            topP = profile.topP,
            topK = profile.topK,
            stopSequences = profile.stopTokens
        )

        saveSettings(updated)
        AppEventLogger.info(
            component = TAG,
            action = "apply_model_profile",
            details = "family=${profile.family}, temp=${profile.defaultTemperature}, repPenalty=${profile.repetitionPenalty}, minP=${profile.minP}, topP=${profile.topP}, topK=${profile.topK}, stops=${profile.stopTokens.size}"
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
                stopSequences = decodeStopSequences(preferences[STOP_SEQUENCES]),
                isAdvancedInferenceEnabled = preferences[ADVANCED_INFERENCE_ENABLED] ?: false,
                systemPrompt = resolvedPrompt,
                ttsVoiceProfile = TtsVoiceProfile.fromStorage(
                    preferences[TTS_VOICE_PROFILE],
                    preferences[TTS_VOICE_GENDER]
                ),
                enableThinking = preferences[ENABLE_THINKING] ?: false,
                enableSpeculativeDecoding = preferences[ENABLE_SPECULATIVE_DECODING] ?: false,
                enableAgentSkills = preferences[ENABLE_AGENT_SKILLS] ?: false,
                enableWebSearch = preferences[ENABLE_WEB_SEARCH] ?: false,
                persistSessionLogs = preferences[PERSIST_SESSION_LOGS] ?: true,
            )
        }
    }

    private fun encodeStopSequences(stopSequences: List<String>): String {
        return stopSequences
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(STOP_SEQUENCE_SEPARATOR)
    }

    private fun decodeStopSequences(encoded: String?): List<String> {
        if (encoded.isNullOrBlank()) {
            return emptyList()
        }

        return encoded
            .split(STOP_SEQUENCE_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun getHardwareSettings(defaultCpuThreads: Int = DEFAULT_CPU_THREADS): Flow<HardwareSettings> {
        return context.settingsDataStore.data.map { preferences ->
            val storedMode = preferences[HARDWARE_ACCELERATION_MODE]
            val accelerationMode = HardwareAccelerationMode.fromStorage(storedMode)
            val storedGpuLayers = preferences[GPU_LAYERS]
            val resolvedGpuLayers = when {
                accelerationMode == HardwareAccelerationMode.CPU -> storedGpuLayers ?: 0
                storedGpuLayers == null -> DEFAULT_GPU_LAYERS
                storedGpuLayers == 0 && storedMode == null -> DEFAULT_GPU_LAYERS
                else -> storedGpuLayers
            }

            HardwareSettings(
                contextLength = preferences[CONTEXT_LENGTH] ?: DEFAULT_CONTEXT_LENGTH,
                cpuThreads = preferences[CPU_THREADS] ?: defaultCpuThreads,
                gpuLayers = resolvedGpuLayers,
                accelerationMode = accelerationMode,
                gemma4GpuOverride = preferences[GEMMA4_GPU_OVERRIDE] ?: false,
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

    suspend fun saveAdvancedInferenceEnabled(value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[ADVANCED_INFERENCE_ENABLED] = value
        }
        AppEventLogger.info(component = TAG, action = "save_advanced_inference_enabled", details = "value=$value")
    }

    fun isAdvancedInferenceEnabled(): Flow<Boolean> {
        return context.settingsDataStore.data.map { preferences ->
            preferences[ADVANCED_INFERENCE_ENABLED] ?: false
        }
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

    suspend fun saveHardwareAccelerationMode(mode: HardwareAccelerationMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[HARDWARE_ACCELERATION_MODE] = mode.storageValue
        }
        AppEventLogger.info(
            component = TAG,
            action = "save_hardware_acceleration_mode",
            details = "mode=${mode.storageValue}"
        )
    }

    suspend fun saveGemma4GpuOverride(value: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[GEMMA4_GPU_OVERRIDE] = value
        }
        AppEventLogger.info(
            component = TAG,
            action = "save_gemma4_gpu_override",
            details = "value=$value"
        )
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

    suspend fun saveEnableThinking(value: Boolean) {
        context.settingsDataStore.edit { it[ENABLE_THINKING] = value }
        AppEventLogger.info(component = TAG, action = "save_enable_thinking", details = "value=$value")
    }

    suspend fun saveEnableSpeculativeDecoding(value: Boolean) {
        context.settingsDataStore.edit { it[ENABLE_SPECULATIVE_DECODING] = value }
        AppEventLogger.info(component = TAG, action = "save_enable_speculative_decoding", details = "value=$value")
    }

    suspend fun saveEnableAgentSkills(value: Boolean) {
        context.settingsDataStore.edit { it[ENABLE_AGENT_SKILLS] = value }
        AppEventLogger.info(component = TAG, action = "save_enable_agent_skills", details = "value=$value")
    }

    suspend fun saveEnableWebSearch(value: Boolean) {
        context.settingsDataStore.edit { it[ENABLE_WEB_SEARCH] = value }
        AppEventLogger.info(component = TAG, action = "save_enable_web_search", details = "value=$value")
    }

    suspend fun savePersistSessionLogs(value: Boolean) {
        context.settingsDataStore.edit { it[PERSIST_SESSION_LOGS] = value }
        AppEventLogger.info(component = TAG, action = "save_persist_session_logs", details = "value=$value")
    }
}
