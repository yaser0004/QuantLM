package com.quantlm.yaser.presentation.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quantlm.yaser.data.audio.AudioInputManager
import com.quantlm.yaser.data.audio.AudioInputState
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.data.service.InferenceService
import com.quantlm.yaser.domain.model.AvailableModels
import com.quantlm.yaser.domain.model.DownloadableModel
import com.quantlm.yaser.domain.model.GenerationState
import com.quantlm.yaser.domain.model.Message
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    val audioInputManager: AudioInputManager
) : ViewModel() {
    
    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId = _currentConversationId.asStateFlow()
    
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages = _messages.asStateFlow()
    
    private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val generationState = _generationState.asStateFlow()
    
    private val _inputText = MutableStateFlow("")
    val inputText = _inputText.asStateFlow()
    
    // Selected images for vision models (supports multiple)
    private val _selectedImages = MutableStateFlow<List<String>>(emptyList())
    val selectedImages = _selectedImages.asStateFlow()
    
    // Backward compatibility - get first image path
    val selectedImagePath = _selectedImages.map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    
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
    
    private val _conversations = MutableStateFlow<List<com.quantlm.yaser.domain.model.Conversation>>(emptyList())
    val conversations = _conversations.asStateFlow()

    val lastMessagePreviews = chatRepository.observeLastMessagesPerConversation()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var messagesJob: Job? = null
    
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
        
        // Observe loaded model changes to update vision support state
        viewModelScope.launch {
            loadedModel.collect { model ->
                // Update vision support when model changes
                _isVisionSupported.value = model?.isVisionModel == true && 
                    inferenceRepository.isVisionSupported()
                Log.d(TAG, "Model changed: ${model?.name}, vision supported: ${_isVisionSupported.value}")
                AppEventLogger.info(
                    component = TAG,
                    action = "loaded_model_changed",
                    details = "model=${model?.name ?: "none"}, visionSupported=${_isVisionSupported.value}"
                )
                
                // Update vision model state
                updateVisionModelState()
            }
        }

        loadAllConversations()
        updateVisionModelState()

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
            
            // Check model file
            val isModelDownloaded = if (modelFile.exists()) {
                if (visionModel.fileSize > 0) {
                    kotlin.math.abs(modelFile.length() - visionModel.fileSize).toFloat() / visionModel.fileSize.toFloat() < 0.01f
                } else {
                    modelFile.length() > 0
                }
            } else false
            
            // Check mmproj file
            val isMmprojDownloaded = if (mmprojFile?.exists() == true) {
                if (visionModel.mmprojFileSize > 0) {
                    kotlin.math.abs(mmprojFile.length() - visionModel.mmprojFileSize).toFloat() / visionModel.mmprojFileSize.toFloat() < 0.01f
                } else {
                    mmprojFile.length() > 0
                }
            } else false
            
            isModelDownloaded && isMmprojDownloaded
        }
        
        // Default vision model to suggest if none downloaded (smallest one first)
        val defaultVisionModel = allVisionModels.minByOrNull { it.fileSize } ?: AvailableModels.SMOLVLM2_2_2B_Q4_K_M
        
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
                allVision.minByOrNull { it.fileSize } ?: AvailableModels.SMOLVLM2_2_2B_Q4_K_M
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
                    _messages.value = messages
                }
        }
    }
    
    fun sendMessage() {
        val text = _inputText.value.trim()
        
        if (text.isEmpty()) {
            AppEventLogger.debug(component = TAG, action = "send_message_ignored_empty")
            return
        }
        
        // Prevent sending if already generating
        val currentState = _generationState.value
        if (currentState is GenerationState.Generating || currentState is GenerationState.Loading) {
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
        
        viewModelScope.launch {
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
            _activeQuickAction.value = null
            _selectedTemplate.value = null
            
            // Use current settings (template system prompt takes precedence if set)
            val settings = _generationSettings.value
            
            try {
                sendMessageUseCase(
                    conversationId = conversationId,
                    userMessage = finalUserMessage,
                    temperature = template?.suggestedTemperature ?: settings.temperature,
                    maxTokens = template?.suggestedMaxTokens ?: settings.maxTokens,
                    topP = settings.topP,
                    topK = settings.topK,
                    systemPrompt = templateSystemPrompt ?: settings.systemPrompt,
                    imagePaths = imagePaths
                ).collect { state ->
                    _generationState.value = state
                    
                    // Reset to idle after completion or error
                    when (state) {
                        is GenerationState.Complete -> {
                            AppEventLogger.info(
                                component = TAG,
                                action = "send_message_completed",
                                details = "conversationId=$conversationId, responseLength=${state.text.length}"
                            )
                            // Stop foreground service
                            InferenceService.stopInference(application)
                            // Auto-generate chat name from first message
                            if (isFirstMessage) {
                                updateConversationTitle(conversationId, userText)
                            }
                            kotlinx.coroutines.delay(100)
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
                            _generationState.value = GenerationState.Idle
                        }
                        else -> { /* keep state */ }
                    }
                }
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
                _generationState.value = GenerationState.Idle
            }
        }
    }
    
    /**
     * Re-process a user message to get a new response
     * This uses the message content and any attached images to generate a fresh response
     */
    fun reProcessMessage(message: Message) {
        if (!message.isUser) return
        
        // Prevent if already generating
        val currentState = _generationState.value
        if (currentState is GenerationState.Generating || currentState is GenerationState.Loading) {
            AppEventLogger.warn(
                component = TAG,
                action = "regenerate_blocked_busy",
                details = "state=${currentState::class.simpleName}"
            )
            return
        }
        
        // Start foreground service to keep inference running in background
        InferenceService.startInference(application)
        
        viewModelScope.launch {
            val conversationId = _currentConversationId.value ?: return@launch
            val settings = _generationSettings.value
            AppEventLogger.info(
                component = TAG,
                action = "regenerate_started",
                details = "conversationId=$conversationId, messageId=${message.id}, imageCount=${message.imagePaths.size}"
            )
            
            try {
                sendMessageUseCase(
                    conversationId = conversationId,
                    userMessage = message.content,
                    temperature = settings.temperature,
                    maxTokens = settings.maxTokens,
                    topP = settings.topP,
                    topK = settings.topK,
                    systemPrompt = settings.systemPrompt,
                    imagePaths = message.imagePaths,
                    isRegeneration = true // Don't create new user message
                ).collect { state ->
                    _generationState.value = state
                    
                    when (state) {
                        is GenerationState.Complete -> {
                            AppEventLogger.info(
                                component = TAG,
                                action = "regenerate_completed",
                                details = "conversationId=$conversationId, responseLength=${state.text.length}"
                            )
                            InferenceService.stopInference(application)
                            kotlinx.coroutines.delay(100)
                            _generationState.value = GenerationState.Idle
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
            chatRepository.deleteConversation(conversationId)
            
            // If we deleted the current conversation, create a new one
            if (_currentConversationId.value == conversationId) {
                startNewConversation()
            }
        }
    }

    fun renameConversation(conversationId: Long, newTitle: String) {
        viewModelScope.launch {
            val conv = chatRepository.getConversation(conversationId) ?: return@launch
            val t = newTitle.trim()
            if (t.isEmpty()) return@launch
            chatRepository.updateConversation(
                conv.copy(title = t, updatedAt = System.currentTimeMillis())
            )
            AppEventLogger.info(
                component = TAG,
                action = "rename_conversation",
                details = "conversationId=$conversationId, titleLength=${t.length}"
            )
        }
    }

    fun deleteMessage(message: Message) {
        viewModelScope.launch {
            chatRepository.deleteMessage(message.id)
            AppEventLogger.info(
                component = TAG,
                action = "delete_message",
                details = "messageId=${message.id}, conversationId=${message.conversationId}"
            )
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
            chatRepository.updateMessage(message.copy(content = trimmed))
            chatRepository.deleteMessagesAfter(message.conversationId, message.id)
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
            val all = chatRepository.getMessagesForConversation(assistantMessage.conversationId).first()
            val userMsg = all.takeWhile { it.id != assistantMessage.id }
                .lastOrNull { it.isUser } ?: return@launch
            chatRepository.deleteMessage(assistantMessage.id)
            reProcessMessage(userMsg)
        }
    }
}
