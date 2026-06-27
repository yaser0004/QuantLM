package com.quantlm.yaser.presentation.chat

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantlm.yaser.data.audio.AudioInputManager
import com.quantlm.yaser.data.audio.AudioInputState
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.service.InferenceService
import com.quantlm.yaser.domain.model.AvailableModels
import com.quantlm.yaser.domain.model.DownloadableModel
import com.quantlm.yaser.domain.model.isVisionModel
import com.quantlm.yaser.domain.model.GenerationState
import com.quantlm.yaser.domain.model.Message
import com.quantlm.yaser.domain.model.ModelCapability
import com.quantlm.yaser.domain.model.ModelConfig
import com.quantlm.yaser.domain.model.PromptTemplate
import com.quantlm.yaser.domain.model.PromptTemplates
import com.quantlm.yaser.domain.model.ToolCall
import com.quantlm.yaser.domain.model.ToolCallParser
import com.quantlm.yaser.domain.model.ToolExecutor
import com.quantlm.yaser.domain.model.ToolResult
import com.quantlm.yaser.domain.model.MobileTools
import com.quantlm.yaser.domain.model.VisionQuickAction
import com.quantlm.yaser.domain.repository.ChatRepository
import com.quantlm.yaser.domain.repository.InferenceRepository
import com.quantlm.yaser.domain.repository.ModelRepository
import com.quantlm.yaser.domain.usecase.SendMessageUseCase
import com.quantlm.yaser.domain.util.ChatNameGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import javax.inject.Inject

/**
 * Represents the state of the vision model availability
 */
