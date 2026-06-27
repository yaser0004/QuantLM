package com.quantlm.yaser.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.inference.ModelManager
import com.quantlm.yaser.data.local.GenerationPreferences
import com.quantlm.yaser.domain.model.ModelConfig
import com.quantlm.yaser.domain.model.ModelLoadingState
import com.quantlm.yaser.domain.inference.SamplingCapabilities
import com.quantlm.yaser.domain.inference.SamplingParam
import com.quantlm.yaser.domain.model.ModelProfileRegistry
import com.quantlm.yaser.domain.repository.ChatRepository
import com.quantlm.yaser.domain.repository.InferenceRepository
import com.quantlm.yaser.domain.repository.ModelRepository
import com.quantlm.yaser.domain.tts.TtsVoiceProfile
import com.quantlm.yaser.domain.usecase.LoadModelUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val chatRepository: ChatRepository,
    private val loadModelUseCase: LoadModelUseCase,
    private val generationPreferences: GenerationPreferences,
    private val modelManager: ModelManager,
    private val inferenceRepository: InferenceRepository
) : ViewModel() {

    private companion object {
        const val TAG = "SettingsViewModel"
    }

    private val detectedCpuThreads = detectCpuThreads()
    
    val loadedModel = modelRepository.getLoadedModel()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * Sampling parameters the active engine honors — recomputed whenever the
     * loaded model changes. Drives control enablement in the Settings UI.
     */
    val samplingCapabilities: StateFlow<Set<SamplingParam>> = loadedModel
        .map { inferenceRepository.getActiveSamplingCapabilities() }
        .stateIn(viewModelScope, SharingStarted.Lazily, SamplingCapabilities.FULL)
    
    /** Loading state exposed from the repository for UI indicators */
    val modelLoadingState: StateFlow<ModelLoadingState> = modelRepository.loadingState
    
    // Use preferences for generation settings
    private val _generationSettings = MutableStateFlow(GenerationPreferences.GenerationSettings())
    val temperature = _generationSettings.map { it.temperature }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.7f)
    
    val maxTokens = _generationSettings.map { it.maxTokens }
        .stateIn(viewModelScope, SharingStarted.Lazily, 512)
    
    val topP = _generationSettings.map { it.topP }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.9f)
    
    val topK = _generationSettings.map { it.topK }
        .stateIn(viewModelScope, SharingStarted.Lazily, 40)
    
    val repeatPenalty = _generationSettings.map { it.repeatPenalty }
        .stateIn(viewModelScope, SharingStarted.Lazily, 1.1f)
    
    val repeatLastN = _generationSettings.map { it.repeatLastN }
        .stateIn(viewModelScope, SharingStarted.Lazily, 64)
    
    val minP = _generationSettings.map { it.minP }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.05f)
    
    val tfsZ = _generationSettings.map { it.tfsZ }
        .stateIn(viewModelScope, SharingStarted.Lazily, 1.0f)
    
    val typicalP = _generationSettings.map { it.typicalP }
        .stateIn(viewModelScope, SharingStarted.Lazily, 1.0f)
    
    val mirostat = _generationSettings.map { it.mirostat }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)
    
    val mirostatTau = _generationSettings.map { it.mirostatTau }
        .stateIn(viewModelScope, SharingStarted.Lazily, 5.0f)
    
    val mirostatEta = _generationSettings.map { it.mirostatEta }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.1f)

    val isAdvancedInferenceEnabled = _generationSettings.map { it.isAdvancedInferenceEnabled }
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // Diagnostics: save each session's full log to internal storage (opt-out).
    val persistSessionLogs = _generationSettings.map { it.persistSessionLogs }
        .stateIn(viewModelScope, SharingStarted.Lazily, true)

    val ttsVoiceProfile = _generationSettings.map { it.ttsVoiceProfile }
        .stateIn(viewModelScope, SharingStarted.Lazily, TtsVoiceProfile.CLASSIC_LEGACY)

    private val _systemPrompt = MutableStateFlow(GenerationPreferences.DEFAULT_SYSTEM_PROMPT)
    val systemPrompt = _systemPrompt.asStateFlow()

    private val _contextLength = MutableStateFlow(GenerationPreferences.DEFAULT_CONTEXT_LENGTH)
    val contextLength = _contextLength.asStateFlow()
    
    private val _cpuThreads = MutableStateFlow(detectedCpuThreads)
    val cpuThreads = _cpuThreads.asStateFlow()
    
    private val _gpuLayers = MutableStateFlow(GenerationPreferences.DEFAULT_GPU_LAYERS)
    val gpuLayers = _gpuLayers.asStateFlow()

    private val _hardwareAccelerationMode = MutableStateFlow(GenerationPreferences.HardwareAccelerationMode.GPU)
    val hardwareAccelerationMode = _hardwareAccelerationMode.asStateFlow()

    private val _gemma4GpuOverride = MutableStateFlow(false)
    val gemma4GpuOverride = _gemma4GpuOverride.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init {
        // Load settings from preferences
        viewModelScope.launch {
            generationPreferences.getSettings().collect { settings ->
                _generationSettings.value = settings
                if (_systemPrompt.value != settings.systemPrompt) {
                    _systemPrompt.value = settings.systemPrompt
                }
            }
        }

        viewModelScope.launch {
            generationPreferences.getHardwareSettings(defaultCpuThreads = detectedCpuThreads).collect { hardware ->
                _contextLength.value = hardware.contextLength.coerceIn(512, 8192)
                _cpuThreads.value = hardware.cpuThreads.coerceIn(1, 16)
                _gpuLayers.value = hardware.gpuLayers.coerceIn(0, 100)
                _hardwareAccelerationMode.value = hardware.accelerationMode
                _gemma4GpuOverride.value = hardware.gemma4GpuOverride
            }
        }
    }

    // Fix [2.5]: cancel superseded load so a new selection wins over an in-flight init
    private var loadModelJob: Job? = null
    
    fun setTemperature(value: Float) {
        val coerced = value.coerceIn(0f, 1.5f)
        viewModelScope.launch {
            generationPreferences.saveTemperature(coerced)
        }
    }

    fun resetTemperature() {
        setTemperature(GenerationPreferences.DEFAULT_TEMPERATURE)
    }
    
    fun setMaxTokens(value: Int) {
        val coerced = value.coerceIn(64, 1024)
        viewModelScope.launch {
            generationPreferences.saveMaxTokens(coerced)
        }
    }

    fun resetMaxTokens() {
        setMaxTokens(GenerationPreferences.DEFAULT_MAX_TOKENS)
    }
    
    fun setTopP(value: Float) {
        val coerced = value.coerceIn(0.1f, 1f)
        viewModelScope.launch {
            generationPreferences.saveTopP(coerced)
        }
    }

    fun resetTopP() {
        setTopP(GenerationPreferences.DEFAULT_TOP_P)
    }
    
    fun setTopK(value: Int) {
        val coerced = value.coerceIn(1, 100)
        viewModelScope.launch {
            generationPreferences.saveTopK(coerced)
        }
    }

    fun resetTopK() {
        setTopK(GenerationPreferences.DEFAULT_TOP_K)
    }
    
    fun setRepeatPenalty(value: Float) {
        val coerced = value.coerceIn(1.0f, 1.3f)
        viewModelScope.launch {
            generationPreferences.saveRepeatPenalty(coerced)
        }
    }

    fun resetRepeatPenalty() {
        setRepeatPenalty(GenerationPreferences.DEFAULT_REPEAT_PENALTY)
    }
    
    fun setRepeatLastN(value: Int) {
        val coerced = value.coerceIn(0, 256)
        viewModelScope.launch {
            generationPreferences.saveRepeatLastN(coerced)
        }
    }

    fun resetRepeatLastN() {
        setRepeatLastN(GenerationPreferences.DEFAULT_REPEAT_LAST_N)
    }
    
    fun setMinP(value: Float) {
        val coerced = value.coerceIn(0.0f, 0.2f)
        viewModelScope.launch {
            generationPreferences.saveMinP(coerced)
        }
    }

    fun resetMinP() {
        setMinP(GenerationPreferences.DEFAULT_MIN_P)
    }
    
    fun setTfsZ(value: Float) {
        val coerced = value.coerceIn(0.0f, 1.0f)
        viewModelScope.launch {
            generationPreferences.saveTfsZ(coerced)
        }
    }

    fun resetTfsZ() {
        setTfsZ(GenerationPreferences.DEFAULT_TFS_Z)
    }
    
    fun setTypicalP(value: Float) {
        val coerced = value.coerceIn(0.0f, 1.0f)
        viewModelScope.launch {
            generationPreferences.saveTypicalP(coerced)
        }
    }

    fun resetTypicalP() {
        setTypicalP(GenerationPreferences.DEFAULT_TYPICAL_P)
    }
    
    fun setMirostat(value: Int) {
        val coerced = value.coerceIn(0, 2)
        viewModelScope.launch {
            generationPreferences.saveMirostat(coerced)
        }
    }

    fun resetMirostat() {
        setMirostat(GenerationPreferences.DEFAULT_MIROSTAT)
    }
    
    fun setMirostatTau(value: Float) {
        val coerced = value.coerceIn(0.0f, 10.0f)
        viewModelScope.launch {
            generationPreferences.saveMirostatTau(coerced)
        }
    }

    fun resetMirostatTau() {
        setMirostatTau(GenerationPreferences.DEFAULT_MIROSTAT_TAU)
    }
    
    fun setMirostatEta(value: Float) {
        val coerced = value.coerceIn(0.001f, 1.0f)
        viewModelScope.launch {
            generationPreferences.saveMirostatEta(coerced)
        }
    }

    fun resetMirostatEta() {
        setMirostatEta(GenerationPreferences.DEFAULT_MIROSTAT_ETA)
    }

    fun setAdvancedInferenceEnabled(enabled: Boolean) {
        AppEventLogger.info(component = TAG, action = "set_advanced_inference", details = "enabled=$enabled")
        viewModelScope.launch {
            generationPreferences.saveAdvancedInferenceEnabled(enabled)
        }
    }

    fun setPersistSessionLogs(enabled: Boolean) {
        AppEventLogger.info(component = TAG, action = "set_persist_session_logs", details = "enabled=$enabled")
        viewModelScope.launch {
            generationPreferences.savePersistSessionLogs(enabled)
        }
    }

    fun resetToModelDefaults() {
        AppEventLogger.info(component = TAG, action = "reset_to_model_defaults_requested")
        viewModelScope.launch {
            val model = loadedModel.value
            if (model == null) {
                _message.value = "Load a model first to reset defaults"
                return@launch
            }

            val loadedFileName = File(model.filePath).name
            val modelId = modelManager.resolveModelIdForLoadedFileName(loadedFileName)
                ?: model.name
            val profile = ModelProfileRegistry.getProfileForModel(modelId)

            generationPreferences.saveTemperature(profile.defaultTemperature)
            generationPreferences.saveTopP(profile.topP)
            generationPreferences.saveMinP(profile.minP)
            generationPreferences.saveRepeatPenalty(profile.repetitionPenalty)

            _generationSettings.value = _generationSettings.value.copy(
                temperature = profile.defaultTemperature,
                topP = profile.topP,
                minP = profile.minP,
                repeatPenalty = profile.repetitionPenalty
            )

            _message.value = "Inference controls reset to ${profile.family} defaults"
        }
    }
    
    fun setContextLength(value: Int) {
        val coerced = value.coerceIn(512, 8192)
        _contextLength.value = coerced
        viewModelScope.launch {
            generationPreferences.saveContextLength(coerced)
        }
    }

    fun resetContextLength() {
        setContextLength(GenerationPreferences.DEFAULT_CONTEXT_LENGTH)
    }
    
    fun setCpuThreads(value: Int) {
        val coerced = value.coerceIn(1, 16)
        _cpuThreads.value = coerced
        viewModelScope.launch {
            generationPreferences.saveCpuThreads(coerced)
        }
    }

    fun resetCpuThreads() {
        setCpuThreads(detectedCpuThreads)
    }
    
    fun setGpuLayers(value: Int) {
        val minLayers = if (_hardwareAccelerationMode.value == GenerationPreferences.HardwareAccelerationMode.GPU) 1 else 0
        val coerced = value.coerceIn(minLayers, 100)
        _gpuLayers.value = coerced
        viewModelScope.launch {
            generationPreferences.saveGpuLayers(coerced)
        }
    }

    fun resetGpuLayers() {
        setGpuLayers(GenerationPreferences.DEFAULT_GPU_LAYERS)
    }

    fun setHardwareAccelerationMode(mode: GenerationPreferences.HardwareAccelerationMode) {
        AppEventLogger.info(component = TAG, action = "set_hardware_accel_mode", details = "mode=${mode.storageValue}")
        _hardwareAccelerationMode.value = mode
        viewModelScope.launch {
            generationPreferences.saveHardwareAccelerationMode(mode)
            if (mode == GenerationPreferences.HardwareAccelerationMode.GPU && _gpuLayers.value <= 0) {
                val defaultLayers = GenerationPreferences.DEFAULT_GPU_LAYERS.coerceIn(1, 100)
                _gpuLayers.value = defaultLayers
                generationPreferences.saveGpuLayers(defaultLayers)
            }
        }
    }

    fun setGemma4GpuOverride(enabled: Boolean) {
        AppEventLogger.warn(component = TAG, action = "set_gemma4_gpu_override", details = "enabled=$enabled")
        _gemma4GpuOverride.value = enabled
        viewModelScope.launch {
            generationPreferences.saveGemma4GpuOverride(enabled)
        }
    }

    private fun detectCpuThreads(): Int {
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        if (available <= 4) return available

        val reservedForSystem = (available * 0.25f).roundToInt().coerceAtLeast(1)
        val target = available - reservedForSystem
        return target.coerceIn(4, 8)
    }
    
    fun setSystemPrompt(value: String) {
        _systemPrompt.value = value
    }

    fun resetSystemPrompt() {
        _systemPrompt.value = GenerationPreferences.DEFAULT_SYSTEM_PROMPT
        saveSystemPrompt()
    }

    fun saveSystemPrompt() {
        AppEventLogger.info(component = TAG, action = "save_system_prompt", details = "length=${_systemPrompt.value.length}")
        viewModelScope.launch {
            val sanitized = _systemPrompt.value.trim()
            generationPreferences.saveSystemPrompt(sanitized)
            _generationSettings.value = _generationSettings.value.copy(systemPrompt = sanitized)
            _systemPrompt.value = sanitized
            _message.value = if (sanitized.isEmpty()) {
                "System prompt cleared"
            } else {
                "System prompt saved"
            }
        }
    }

    fun setTtsVoiceProfile(profile: TtsVoiceProfile) {
        AppEventLogger.info(component = TAG, action = "set_tts_voice", details = "profile=${profile.storageValue}")
        viewModelScope.launch {
            generationPreferences.saveTtsVoiceProfile(profile)
        }
    }

    fun loadModel(
        modelPath: String,
        modelName: String,
        modelSize: Long,
        isVisionModel: Boolean = false,
        mmprojPath: String? = null
    ) {
        AppEventLogger.info(
            component = TAG,
            action = "load_model_requested",
            details = "name=$modelName, sizeMb=${modelSize / (1024 * 1024)}, vision=$isVisionModel, hasMmproj=${mmprojPath != null}"
        )
        loadModelJob?.cancel()
        loadModelJob = viewModelScope.launch {
            _isLoading.value = true

            val persistedHardware = generationPreferences
                .getHardwareSettings(defaultCpuThreads = detectedCpuThreads)
                .first()
            val effectiveContextLength = persistedHardware.contextLength.coerceIn(512, 8192)
            val effectiveCpuThreads = persistedHardware.cpuThreads.coerceIn(1, 16)
            val effectiveGpuLayers = when (persistedHardware.accelerationMode) {
                GenerationPreferences.HardwareAccelerationMode.CPU -> 0
                GenerationPreferences.HardwareAccelerationMode.GPU -> persistedHardware.gpuLayers.coerceIn(1, 100)
            }

            _contextLength.value = effectiveContextLength
            _cpuThreads.value = effectiveCpuThreads
            _gpuLayers.value = effectiveGpuLayers
            _hardwareAccelerationMode.value = persistedHardware.accelerationMode
            
            val currentSettings = _generationSettings.value
            val config = ModelConfig(
                name = modelName,
                filePath = modelPath,
                size = modelSize,
                contextLength = effectiveContextLength,
                temperature = currentSettings.temperature,
                maxTokens = currentSettings.maxTokens,
                nThreads = effectiveCpuThreads,
                nGpuLayers = effectiveGpuLayers,
                systemPrompt = _systemPrompt.value,
                isVisionModel = isVisionModel,
                mmprojPath = mmprojPath
            )
            
            loadModelUseCase(config)
                .onSuccess {
                    val visionSuffix = if (isVisionModel) " (Vision enabled)" else ""
                    _message.value = "Model loaded successfully$visionSuffix"
                }
                .onFailure {
                    _message.value = "Failed to load model: ${it.message}"
                }
            
            _isLoading.value = false
        }
    }
    
    fun unloadModel() {
        AppEventLogger.info(component = TAG, action = "unload_model_requested")
        viewModelScope.launch {
            modelRepository.unloadModel()
            _message.value = "Model unloaded"
        }
    }

    fun clearChatHistory() {
        AppEventLogger.warn(component = TAG, action = "clear_chat_history_requested")
        viewModelScope.launch {
            chatRepository.clearAllHistory()
            _message.value = "Chat history cleared"
        }
    }
    
    fun clearMessage() {
        _message.value = null
    }
    
    fun applyPreset(preset: String) {
        AppEventLogger.info(component = TAG, action = "apply_preset", details = "preset=$preset")
        viewModelScope.launch {
            val currentSettings = _generationSettings.value
            val newSettings = when (preset) {
                "precise" -> currentSettings.copy(
                    temperature = 0.2f,
                    maxTokens = 256,
                    topP = 0.7f,
                    topK = 20,
                    repeatPenalty = 1.15f,
                    repeatLastN = 64,
                    minP = 0.05f,
                    tfsZ = 1.0f,
                    typicalP = 1.0f,
                    mirostat = 0,
                    mirostatTau = 5.0f,
                    mirostatEta = 0.1f,
                    systemPrompt = _systemPrompt.value
                )
                "balanced" -> currentSettings.copy(
                    temperature = 0.7f,
                    maxTokens = 512,
                    topP = 0.9f,
                    topK = 40,
                    repeatPenalty = 1.1f,
                    repeatLastN = 64,
                    minP = 0.05f,
                    tfsZ = 1.0f,
                    typicalP = 1.0f,
                    mirostat = 0,
                    mirostatTau = 5.0f,
                    mirostatEta = 0.1f,
                    systemPrompt = _systemPrompt.value
                )
                "creative" -> currentSettings.copy(
                    temperature = 1.2f,
                    maxTokens = 512,
                    topP = 0.95f,
                    topK = 60,
                    repeatPenalty = 1.05f,
                    repeatLastN = 128,
                    minP = 0.0f,
                    tfsZ = 1.0f,
                    typicalP = 1.0f,
                    mirostat = 0,
                    mirostatTau = 5.0f,
                    mirostatEta = 0.1f,
                    systemPrompt = _systemPrompt.value
                )
                "focused" -> currentSettings.copy(
                    temperature = 0.7f,
                    maxTokens = 256,
                    topP = 0.9f,
                    topK = 40,
                    repeatPenalty = 1.1f,
                    repeatLastN = 64,
                    minP = 0.05f,
                    tfsZ = 1.0f,
                    typicalP = 1.0f,
                    mirostat = 2,
                    mirostatTau = 5.0f,
                    mirostatEta = 0.1f,
                    systemPrompt = _systemPrompt.value
                )
                else -> _generationSettings.value
            }
            generationPreferences.saveSettings(newSettings)
            _message.value = "Applied $preset preset"
        }
    }
}