sealed class VisionModelState {
    /** Vision model is loaded and ready */
    object Ready : VisionModelState()
    /** Vision model(s) downloaded but not loaded - contains list of all downloaded vision models */
    data class Downloaded(val model: DownloadableModel, val allDownloaded: List<DownloadableModel> = listOf(model)) : VisionModelState()
    /** No vision model downloaded - contains list of all available vision models for download */
    data class NotDownloaded(val model: DownloadableModel, val availableModels: List<DownloadableModel> = emptyList()) : VisionModelState()
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val modelRepository: ModelRepository,
    private val inferenceRepository: InferenceRepository,
    private val sendMessageUseCase: SendMessageUseCase,
    private val toolExecutor: ToolExecutor,
    private val generationPreferences: com.quantlm.yaser.data.local.GenerationPreferences,
    private val application: Application,
    val audioInputManager: AudioInputManager,
    private val skillRepository: com.quantlm.yaser.data.skills.SkillRepository,
) : ViewModel() {
    
    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId = _currentConversationId.asStateFlow()

    // One-shot UI notifications (model switch events, capability changes, etc.)
    private val _snackbarMessages = Channel<String>(capacity = Channel.BUFFERED)
    val snackbarMessages = _snackbarMessages.receiveAsFlow()
    
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages = _messages.asStateFlow()
    
    private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState = _generationState.asStateFlow()

    // Persists the thinking content and summary across the Thinking → Generating state transition
    // so the UI can display both alongside the streaming response bubble simultaneously.
    private val _liveThinkingContent = MutableStateFlow<String?>(null)
    val liveThinkingContent = _liveThinkingContent.asStateFlow()

    private val _liveThoughtSummary = MutableStateFlow<String?>(null)
    val liveThoughtSummary = _liveThoughtSummary.asStateFlow()
    
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()
    
    // Selected images for vision models (supports multiple)
    private val _selectedImages = MutableStateFlow<List<String>>(emptyList())
    val selectedImages = _selectedImages.asStateFlow()
    
    // Backward compatibility - get first image path
    val selectedImagePath = _selectedImages.map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    // Selected audio attachments for audio-capable models (audio scribe)
    private val _selectedAudio = MutableStateFlow<List<String>>(emptyList())
    val selectedAudio = _selectedAudio.asStateFlow()
    
    // Vision quick action prompts
    private val _activeQuickAction = MutableStateFlow<VisionQuickAction?>(null)
    val activeQuickAction = _activeQuickAction.asStateFlow()
    
    // Prompt template selection
    private val _selectedTemplate = MutableStateFlow<PromptTemplate?>(null)
    val selectedTemplate = _selectedTemplate.asStateFlow()
    
    // Tool calling state
    private val _isToolCallingEnabled = MutableStateFlow(true)
    val isToolCallingEnabled = _isToolCallingEnabled.asStateFlow()
    
    private val _pendingToolCall = MutableStateFlow<ToolCall?>(null)
    val pendingToolCall = _pendingToolCall.asStateFlow()
    
    private val _toolResult = MutableStateFlow<ToolResult?>(null)
    val toolResult = _toolResult.asStateFlow()
    
    val loadedModel = modelRepository.getLoadedModel()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    // Vision support state - derived from loaded model
    private val _isVisionSupported = MutableStateFlow(false)
    val isVisionSupportedFlow = _isVisionSupported.asStateFlow()
    
    // Vision model availability state
    private val _visionModelState = MutableStateFlow<VisionModelState>(
        VisionModelState.NotDownloaded(AvailableModels.QWEN2_5_VL_3B_Q8_0)
    )
    val visionModelState = _visionModelState.asStateFlow()
    
    // Loading state for model switching
    private val _isLoadingVisionModel = MutableStateFlow(false)
    val isLoadingVisionModel = _isLoadingVisionModel.asStateFlow()

    // Phase 2 (§3.5): generalized capability-aware switching busy flag.
    // Mirrors `_isLoadingVisionModel` so existing UI keeps working during the
    // transition while new flows (audio/reasoning/spec-dec/agent-skills)
    // hang off the unified flag.
    private val _isLoadingCapabilityModel = MutableStateFlow(false)
    val isLoadingCapabilityModel = _isLoadingCapabilityModel.asStateFlow()

    // Phase 2 (§3.5): per-capability chooser state. Populated for the toggle
    // surface in the chat composer's `+` menu. Keys are the capabilities the
    // user can act on; values track downloaded/available models for each.
    private val capabilityKeys = listOf(
        ModelCapability.VISION,
        ModelCapability.AUDIO,
        ModelCapability.REASONING,
        ModelCapability.SPECULATIVE_DECODING,
        ModelCapability.AGENT_SKILLS,
    )
    private val _capabilityModelStates: Map<ModelCapability, MutableStateFlow<CapabilityModelState>> =
        capabilityKeys.associateWith {
            MutableStateFlow<CapabilityModelState>(
                CapabilityModelState.NotDownloaded(it, AvailableModels.SMOLVLM_1_8B_Q8_0, emptyList())
            )
        }
    val capabilityModelStates: Map<ModelCapability, StateFlow<CapabilityModelState>> =
        _capabilityModelStates.mapValues { (_, flow) -> flow.asStateFlow() }

    // Active model's runtime capabilities — set whenever the loaded model changes.
    private val _loadedModelCapabilities = MutableStateFlow<Set<ModelCapability>>(emptySet())
    val loadedModelCapabilities = _loadedModelCapabilities.asStateFlow()

    // Phase 2 (§3.9): per-skill state lives in [SkillRepository]; the
    // composer's chip strip filters down to enabled names.
    val agentSkills = skillRepository.skills
    val enabledSkillNames = agentSkills
        .map { list -> list.filter { it.enabled }.map { it.name } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    private val _conversations = MutableStateFlow<List<com.quantlm.yaser.domain.model.Conversation>>(emptyList())
    val conversations = _conversations.asStateFlow()

    val lastMessagePreviews = chatRepository.observeLastMessagesPerConversation()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var messagesJob: Job? = null

    // The in-flight send/regenerate coroutine. Edit-and-resend and
    // regenerate cancel it (and wait) before mutating message history, so a
    // still-streaming old turn can't persist its response after the rows it
    // belongs to were deleted.
    private var generationJob: Job? = null

    /**
     * True for every state in which a generation request is in flight —
     * including the pre-token phases (web search, thinking, media analysis)
     * that can last a long time. Starting a second generation in any of these
     * states would fail at the engine's in-flight guard and, worse, its error
     * path would stop the foreground service out from under the first one.
     */
    private fun isGenerationInFlight(state: GenerationState): Boolean =
        state is GenerationState.Generating ||
            state is GenerationState.Loading ||
            state is GenerationState.SearchingWeb ||
            state is GenerationState.Thinking ||
            state is GenerationState.AnalyzingImage ||
            state is GenerationState.TranscribingAudio ||
            state is GenerationState.PreparingReasoning


    // Generation parameters - loaded from preferences
    private val _generationSettings = MutableStateFlow(
        com.quantlm.yaser.data.local.GenerationPreferences.GenerationSettings()
    )
    val generationSettings = _generationSettings.asStateFlow()
    
    init {
        // Load saved settings
        viewModelScope.launch {
            generationPreferences.getSettings().collect { settings ->
                _generationSettings.value = settings
            }
        }
        
        // Observe loaded model changes to update vision support state and manage
        // context boundaries when the user switches models mid-conversation.
        viewModelScope.launch {
            var previousModelName: String? = null
            loadedModel.collect { model ->
                val newModelName = model?.name

                // Update vision support when model changes
                _isVisionSupported.value = model?.isVisionModel == true &&
                    inferenceRepository.isVisionSupported()
                Log.d(TAG, "Model changed: $newModelName, vision supported: ${_isVisionSupported.value}")
                AppEventLogger.info(
                    component = TAG,
                    action = "loaded_model_changed",
                    details = "model=${newModelName ?: "none"}, visionSupported=${_isVisionSupported.value}"
                )

                // If a DIFFERENT model is now loaded (not first load, not unload),
                // reset the inference engine's context and insert a visual marker
                // into the message history so the new model starts fresh.
                if (newModelName != null && previousModelName != null && newModelName != previousModelName) {
                    val conversationId = _currentConversationId.value
                    if (conversationId != null && _messages.value.isNotEmpty()) {
                        inferenceRepository.resetConversation()
                        chatRepository.insertModelChangeMarker(conversationId, newModelName)
                        _snackbarMessages.trySend(
                            "Context cleared — $newModelName starts fresh from this point"
                        )
                        AppEventLogger.info(
                            component = TAG,
                            action = "model_switch_context_reset",
                            details = "from=$previousModelName, to=$newModelName, conversationId=$conversationId"
                        )
                    }
                }
                previousModelName = newModelName

                // Update vision model state
                updateVisionModelState()

                // Phase 2 (§3.5): refresh the generalized capability map.
                // Use getActiveCapabilities() so runtime detection (e.g.
                // nativeChatTemplateSupportsThinking) gates the REASONING flag
                // rather than trusting the static manifest alone.
                val newCaps = if (model != null) {
                    inferenceRepository.getActiveCapabilities()
                } else {
                    emptySet()
                }
                _loadedModelCapabilities.value = newCaps
                updateCapabilityModelStates()

                // Auto-disable capability toggles that the new model doesn't support,
                // and notify the user so they aren't confused by silent changes.
                if (model != null) {
                    autoDisableStaleCapabilityToggles(newModelName ?: "", newCaps)
                }
            }
        }

        loadAllConversations()
        updateVisionModelState()
        updateCapabilityModelStates()

        viewModelScope.launch {
            initializeConversation()
        }
    }
    
    /**
     * Check if any vision model is downloaded and update state accordingly.
     * Scans all available vision models, not just Qwen2.5.
     */
    private fun updateVisionModelState() {
        val modelsDir = File(application.filesDir, "models")
        
        // Get all vision-capable models from the available models list
        val allVisionModels = AvailableModels.getAllModels().filter { it.isVisionModel }
        
        // Check which vision models are fully downloaded (model + mmproj)
        val downloadedVisionModels = allVisionModels.filter { visionModel ->
            val modelFile = File(modelsDir, visionModel.fileName)
            val mmprojFile = visionModel.mmprojFileName?.let { File(modelsDir, it) }
            val requiresMmproj = visionModel.mmprojFileName != null

            val isModelDownloaded = modelFile.exists() &&
                fileSizeOk(modelsDir, visionModel.fileName, modelFile.length(), visionModel.fileSize)

            // Native multimodal models (for example Gemma 3n LiteRT-LM) do not require mmproj.
            val isMmprojDownloaded = if (!requiresMmproj) {
                true
            } else {
                mmprojFile != null && mmprojFile.exists() &&
                    fileSizeOk(modelsDir, visionModel.mmprojFileName!!, mmprojFile.length(), visionModel.mmprojFileSize)
            }

            isModelDownloaded && isMmprojDownloaded
        }
        
        // Default vision model to suggest if none downloaded (smallest one first)
        val defaultVisionModel = allVisionModels.minByOrNull { it.fileSize } ?: AvailableModels.SMOLVLM_1_8B_Q8_0
        
        _visionModelState.value = when {
            _isVisionSupported.value -> VisionModelState.Ready
            downloadedVisionModels.isNotEmpty() -> VisionModelState.Downloaded(
                model = downloadedVisionModels.first(),
                allDownloaded = downloadedVisionModels
            )
            else -> VisionModelState.NotDownloaded(
                model = defaultVisionModel,
                availableModels = allVisionModels
            )
        }

        val stateSummary = when (val state = _visionModelState.value) {
            VisionModelState.Ready -> "Ready"
            is VisionModelState.Downloaded -> "Downloaded(primary=${state.model.id}, count=${state.allDownloaded.size})"
            is VisionModelState.NotDownloaded -> "NotDownloaded(default=${state.model.id}, available=${state.availableModels.size})"
        }

        Log.d(TAG, "Vision model state updated: $stateSummary")
        AppEventLogger.debug(
            component = TAG,
            action = "vision_model_state_updated",
            details = "state=$stateSummary"
        )
    }
    
    /**
     * Phase 2 (§3.5): compute [CapabilityModelState] for every capability the
     * chat composer's `+` menu can act on. Uses the same on-disk existence +
     * size-tolerance check the vision flow uses today; mmproj is verified when
     * a manifest entry declares one.
     */
    private fun updateCapabilityModelStates() {
        val modelsDir = File(application.filesDir, "models")
        val activeCaps = _loadedModelCapabilities.value
        val allManifest = AvailableModels.getAllModels()

        capabilityKeys.forEach { capability ->
            val candidates = allManifest.filter { capability in it.capabilities }
            val downloaded = candidates.filter { it.isFullyDownloaded(modelsDir) }
            val newState: CapabilityModelState = when {
                capability in activeCaps -> CapabilityModelState.Ready
                downloaded.isNotEmpty() -> CapabilityModelState.Downloaded(
                    capability = capability,
                    preferred = downloaded.minByOrNull { it.fileSize } ?: downloaded.first(),
                    allDownloaded = downloaded,
                )
                else -> {
                    val recommended = candidates.minByOrNull { it.fileSize }
                    if (recommended != null) {
                        CapabilityModelState.NotDownloaded(
                            capability = capability,
                            recommended = recommended,
                            available = candidates,
                        )
                    } else {
                        // No manifest entry advertises this capability. Keep a
                        // sane placeholder so the chooser can render an empty
                        // state instead of crashing.
                        CapabilityModelState.NotDownloaded(
                            capability = capability,
                            recommended = AvailableModels.SMOLVLM_1_8B_Q8_0,
                            available = emptyList(),
                        )
                    }
                }
            }
            _capabilityModelStates[capability]?.value = newState
        }
    }

    private fun DownloadableModel.isFullyDownloaded(modelsDir: File): Boolean {
        val modelFile = File(modelsDir, fileName)
        if (!modelFile.exists()) return false
        if (!fileSizeOk(modelsDir, fileName, modelFile.length(), fileSize)) return false
        val mmprojName = mmprojFileName ?: return true
        val mmprojFile = File(modelsDir, mmprojName)
        if (!mmprojFile.exists()) return false
        return fileSizeOk(modelsDir, mmprojName, mmprojFile.length(), mmprojFileSize)
    }

    // Mirrors ModelDownloadViewModel: prefer the .size sidecar written on successful download,
    // then fall back to 10% tolerance against the manifest value, then just check non-empty.
    private fun fileSizeOk(modelsDir: File, fileName: String, actual: Long, manifest: Long): Boolean {
        val stored = try {
            File(modelsDir, "$fileName.size").takeIf { it.exists() }
                ?.readText()?.trim()?.toLongOrNull() ?: -1L
        } catch (_: Exception) { -1L }
        val reference = if (stored > 0) stored else manifest
        return when {
            reference > 0 -> kotlin.math.abs(actual - reference).toFloat() / reference.toFloat() < 0.10f
            else -> actual > 1_000_000L
        }
    }

    /**
     * Phase 2 (§3.5): generalized model switch for any capability. Reuses
     * `modelRepository.loadModel(...)` exactly like [switchToVisionModel].
     */
    fun switchToModelForCapability(
        capability: ModelCapability,
        model: DownloadableModel,
        onComplete: (Boolean) -> Unit = {},
    ) {
        // Vision keeps the bespoke loading flag for back-compat with existing UI
        // that watches `_isLoadingVisionModel`; everything else uses the
        // generalized flag. Both are set so legacy collectors don't miss a beat.
        viewModelScope.launch {
            _isLoadingCapabilityModel.value = true
            if (capability == ModelCapability.VISION) _isLoadingVisionModel.value = true
            AppEventLogger.info(
                component = TAG,
                action = "switch_capability_model_requested",
                details = "capability=$capability, modelId=${model.id}"
            )
            try {
                val modelsDir = File(application.filesDir, "models")
                val modelPath = File(modelsDir, model.fileName).absolutePath
                val mmprojPath = model.mmprojFileName?.let { File(modelsDir, it).absolutePath }

                if (!File(modelPath).exists()) {
                    AppEventLogger.error(
                        component = TAG,
                        action = "switch_capability_model_failed",
                        details = "reason=model_file_missing, capability=$capability, modelName=${model.name}"
                    )
                    onComplete(false)
                    return@launch
                }
                if (mmprojPath != null && !File(mmprojPath).exists()) {
                    AppEventLogger.error(
                        component = TAG,
                        action = "switch_capability_model_failed",
                        details = "reason=mmproj_missing, capability=$capability, modelName=${model.name}"
                    )
                    onComplete(false)
                    return@launch
                }

                val previousModelName = loadedModel.value?.name ?: ""

                val config = ModelConfig(
                    name = model.name,
                    filePath = modelPath,
                    size = if (model.fileSize > 0) model.fileSize else File(modelPath).length(),
                    isVisionModel = ModelCapability.VISION in model.capabilities,
                    mmprojPath = mmprojPath
                )

                val result = modelRepository.loadModel(config)
                if (result.isSuccess) {
                    AppEventLogger.info(
                        component = TAG,
                        action = "capability_model_switch",
                        details = "capability=$capability, fromModel=$previousModelName, toModel=${model.name}"
                    )
                    updateVisionModelState()
                    updateCapabilityModelStates()
                    onComplete(true)
                } else {
                    AppEventLogger.error(
                        component = TAG,
                        action = "switch_capability_model_failed",
                        details = "reason=${result.exceptionOrNull()?.message ?: "unknown"}, capability=$capability"
                    )
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error switching model for capability $capability", e)
                AppEventLogger.error(
                    component = TAG,
                    action = "switch_capability_model_exception",
                    details = "capability=$capability, reason=${e.message ?: "unknown"}",
                    throwable = e
                )
                onComplete(false)
            } finally {
                _isLoadingCapabilityModel.value = false
                if (capability == ModelCapability.VISION) _isLoadingVisionModel.value = false
            }
        }
    }

    /** Refresh the generalized capability chooser map; call after download completion. */
    fun refreshCapabilityModelStates() {
        updateCapabilityModelStates()
    }

    /**
     * Switch to a specific vision model if downloaded
     */
    fun switchToVisionModel(model: DownloadableModel, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _isLoadingVisionModel.value = true
            AppEventLogger.info(
                component = TAG,
                action = "switch_vision_model_requested",
                details = "modelId=${model.id}, modelName=${model.name}"
            )
            try {
                val modelsDir = File(application.filesDir, "models")
                val modelPath = File(modelsDir, model.fileName).absolutePath
                val mmprojPath = model.mmprojFileName?.let { 
                    File(modelsDir, it).absolutePath 
                }
                
                // Verify files exist
                if (!File(modelPath).exists()) {
                    Log.e(TAG, "Vision model file not found: $modelPath")
                    AppEventLogger.error(
                        component = TAG,
                        action = "switch_vision_model_failed",
                        details = "reason=model_file_missing, modelName=${model.name}"
                    )
                    onComplete(false)
                    _isLoadingVisionModel.value = false
                    return@launch
                }
                if (mmprojPath != null && !File(mmprojPath).exists()) {
                    Log.e(TAG, "Vision mmproj file not found: $mmprojPath")
                    AppEventLogger.error(
                        component = TAG,
                        action = "switch_vision_model_failed",
                        details = "reason=mmproj_missing, modelName=${model.name}"
                    )
                    onComplete(false)
                    _isLoadingVisionModel.value = false
                    return@launch
                }
                
                val config = ModelConfig(
                    name = model.name,
                    filePath = modelPath,
                    size = if (model.fileSize > 0) model.fileSize else File(modelPath).length(),
                    isVisionModel = true,
                    mmprojPath = mmprojPath
                )
                
                val result = modelRepository.loadModel(config)
                if (result.isSuccess) {
                    Log.i(TAG, "Successfully switched to vision model: ${model.name}")
                    AppEventLogger.info(
                        component = TAG,
                        action = "switch_vision_model_succeeded",
                        details = "modelId=${model.id}, modelName=${model.name}"
                    )
                    updateVisionModelState()
                    onComplete(true)
                } else {
                    Log.e(TAG, "Failed to load vision model: ${result.exceptionOrNull()}")
                    AppEventLogger.error(
                        component = TAG,
                        action = "switch_vision_model_failed",
                        details = "reason=${result.exceptionOrNull()?.message ?: "unknown"}, modelName=${model.name}"
                    )
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error switching to vision model", e)
                AppEventLogger.error(
                    component = TAG,
                    action = "switch_vision_model_exception",
                    details = "modelName=${model.name}, reason=${e.message ?: "unknown"}",
                    throwable = e
                )
                onComplete(false)
            } finally {
                _isLoadingVisionModel.value = false
            }
        }
    }
    
    /**
     * Switch to vision model if downloaded (legacy - uses first available)
     */
    fun switchToVisionModel(onComplete: (Boolean) -> Unit = {}) {
        val state = _visionModelState.value
        if (state !is VisionModelState.Downloaded) {
            Log.w(TAG, "Cannot switch to vision model - not downloaded")
            AppEventLogger.warn(component = TAG, action = "switch_vision_model_blocked_not_downloaded")
            onComplete(false)
            return
        }
        
        // Use the new method with the first downloaded model
        switchToVisionModel(state.model, onComplete)
    }
    
    /**
     * Get the best vision model to download (smallest one for quick start)
     */
    fun getVisionModel(): DownloadableModel {
        val state = _visionModelState.value
        return when (state) {
            is VisionModelState.Downloaded -> state.model
            is VisionModelState.NotDownloaded -> state.model
            VisionModelState.Ready -> {
                // Return the currently loaded vision model or fallback
                val allVision = AvailableModels.getAllModels().filter { it.isVisionModel }
                allVision.minByOrNull { it.fileSize } ?: AvailableModels.SMOLVLM_1_8B_Q8_0
            }
        }
    }
    
    /**
     * Get all available vision models for selection
     */
    fun getAllVisionModels(): List<DownloadableModel> {
        return AvailableModels.getAllModels().filter { it.isVisionModel }
    }
    
    /**
     * Get all downloaded vision models
     */
    fun getDownloadedVisionModels(): List<DownloadableModel> {
        val state = _visionModelState.value
        return when (state) {
            is VisionModelState.Downloaded -> state.allDownloaded
            else -> emptyList()
        }
    }
    
    /**
     * Refresh vision model state (call after download completes)
     */
    fun refreshVisionModelState() {
        updateVisionModelState()
    }
    
    private fun loadAllConversations() {
        viewModelScope.launch {
            chatRepository.getAllConversations()
                .collect { convos ->
                    _conversations.value = convos
                }
        }
    }

    private suspend fun initializeConversation() {
        val conversationsSnapshot = try {
            chatRepository.getAllConversations().first()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversations during init", e)
            AppEventLogger.error(
                component = TAG,
                action = "initialize_conversation_failed",
                details = "reason=${e.message ?: "unknown"}",
                throwable = e
            )
            startNewConversation()
            return
        }

        if (conversationsSnapshot.isEmpty()) {
            Log.d(TAG, "No existing conversations found, creating a fresh one")
            AppEventLogger.info(component = TAG, action = "initialize_conversation_new_created", details = "reason=no_existing_conversations")
            startNewConversation()
            return
        }

        val latestConversation = conversationsSnapshot.maxByOrNull { it.updatedAt }
        if (latestConversation == null) {
            Log.d(TAG, "Unable to resolve latest conversation, creating a fresh one")
            AppEventLogger.warn(component = TAG, action = "initialize_conversation_new_created", details = "reason=latest_not_resolved")
            startNewConversation()
            return
        }

        val hasMessages = try {
            chatRepository.getMessagesForConversation(latestConversation.id).first().isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inspect messages for conversation ${latestConversation.id}", e)
            AppEventLogger.error(
                component = TAG,
                action = "initialize_conversation_inspect_failed",
                details = "conversationId=${latestConversation.id}, reason=${e.message ?: "unknown"}",
                throwable = e
            )
            startNewConversation(modelName = latestConversation.modelName)
            return
        }

        if (hasMessages) {
            Log.d(TAG, "Latest conversation (${latestConversation.id}) has messages, creating a new chat for fresh start")
            AppEventLogger.info(
                component = TAG,
                action = "initialize_conversation_new_created",
                details = "reason=latest_has_messages, previousConversationId=${latestConversation.id}"
            )
            startNewConversation(modelName = latestConversation.modelName)
        } else {
            Log.d(TAG, "Reusing existing empty conversation with ID: ${latestConversation.id}")
            AppEventLogger.info(
                component = TAG,
                action = "initialize_conversation_reused",
                details = "conversationId=${latestConversation.id}"
            )
            _currentConversationId.value = latestConversation.id
            _messages.value = emptyList()
            _generationState.value = GenerationState.Idle
            loadMessages(latestConversation.id)
        }
    }
    
    fun setInputText(text: String) {
        _inputText.value = text
    }
    
    // Multi-image handling for vision models
    fun addImage(imagePath: String) {
        val current = _selectedImages.value.toMutableList()
        if (!current.contains(imagePath) && current.size < MAX_IMAGES) {
            current.add(imagePath)
            _selectedImages.value = current
            AppEventLogger.debug(
                component = TAG,
                action = "image_added",
                details = "selectedCount=${current.size}"
            )
        }
    }
    
    fun removeImage(imagePath: String) {
        _selectedImages.value = _selectedImages.value.filter { it != imagePath }
        AppEventLogger.debug(
            component = TAG,
            action = "image_removed",
            details = "selectedCount=${_selectedImages.value.size}"
        )
    }
    
    fun clearSelectedImages() {
        _selectedImages.value = emptyList()
        _activeQuickAction.value = null
        AppEventLogger.debug(component = TAG, action = "images_cleared")
    }
    
    // Legacy single image support
    fun setSelectedImage(imagePath: String?) {
        _selectedImages.value = if (imagePath != null) listOf(imagePath) else emptyList()
        AppEventLogger.debug(
            component = TAG,
            action = "single_image_set",
            details = "hasImage=${imagePath != null}"
        )
    }
    
    fun clearSelectedImage() {
        clearSelectedImages()
    }
    
    // Vision quick actions
    fun setQuickAction(action: VisionQuickAction?) {
        _activeQuickAction.value = action
        if (action != null) {
            _inputText.value = action.promptText
        }
        AppEventLogger.debug(
            component = TAG,
            action = "vision_quick_action_set",
            details = "action=${action?.name ?: "none"}"
        )
    }
    
    // Prompt template selection
    fun selectTemplate(template: PromptTemplate) {
        _selectedTemplate.value = template
        AppEventLogger.debug(
            component = TAG,
            action = "prompt_template_selected",
            details = "templateId=${template.id}"
        )
        // Fix [2.7]: pre-fill input so productivity templates are immediately usable
        when (template.id) {
            PromptTemplates.TODO_LIST.id ->
                if (_inputText.value.isBlank()) {
                    _inputText.value = "- [ ] "
                }
            PromptTemplates.MEETING_NOTES.id ->
                if (_inputText.value.isBlank()) {
                    _inputText.value = "Attendees:\nAgenda:\n- "
                }
            else -> { /* keep existing input */ }
        }
    }
    
    fun clearTemplate() {
        _selectedTemplate.value = null
        AppEventLogger.debug(component = TAG, action = "prompt_template_cleared")
    }
    
    // Tool calling methods
    fun setToolCallingEnabled(enabled: Boolean) {
        _isToolCallingEnabled.value = enabled
        AppEventLogger.info(component = TAG, action = "tool_calling_toggled", details = "enabled=$enabled")
    }
    
    fun executeToolCall(toolCall: ToolCall) {
        viewModelScope.launch {
            try {
                _pendingToolCall.value = toolCall
                AppEventLogger.info(component = TAG, action = "tool_call_execute_started", details = "tool=${toolCall.name}")
                val result = toolExecutor.execute(toolCall)
                _toolResult.value = result
                _pendingToolCall.value = null
                AppEventLogger.info(
                    component = TAG,
                    action = "tool_call_execute_completed",
                    details = "tool=${toolCall.name}, success=${result.success}"
                )
                
                // Reset after a delay
                kotlinx.coroutines.delay(5000)
                _toolResult.value = null
            } catch (e: Exception) {
                Log.e(TAG, "Tool execution failed", e)
                AppEventLogger.error(
                    component = TAG,
                    action = "tool_call_execute_failed",
                    details = "tool=${toolCall.name}, reason=${e.message ?: "unknown"}",
                    throwable = e
                )
                _toolResult.value = ToolResult(
                    toolName = toolCall.name,
                    success = false,
                    error = e.message ?: "Unknown error"
                )
                _pendingToolCall.value = null
            }
        }
    }
    
    fun rejectToolCall() {
        _pendingToolCall.value = null
        AppEventLogger.info(component = TAG, action = "tool_call_rejected")
    }
    
    /**
     * Parse tool call from LLM response and optionally auto-execute
     */
    fun handleToolCallInResponse(responseText: String): Boolean {
        if (!_isToolCallingEnabled.value) return false
        
        val toolCall = ToolCallParser.parseToolCall(responseText)
        if (toolCall != null) {
            _pendingToolCall.value = toolCall
            AppEventLogger.info(component = TAG, action = "tool_call_detected", details = "tool=${toolCall.name}")
            return true
        }
        return false
    }
    
    /**
     * Get the tools system prompt if tool calling is enabled
     */
    fun getToolsSystemPrompt(): String? {
        return if (_isToolCallingEnabled.value) {
            MobileTools.generateToolsSystemPrompt()
        } else null
    }
    
    companion object {
        private const val TAG = "ChatViewModel"
        const val MAX_IMAGES = 4 // Maximum images per message

        /**
         * Renders only the ACTIVE version of each turn and stamps the transient
         * [Message.versionIndex]/[Message.versionCount] used by the ‹k/n› toggle.
         * Pure (no I/O) → unit-tested directly.
         */
        fun projectActiveVersions(all: List<Message>): List<Message> {
            val sibsByParent = all
                .filter { !it.isUser && it.parentMessageId != null }
                .groupBy { it.parentMessageId!! }
            return all.filter { it.isActiveVersion }.map { m ->
                val sibs = m.parentMessageId?.let { sibsByParent[it] }
                if (sibs != null && sibs.size > 1) {
                    val ordered = sibs.sortedWith(compareBy({ it.timestamp }, { it.id }))
                    m.copy(
                        versionIndex = ordered.indexOfFirst { it.id == m.id },
                        versionCount = sibs.size,
                    )
                } else {
                    m
                }
            }
        }

        /**
         * The assistant answers (all versions) belonging to [userMsg]'s turn: the
         * assistant rows after it up to the next user message. Positional, so it
         * works for legacy null-parent answers too. Pure → unit-tested.
         */
        fun answerMessagesFor(all: List<Message>, userMsg: Message): List<Message> {
            val idx = all.indexOfFirst { it.id == userMsg.id }
            if (idx < 0) return emptyList()
            val after = all.drop(idx + 1)
            val nextUser = after.indexOfFirst { it.isUser }
            val slice = if (nextUser >= 0) after.take(nextUser) else after
            return slice.filter { !it.isUser && !it.isModelChangeMarker }
        }
    }
    
    fun isVisionSupported(): Boolean {
        return inferenceRepository.isVisionSupported()
    }
    
    fun startNewConversation(modelName: String = "", onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            // First, ensure any ongoing generation is stopped
            stopGeneration()
            AppEventLogger.info(
                component = TAG,
                action = "start_new_conversation_requested",
                details = "modelName=$modelName"
            )

            val title = "New Chat ${System.currentTimeMillis()}"
            val conversationId = chatRepository.createConversation(title, modelName)
            _currentConversationId.value = conversationId
            _messages.value = emptyList() // Clear messages immediately

            // Reset generation state
            _generationState.value = GenerationState.Idle

            // Phase 1 (Subphase G): drop engine-side conversation state (KV
            // cache / MediaPipe session) so the new conversation starts fresh.
            inferenceRepository.resetConversation()

            Log.d(TAG, "Started new conversation with ID: $conversationId")
            AppEventLogger.info(component = TAG, action = "start_new_conversation_succeeded", details = "conversationId=$conversationId")
            loadMessages(conversationId)
            onCreated?.invoke(conversationId)
        }
    }

    fun loadConversation(conversationId: Long) {
        viewModelScope.launch {
            // Stop any ongoing generation when switching conversations
            stopGeneration()

            _currentConversationId.value = conversationId
            _generationState.value = GenerationState.Idle

            // Phase 1 (Subphase G): switching to a different conversation
            // means the engine's cached KV no longer matches what the next
            // prompt will tokenize. Drop it.
            inferenceRepository.resetConversation()

            Log.d(TAG, "Loading conversation: $conversationId")
            AppEventLogger.info(component = TAG, action = "load_conversation", details = "conversationId=$conversationId")
            loadMessages(conversationId)
        }
    }
    
    private fun loadMessages(conversationId: Long) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            chatRepository.getMessagesForConversation(conversationId)
                .collect { messages ->
                    _messages.value = projectActiveVersions(messages)
                }
        }
    }

    
    fun sendMessage() {
        val text = _inputText.value.trim()

        // Allow attachment-only messages (e.g. an audio clip to transcribe).
        if (text.isEmpty() && _selectedImages.value.isEmpty() && _selectedAudio.value.isEmpty()) {
            AppEventLogger.debug(component = TAG, action = "send_message_ignored_empty")
            return
        }
        
        // Prevent sending if already generating
        val currentState = _generationState.value
        if (isGenerationInFlight(currentState)) {
            AppEventLogger.warn(
                component = TAG,
                action = "send_message_blocked_busy",
                details = "state=${currentState::class.simpleName}"
            )
            return
        }

        // Check if a model is loaded
        val model = loadedModel.value
        if (model == null) {
            AppEventLogger.warn(component = TAG, action = "send_message_blocked_no_model")
            viewModelScope.launch {
                _generationState.value = GenerationState.Error("No model loaded. Please load a model from the Models tab first.")
                kotlinx.coroutines.delay(3000)
                _generationState.value = GenerationState.Idle
            }
            return
        }
        
        // Start foreground service to keep inference running in background
        InferenceService.startInference(application)

        generationJob = viewModelScope.launch {
            // Ensure we have a conversation ID
            var conversationId = _currentConversationId.value
            
            // Track if this is a new conversation (for auto-naming)
            var isFirstMessage: Boolean
            
            if (conversationId == null) {
                val modelName = loadedModel.value?.name ?: "Unknown"
                val title = "New Chat ${System.currentTimeMillis()}"
                conversationId = chatRepository.createConversation(title, modelName)
                _currentConversationId.value = conversationId
                loadMessages(conversationId)
                isFirstMessage = true
                kotlinx.coroutines.delay(50)
            } else {
                // Check if this conversation has any messages
                isFirstMessage = _messages.value.isEmpty()
            }
            
            // Store the user text for auto-naming
            val userText = text
            
            // Get selected images (supports multiple)
            val imagePaths = _selectedImages.value.toList()
            // Get selected audio attachments (audio scribe)
            val audioPaths = _selectedAudio.value.toList()

            // Get selected template and apply if present
            val template = _selectedTemplate.value
            val finalUserMessage = if (template != null) {
                template.generatePrompt(userText)
            } else {
                userText
            }
            val templateSystemPrompt = template?.systemPrompt

            AppEventLogger.info(
                component = TAG,
                action = "send_message_started",
                details = "conversationId=$conversationId, model=${model.name}, imageCount=${imagePaths.size}, template=${template?.id ?: "none"}, isFirstMessage=$isFirstMessage"
            )
            
            // Clear input and images immediately
            _inputText.value = ""
            _selectedImages.value = emptyList()
            _selectedAudio.value = emptyList()
            _activeQuickAction.value = null
            _selectedTemplate.value = null
            
            // Use current settings (template system prompt takes precedence if set)
            val settings = generationPreferences.getSettings().first()
            // Phase 2 (§3.9): inject the agent-skill system prompt prefix when
            // the loaded model declares AGENT_SKILLS, the user has turned the
            // toggle on, and at least one skill is enabled.
            val baseSystemPrompt = templateSystemPrompt ?: settings.systemPrompt
            val effectiveSystemPrompt =
                augmentWithAgentSkills(baseSystemPrompt, settings.enableAgentSkills)

            // Web search runs for several seconds before the use-case flow emits.
            // Surface the indicator the instant Send is pressed (state-driven, so
            // it can't flicker or be missed by the flow collector racing the
            // engine's first states). The use case re-emits SearchingWeb itself;
            // this just guarantees the indicator appears immediately and persists.
            if (settings.enableWebSearch) {
                _generationState.value = GenerationState.SearchingWeb()
            }

            try {
                sendMessageUseCase(
                    conversationId = conversationId,
                    userMessage = finalUserMessage,
                    temperature = template?.suggestedTemperature ?: settings.temperature,
                    maxTokens = template?.suggestedMaxTokens ?: settings.maxTokens,
                    topP = settings.topP,
                    topK = settings.topK,
                    repeatPenalty = settings.repeatPenalty,
                    repeatLastN = settings.repeatLastN,
                    minP = settings.minP,
                    tfsZ = settings.tfsZ,
                    typicalP = settings.typicalP,
                    mirostat = settings.mirostat,
                    mirostatTau = settings.mirostatTau,
                    mirostatEta = settings.mirostatEta,
                    stopSequences = settings.stopSequences,
                    systemPrompt = effectiveSystemPrompt,
                    imagePaths = imagePaths,
                    audioPaths = audioPaths,
                    reasoningEnabled = if (ModelCapability.REASONING in _loadedModelCapabilities.value) {
                        settings.enableThinking
                    } else {
                        null
                    },
                    webSearchEnabled = settings.enableWebSearch
                )
                    // Phase 1C: if the collector falls behind during a fast
                    // token stream, drop intermediate Generating values rather
                    // than queueing them. conflate keeps the most-recent value
                    // and forwards it once the collector is ready — terminal
                    // states (Complete / Error) are preserved because they
                    // arrive as the "latest" once streaming ends. This is the
                    // safety net for any path that still emits faster than
                    // Compose can render.
                    .conflate()
                    .collect { state ->
                    _generationState.value = state

                    when (state) {
                        is GenerationState.Thinking -> {
                            _liveThinkingContent.value = state.content
                            if (state.thoughtSummary != null) _liveThoughtSummary.value = state.thoughtSummary
                        }
                        is GenerationState.Complete -> {
                            AppEventLogger.info(
                                component = TAG,
                                action = "send_message_completed",
                                details = "conversationId=$conversationId, responseLength=${state.text.length}"
                            )
                            // Stop foreground service
                            InferenceService.stopInference(application)
                            // Auto-generate chat name from first message
                            notifyCompletionIfNeeded(state.text)
                            if (isFirstMessage) {
                                updateConversationTitle(conversationId, userText)
                            }
                            kotlinx.coroutines.delay(100)
                            _liveThinkingContent.value = null
                            _liveThoughtSummary.value = null
                            _generationState.value = GenerationState.Idle
                        }
                        is GenerationState.Error -> {
                            AppEventLogger.error(
                                component = TAG,
                                action = "send_message_failed",
                                details = "conversationId=$conversationId, reason=${state.message}"
                            )
                            // Stop foreground service
                            InferenceService.stopInference(application)
                            kotlinx.coroutines.delay(3000)
                            _liveThinkingContent.value = null
                            _liveThoughtSummary.value = null
                            _generationState.value = GenerationState.Idle
                        }
                        else -> { /* keep state */ }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled by stop / edit-and-resend / VM teardown: release
                // the FGS, but the canceller owns the generation state — don't
                // overwrite it with an error.
                InferenceService.stopInference(application)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Generation error", e)
                AppEventLogger.error(
                    component = TAG,
                    action = "send_message_exception",
                    details = "conversationId=$conversationId, reason=${e.message ?: "unknown"}",
                    throwable = e
                )
                // Stop foreground service on error
                InferenceService.stopInference(application)
                _generationState.value = GenerationState.Error("Error: ${e.message}")
                kotlinx.coroutines.delay(3000)
                _liveThinkingContent.value = null
                _liveThoughtSummary.value = null
                _generationState.value = GenerationState.Idle
            }
        }
    }

    fun stopGeneration() {
        viewModelScope.launch {
            // Capture any partial text before stopping
            val currentState = _generationState.value
            val partialText = when (currentState) {
                is GenerationState.Generating -> currentState.currentText
                else -> ""
            }

            AppEventLogger.info(
                component = TAG,
                action = "stop_generation_requested",
                details = "state=${currentState::class.simpleName}, partialLength=${partialText.length}"
            )
            
            inferenceRepository.stopGeneration()
            
            // Stop foreground service
            InferenceService.stopInference(application)
            
            // Set cancelled state with partial text (will be handled in UI)
            _generationState.value = GenerationState.Cancelled(partialText)
            
            // Auto-reset to Idle after showing the cancelled message
            kotlinx.coroutines.delay(3000)
            if (_generationState.value is GenerationState.Cancelled) {
                _liveThinkingContent.value = null
                _liveThoughtSummary.value = null
                _generationState.value = GenerationState.Idle
            }
        }
    }

    private fun notifyCompletionIfNeeded(responseText: String) {
        if (responseText.isBlank() || !isAppInBackgroundOrScreenLocked()) return
        InferenceService.showCompletionNotification(application, responseText)
    }

    private fun isAppInBackgroundOrScreenLocked(): Boolean {
        val processInfo = ActivityManager.RunningAppProcessInfo().also {
            ActivityManager.getMyMemoryState(it)
        }
        val isAppVisible = processInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        val powerManager = application.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenInteractive = powerManager?.isInteractive ?: true
        return !isAppVisible || !isScreenInteractive
    }

    /**
     * When a model switch brings in a set of capabilities that no longer
     * includes what the user had toggled on, turn those toggles off and
     * notify the user via Snackbar so the change isn't silent.
     */
    private fun autoDisableStaleCapabilityToggles(
        modelName: String,
        newCaps: Set<ModelCapability>,
    ) {
        viewModelScope.launch {
            val settings = generationPreferences.getSettings().first()
            if (settings.enableThinking && ModelCapability.REASONING !in newCaps) {
                generationPreferences.saveEnableThinking(false)
                _snackbarMessages.trySend("$modelName doesn't support Reasoning — it's been turned off")
                AppEventLogger.info(component = TAG, action = "capability_auto_disabled", details = "capability=REASONING, model=$modelName")
            }
            if (settings.enableAgentSkills && ModelCapability.AGENT_SKILLS !in newCaps) {
                generationPreferences.saveEnableAgentSkills(false)
                _snackbarMessages.trySend("$modelName doesn't support Agent Skills — it's been turned off")
                AppEventLogger.info(component = TAG, action = "capability_auto_disabled", details = "capability=AGENT_SKILLS, model=$modelName")
            }
            if (settings.enableSpeculativeDecoding && ModelCapability.SPECULATIVE_DECODING !in newCaps) {
                generationPreferences.saveEnableSpeculativeDecoding(false)
                _snackbarMessages.trySend("$modelName doesn't support Speculative Decoding — it's been turned off")
                AppEventLogger.info(component = TAG, action = "capability_auto_disabled", details = "capability=SPECULATIVE_DECODING, model=$modelName")
            }
        }
    }

    /**
     * Phase 2 (§3.9): prefix the agent-skill system-prompt injection when the
     * loaded model declares AGENT_SKILLS, the user toggle is on, and at least
     * one skill is enabled. Returns [base] unchanged otherwise. Shared by the
     * send and regenerate paths so both compose the same system prompt.
     */
    private fun augmentWithAgentSkills(base: String, enableAgentSkills: Boolean): String {
        return if (
            enableAgentSkills &&
            ModelCapability.AGENT_SKILLS in _loadedModelCapabilities.value
        ) {
            val injection = skillRepository.getSystemPromptInjection()
            if (injection != null) "$injection\n\n$base" else base
        } else base
    }

    /**
     * Re-process a user message to get a new response
     * This uses the message content and any attached images to generate a fresh response
     */
    fun reProcessMessage(message: Message) {
        if (!message.isUser) return
        
        // Prevent if already generating
        val currentState = _generationState.value
        if (isGenerationInFlight(currentState)) {
            AppEventLogger.warn(
                component = TAG,
                action = "regenerate_blocked_busy",
                details = "state=${currentState::class.simpleName}"
            )
            return
        }
        
        // Start foreground service to keep inference running in background
        InferenceService.startInference(application)

        generationJob = viewModelScope.launch {
            val conversationId = _currentConversationId.value ?: return@launch
            val settings = generationPreferences.getSettings().first()
            AppEventLogger.info(
                component = TAG,
                action = "regenerate_started",
                details = "conversationId=$conversationId, messageId=${message.id}, imageCount=${message.imagePaths.size}"
            )

            // Versioning: keep the previous answer(s) as INACTIVE siblings rather
            // than deleting them — excluded from this turn's context but still
            // toggleable in the UI. Backfill parentMessageId so legacy answers
            // join the group. Pre-deactivation also leaves the active message list
            // ending at the user turn, so buildPrompt's "last message is the
            // current turn" assumption holds.
            val all = chatRepository.getMessagesForConversation(conversationId).first()
            val priorAnswers = answerMessagesFor(all, message)
            priorAnswers.forEach {
                chatRepository.updateMessage(it.copy(parentMessageId = message.id, isActiveVersion = false))
            }
            inferenceRepository.resetConversation()

            var succeeded = false
            try {
                sendMessageUseCase(
                    conversationId = conversationId,
                    userMessage = message.content,
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens,
                    topP = settings.topP,
                    topK = settings.topK,
                    repeatPenalty = settings.repeatPenalty,
                    repeatLastN = settings.repeatLastN,
                    minP = settings.minP,
                    tfsZ = settings.tfsZ,
                    typicalP = settings.typicalP,
                    mirostat = settings.mirostat,
                    mirostatTau = settings.mirostatTau,
                    mirostatEta = settings.mirostatEta,
                    stopSequences = settings.stopSequences,
                    // Regeneration has no template prompt, but must still get
                    // the agent-skills injection so it matches a fresh send.
                    systemPrompt = augmentWithAgentSkills(
                        settings.systemPrompt,
                        settings.enableAgentSkills
                    ),
                    imagePaths = message.imagePaths,
                    audioPaths = message.audioPaths,
                    isRegeneration = true, // Don't create new user message
                    parentUserMessageId = message.id, // new answer joins this turn's version group
                    reasoningEnabled = if (ModelCapability.REASONING in _loadedModelCapabilities.value) {
                        settings.enableThinking
                    } else {
                        null
                    },
                    webSearchEnabled = settings.enableWebSearch
                )
                    // Phase 1C: matches the send-message path — conflate keeps
                    // the latest streaming value when the collector is slow.
                    .conflate()
                    .collect { state ->
                    _generationState.value = state

                    when (state) {
                        is GenerationState.Complete -> {
                            succeeded = true // a new active version was persisted
                            AppEventLogger.info(
                                component = TAG,
                                action = "regenerate_completed",
                                details = "conversationId=$conversationId, responseLength=${state.text.length}"
                            )
                            InferenceService.stopInference(application)
                            kotlinx.coroutines.delay(100)
                            _generationState.value = GenerationState.Idle
                            notifyCompletionIfNeeded(state.text)
                        }
                        is GenerationState.Error -> {
                            AppEventLogger.error(
                                component = TAG,
                                action = "regenerate_failed",
                                details = "conversationId=$conversationId, reason=${state.message}"
                            )
                            InferenceService.stopInference(application)
                            kotlinx.coroutines.delay(3000)
                            _generationState.value = GenerationState.Idle
                        }
                        else -> { /* keep state */ }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // See sendMessage: the canceller owns the generation state.
                InferenceService.stopInference(application)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Re-generation error", e)
                AppEventLogger.error(
                    component = TAG,
                    action = "regenerate_exception",
                    details = "conversationId=$conversationId, reason=${e.message ?: "unknown"}",
                    throwable = e
                )
                InferenceService.stopInference(application)
                _generationState.value = GenerationState.Error("Error: ${e.message}")
                kotlinx.coroutines.delay(3000)
                _generationState.value = GenerationState.Idle
            } finally {
                // Guarantee exactly one active version on every exit: if the
                // regeneration didn't complete (error/cancel/exception), no new
                // active answer was inserted — re-activate the most recent prior
                // sibling so the turn never renders with zero answers.
                if (!succeeded) {
                    priorAnswers.maxByOrNull { it.timestamp }?.let {
                        chatRepository.setActiveVersion(message.id, it.id)
                    }
                }
            }
        }
    }

    private fun updateConversationTitle(conversationId: Long, userMessage: String) {
        viewModelScope.launch {
            try {
                val conversation = chatRepository.getConversation(conversationId)
                if (conversation != null) {
                    val newTitle = ChatNameGenerator.generateSmartTitle(userMessage)
                    val updated = conversation.copy(
                        title = newTitle,
                        updatedAt = System.currentTimeMillis()
                    )
                    chatRepository.updateConversation(updated)
                    Log.d(TAG, "Updated conversation title to: $newTitle")
                    AppEventLogger.info(
                        component = TAG,
                        action = "conversation_title_auto_updated",
                        details = "conversationId=$conversationId, titleLength=${newTitle.length}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update conversation title", e)
                AppEventLogger.error(
                    component = TAG,
                    action = "conversation_title_auto_update_failed",
                    details = "conversationId=$conversationId, reason=${e.message ?: "unknown"}",
                    throwable = e
                )
            }
        }
    }
    
    /** Phase 2 (§3.6): toggle the persisted reasoning preference. */
    fun setEnableThinking(value: Boolean) {
        viewModelScope.launch {
            generationPreferences.saveEnableThinking(value)
            AppEventLogger.info(
                component = TAG,
                action = "capability_toggle_changed",
                details = "capability=REASONING, model=${loadedModel.value?.name ?: "none"}, enabled=$value"
            )
        }
    }

    /** Phase 2 (§3.7): toggle the persisted speculative-decoding preference. */
    fun setEnableSpeculativeDecoding(value: Boolean) {
        viewModelScope.launch {
            generationPreferences.saveEnableSpeculativeDecoding(value)
            AppEventLogger.info(
                component = TAG,
                action = "capability_toggle_changed",
                details = "capability=SPECULATIVE_DECODING, model=${loadedModel.value?.name ?: "none"}, enabled=$value"
            )
        }
    }

    /** Phase 2 (§3.9): toggle the persisted agent-skills preference. */
    fun setEnableAgentSkills(value: Boolean) {
        viewModelScope.launch {
            generationPreferences.saveEnableAgentSkills(value)
            AppEventLogger.info(
                component = TAG,
                action = "capability_toggle_changed",
                details = "capability=AGENT_SKILLS, model=${loadedModel.value?.name ?: "none"}, enabled=$value"
            )
        }
    }

    /**
     * Toggle the persisted Web Search preference. Model-agnostic — applies to
     * every model, so it is never gated by [ModelCapability].
     */
    fun setEnableWebSearch(value: Boolean) {
        viewModelScope.launch {
            generationPreferences.saveEnableWebSearch(value)
            AppEventLogger.info(
                component = TAG,
                action = "capability_toggle_changed",
                details = "capability=WEB_SEARCH, model=${loadedModel.value?.name ?: "none"}, enabled=$value"
            )
        }
    }

    /**
     * Audio Scribe: transcode a user-picked audio file to a model-ready WAV and
     * attach it to the next message.
     */
    fun pickAudioFile(uri: android.net.Uri) {
        AppEventLogger.info(
            component = TAG,
            action = "audio_pick_file_requested",
            details = "model=${loadedModel.value?.name ?: "none"}"
        )
        viewModelScope.launch {
            val wav = withContext(Dispatchers.IO) { audioInputManager.prepareAudioForModel(uri) }
            if (wav != null) {
                addAudio(wav.absolutePath)
            } else {
                AppEventLogger.warn(component = TAG, action = "audio_pick_file_failed")
            }
        }
    }

    /**
     * Audio Scribe: convert a finished raw-PCM recording to a model-ready WAV
     * and attach it to the next message.
     */
    fun attachRecordedAudio(pcmPath: String) {
        viewModelScope.launch {
            val wav = withContext(Dispatchers.IO) { audioInputManager.pcmToWav(File(pcmPath)) }
            if (wav != null) {
                addAudio(wav.absolutePath)
            } else {
                AppEventLogger.warn(component = TAG, action = "audio_record_convert_failed")
            }
            audioInputManager.resetState()
        }
    }

    /** Attach a single audio clip (replaces any previously attached clip). */
    fun addAudio(path: String) {
        _selectedAudio.value = listOf(path)
        AppEventLogger.debug(component = TAG, action = "audio_added", details = "path=$path")
    }

    fun removeAudio(path: String) {
        _selectedAudio.value = _selectedAudio.value.filter { it != path }
    }

    fun clearSelectedAudio() {
        _selectedAudio.value = emptyList()
    }

    /** Phase 2 (§3.9): per-skill enable toggle, persisted via SkillRepository. */
    fun setSkillEnabled(name: String, enabled: Boolean) {
        skillRepository.setEnabled(name, enabled)
        AppEventLogger.info(
            component = TAG,
            action = "agent_skill_enabled_changed",
            details = "skill=$name, enabled=$enabled"
        )
    }

    /** Phase 2 (§3.12): log when the capability chooser sheet is opened. */
    fun logCapabilityChooserOpened(capability: ModelCapability, reason: String) {
        AppEventLogger.info(
            component = TAG,
            action = "capability_chooser_opened",
            details = "capability=$capability, reason=$reason"
        )
    }

    fun updateGenerationParams(
        temp: Float? = null,
        tokens: Int? = null,
        p: Float? = null,
        k: Int? = null
    ) {
        viewModelScope.launch {
            val currentSettings = _generationSettings.value
            val newSettings = currentSettings.copy(
                temperature = temp ?: currentSettings.temperature,
                maxTokens = tokens ?: currentSettings.maxTokens,
                topP = p ?: currentSettings.topP,
                topK = k ?: currentSettings.topK
            )
            _generationSettings.value = newSettings
            generationPreferences.saveSettings(newSettings)
            AppEventLogger.info(
                component = TAG,
                action = "generation_params_updated",
                details = "temperature=${newSettings.temperature}, maxTokens=${newSettings.maxTokens}, topP=${newSettings.topP}, topK=${newSettings.topK}"
            )
        }
    }
    
    fun clearAllHistory() {
        viewModelScope.launch {
            AppEventLogger.warn(component = TAG, action = "clear_all_history_requested")
            chatRepository.clearAllHistory()
            // Start a new conversation after clearing
            startNewConversation()
        }
    }
    
    fun deleteConversation(conversationId: Long) {
        viewModelScope.launch {
            AppEventLogger.info(component = TAG, action = "delete_conversation_requested", details = "conversationId=$conversationId")
            try {
                chatRepository.deleteConversation(conversationId)

                // If we deleted the current conversation, create a new one
                if (_currentConversationId.value == conversationId) {
                    startNewConversation()
                }
            } catch (e: Exception) {
                AppEventLogger.error(TAG, "delete_conversation_failed", "conversationId=$conversationId", e)
            }
        }
    }

    fun renameConversation(conversationId: Long, newTitle: String) {
        viewModelScope.launch {
            val t = newTitle.trim()
            if (t.isEmpty()) return@launch
            try {
                val conv = chatRepository.getConversation(conversationId) ?: return@launch
                chatRepository.updateConversation(
                    conv.copy(title = t, updatedAt = System.currentTimeMillis())
                )
                AppEventLogger.info(
                    component = TAG,
                    action = "rename_conversation",
                    details = "conversationId=$conversationId, titleLength=${t.length}"
                )
            } catch (e: Exception) {
                AppEventLogger.error(TAG, "rename_conversation_failed", "conversationId=$conversationId", e)
            }
        }
    }

    fun deleteMessage(message: Message) {
        viewModelScope.launch {
            try {
                chatRepository.deleteMessage(message.id)
                AppEventLogger.info(
                    component = TAG,
                    action = "delete_message",
                    details = "messageId=${message.id}, conversationId=${message.conversationId}"
                )
            } catch (e: Exception) {
                AppEventLogger.error(TAG, "delete_message_failed", "messageId=${message.id}", e)
            }
        }
    }

    fun editAndResendUserMessage(message: Message, newContent: String) {
        viewModelScope.launch {
            val trimmed = newContent.trim()
            if (trimmed.isEmpty() || !message.isUser) return@launch
            AppEventLogger.info(
                component = TAG,
                action = "edit_and_resend_started",
                details = "messageId=${message.id}, conversationId=${message.conversationId}, newLength=${trimmed.length}"
            )
            stopGeneration()
            // Wait for any in-flight turn to actually wind down before
            // rewriting history — otherwise its Complete handler could persist
            // the old response again after deleteMessagesAfter ran.
            generationJob?.cancelAndJoin()
            chatRepository.updateMessage(message.copy(content = trimmed))
            chatRepository.deleteMessagesAfter(message.conversationId, message.id)
            // Phase 1 (Subphase G): the prompt history just changed; engine KV
            // for tokens past the edited turn is now invalid.
            inferenceRepository.resetConversation()
            kotlinx.coroutines.delay(50)
            reProcessMessage(message.copy(content = trimmed))
        }
    }

    fun regenerateAssistantResponse(assistantMessage: Message) {
        if (assistantMessage.isUser) return
        viewModelScope.launch {
            AppEventLogger.info(
                component = TAG,
                action = "regenerate_assistant_requested",
                details = "assistantMessageId=${assistantMessage.id}, conversationId=${assistantMessage.conversationId}"
            )
            stopGeneration()
            // Settle the in-flight turn before reProcessMessage rewrites versions.
            generationJob?.cancelAndJoin()
            val all = chatRepository.getMessagesForConversation(assistantMessage.conversationId).first()
            val userMsg = all.takeWhile { it.id != assistantMessage.id }
                .lastOrNull { it.isUser } ?: return@launch
            // Don't delete: reProcessMessage now deactivates the old answer (keeps
            // it as a toggleable sibling), resets the KV cache, and excludes it
            // from the new turn's context.
            reProcessMessage(userMsg)
        }
    }

    /** Switch which response version of a turn is active. [dir] = -1 / +1. */
    fun switchMessageVersion(message: Message, dir: Int) {
        val parentId = message.parentMessageId ?: return
        viewModelScope.launch {
            val sibs = chatRepository.getMessagesForConversation(message.conversationId).first()
                .filter { it.parentMessageId == parentId }
                .sortedWith(compareBy({ it.timestamp }, { it.id }))
            if (sibs.size < 2) return@launch
            val cur = sibs.indexOfFirst { it.id == message.id }
            if (cur < 0) return@launch
            val next = (cur + dir).coerceIn(0, sibs.size - 1) // clamp, no wrap
            if (next == cur) return@launch
            AppEventLogger.info(
                component = TAG,
                action = "switch_message_version",
                details = "parentId=$parentId, from=$cur, to=$next, count=${sibs.size}"
            )
            chatRepository.setActiveVersion(parentId, sibs[next].id)
            // Active context changed → engine KV cache is now stale.
            inferenceRepository.resetConversation()
        }
    }
}
