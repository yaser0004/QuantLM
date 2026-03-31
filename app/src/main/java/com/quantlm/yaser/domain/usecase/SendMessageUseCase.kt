package com.quantlm.yaser.domain.usecase

import android.util.Log
import com.quantlm.yaser.domain.model.GenerationState
import com.quantlm.yaser.domain.model.GenerationStats
import com.quantlm.yaser.domain.model.Message
import com.quantlm.yaser.domain.repository.ChatRepository
import com.quantlm.yaser.domain.repository.InferenceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

/**
 * Enum representing different chat template formats.
 * Each format defines how to structure prompts for different model families.
 */
enum class ChatTemplateFormat {
    PHI3,      // <|system|>...<|end|><|user|>...<|end|><|assistant|>
    CHATML,    // <|im_start|>system\n...<|im_end|><|im_start|>user\n...<|im_end|><|im_start|>assistant\n
    LLAMA3,    // <|begin_of_text|><|start_header_id|>system<|end_header_id|>...<|eot_id|>
    GEMMA,     // <start_of_turn>user\n...<end_of_turn><start_of_turn>model\n
    ALPACA,    // ### Instruction:\n...\n### Response:
    VICUNA,    // USER: ... ASSISTANT:
    GENERIC,   // Fallback simple format
    MEDIAPIPE_RAW  // Raw text for MediaPipe (handles its own formatting)
}

class SendMessageUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val inferenceRepository: InferenceRepository
) {
    
    companion object {
        private const val TAG = "SendMessageUseCase"
    }
    
    /**
     * Detect chat template format from the raw template string.
     * Maps model-specific templates to our enum format.
     */
    private fun detectTemplateFormat(template: String?): ChatTemplateFormat {
        if (template == null) return ChatTemplateFormat.MEDIAPIPE_RAW
        
        val lower = template.lowercase()
        return when {
            // Phi-3 family
            lower.contains("phi3") || lower.contains("phi-3") || 
            lower.contains("<|system|>") -> ChatTemplateFormat.PHI3
            
            // ChatML (Qwen, many others)
            lower.contains("chatml") || lower.contains("qwen") ||
            lower.contains("<|im_start|>") -> ChatTemplateFormat.CHATML
            
            // Llama 3 family
            lower.contains("llama3") || lower.contains("llama-3") ||
            lower.contains("<|begin_of_text|>") -> ChatTemplateFormat.LLAMA3
            
            // Gemma family
            lower.contains("gemma") || lower.contains("<start_of_turn>") -> ChatTemplateFormat.GEMMA
            
            // Alpaca format
            lower.contains("alpaca") || lower.contains("### instruction") -> ChatTemplateFormat.ALPACA
            
            // Vicuna format
            lower.contains("vicuna") || lower.contains("user:") -> ChatTemplateFormat.VICUNA
            
            else -> ChatTemplateFormat.GENERIC
        }
    }
    
    /**
     * Build prompt using the appropriate chat template format.
     * This is model-agnostic and auto-detects the right format.
     */
    private fun buildPrompt(messages: List<Message>, systemPrompt: String): String {
        val template = inferenceRepository.getChatTemplate()
        val format = detectTemplateFormat(template)
        
        Log.d(TAG, "Using chat template format: $format (detected from: $template)")
        
        val history = if (messages.size > 1) {
            messages.dropLast(1).takeLast(10)
        } else {
            emptyList()
        }
        val currentMessage = messages.lastOrNull()
        
        return when (format) {
            ChatTemplateFormat.PHI3 -> buildPhi3Prompt(history, currentMessage, systemPrompt)
            ChatTemplateFormat.CHATML -> buildChatMLPrompt(history, currentMessage, systemPrompt)
            ChatTemplateFormat.LLAMA3 -> buildLlama3Prompt(history, currentMessage, systemPrompt)
            ChatTemplateFormat.GEMMA -> buildGemmaPrompt(history, currentMessage, systemPrompt)
            ChatTemplateFormat.ALPACA -> buildAlpacaPrompt(history, currentMessage, systemPrompt)
            ChatTemplateFormat.VICUNA -> buildVicunaPrompt(history, currentMessage, systemPrompt)
            ChatTemplateFormat.GENERIC -> buildGenericPrompt(history, currentMessage, systemPrompt)
            ChatTemplateFormat.MEDIAPIPE_RAW -> buildMediaPipeRawPrompt(history, currentMessage, systemPrompt)
        }
    }
    
    /**
     * MediaPipe Raw: Send text directly without chat template wrapping.
     * MediaPipe's addQueryChunk() handles conversation formatting internally.
     * We just provide the latest user message (with optional system context).
     */
    private fun buildMediaPipeRawPrompt(history: List<Message>, current: Message?, systemPrompt: String): String {
        return buildString {
            if (systemPrompt.isNotEmpty()) {
                append(systemPrompt)
                append("\n\n")
            }
            // Only include the latest user message – MediaPipe manages its own
            // session-level conversation context through addQueryChunk.
            if (current != null && current.isUser) {
                append(current.content)
            }
        }
    }
    
    // Phi-3: <|system|>...<|end|><|user|>...<|end|><|assistant|>
    private fun buildPhi3Prompt(history: List<Message>, current: Message?, systemPrompt: String): String {
        return buildString {
            append("<|system|>")
            append(systemPrompt)
            append("<|end|>\n")
            
            for (msg in history) {
                if (msg.isUser) {
                    append("<|user|>")
                    append(msg.content)
                    append("<|end|>\n")
                } else {
                    append("<|assistant|>")
                    append(msg.content)
                    append("<|end|>\n")
                }
            }
            
            if (current != null && current.isUser) {
                append("<|user|>")
                append(current.content)
                append("<|end|>\n")
            }
            
            append("<|assistant|>")
        }
    }
    
    // ChatML: <|im_start|>system\n...<|im_end|>\n<|im_start|>user\n...<|im_end|>\n<|im_start|>assistant\n
    private fun buildChatMLPrompt(history: List<Message>, current: Message?, systemPrompt: String): String {
        return buildString {
            append("<|im_start|>system\n")
            append(systemPrompt)
            append("<|im_end|>\n")
            
            for (msg in history) {
                if (msg.isUser) {
                    append("<|im_start|>user\n")
                    append(msg.content)
                    append("<|im_end|>\n")
                } else {
                    append("<|im_start|>assistant\n")
                    append(msg.content)
                    append("<|im_end|>\n")
                }
            }
            
            if (current != null && current.isUser) {
                append("<|im_start|>user\n")
                append(current.content)
                append("<|im_end|>\n")
            }
            
            append("<|im_start|>assistant\n")
        }
    }
    
    // Llama 3: <|begin_of_text|><|start_header_id|>system<|end_header_id|>\n...<|eot_id|>
    private fun buildLlama3Prompt(history: List<Message>, current: Message?, systemPrompt: String): String {
        return buildString {
            append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
            append(systemPrompt)
            append("<|eot_id|>")
            
            for (msg in history) {
                if (msg.isUser) {
                    append("<|start_header_id|>user<|end_header_id|>\n\n")
                    append(msg.content)
                    append("<|eot_id|>")
                } else {
                    append("<|start_header_id|>assistant<|end_header_id|>\n\n")
                    append(msg.content)
                    append("<|eot_id|>")
                }
            }
            
            if (current != null && current.isUser) {
                append("<|start_header_id|>user<|end_header_id|>\n\n")
                append(current.content)
                append("<|eot_id|>")
            }
            
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }
    }
    
    // Gemma: <start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n
    private fun buildGemmaPrompt(history: List<Message>, current: Message?, systemPrompt: String): String {
        return buildString {
            // Gemma doesn't have explicit system role, prepend to first user message
            var systemUsed = false
            
            for (msg in history) {
                if (msg.isUser) {
                    append("<start_of_turn>user\n")
                    if (!systemUsed && systemPrompt.isNotEmpty()) {
                        append("[System: ")
                        append(systemPrompt)
                        append("]\n\n")
                        systemUsed = true
                    }
                    append(msg.content)
                    append("<end_of_turn>\n")
                } else {
                    append("<start_of_turn>model\n")
                    append(msg.content)
                    append("<end_of_turn>\n")
                }
            }
            
            if (current != null && current.isUser) {
                append("<start_of_turn>user\n")
                if (!systemUsed && systemPrompt.isNotEmpty()) {
                    append("[System: ")
                    append(systemPrompt)
                    append("]\n\n")
                }
                append(current.content)
                append("<end_of_turn>\n")
            }
            
            append("<start_of_turn>model\n")
        }
    }
    
    // Alpaca: ### Instruction:\n...\n\n### Response:
    private fun buildAlpacaPrompt(history: List<Message>, current: Message?, systemPrompt: String): String {
        return buildString {
            if (systemPrompt.isNotEmpty()) {
                append("### System:\n")
                append(systemPrompt)
                append("\n\n")
            }
            
            for (msg in history) {
                if (msg.isUser) {
                    append("### Instruction:\n")
                    append(msg.content)
                    append("\n\n")
                } else {
                    append("### Response:\n")
                    append(msg.content)
                    append("\n\n")
                }
            }
            
            if (current != null && current.isUser) {
                append("### Instruction:\n")
                append(current.content)
                append("\n\n")
            }
            
            append("### Response:\n")
        }
    }
    
    // Vicuna: USER: ... ASSISTANT:
    private fun buildVicunaPrompt(history: List<Message>, current: Message?, systemPrompt: String): String {
        return buildString {
            if (systemPrompt.isNotEmpty()) {
                append(systemPrompt)
                append("\n\n")
            }
            
            for (msg in history) {
                if (msg.isUser) {
                    append("USER: ")
                    append(msg.content)
                    append("\n")
                } else {
                    append("ASSISTANT: ")
                    append(msg.content)
                    append("\n")
                }
            }
            
            if (current != null && current.isUser) {
                append("USER: ")
                append(current.content)
                append("\n")
            }
            
            append("ASSISTANT: ")
        }
    }
    
    // Generic fallback
    private fun buildGenericPrompt(history: List<Message>, current: Message?, systemPrompt: String): String {
        return buildString {
            if (systemPrompt.isNotEmpty()) {
                append("System: ")
                append(systemPrompt)
                append("\n\n")
            }
            
            for (msg in history) {
                if (msg.isUser) {
                    append("User: ")
                    append(msg.content)
                    append("\n")
                } else {
                    append("Assistant: ")
                    append(msg.content)
                    append("\n")
                }
            }
            
            if (current != null && current.isUser) {
                append("User: ")
                append(current.content)
                append("\n")
            }
            
            append("Assistant: ")
        }
    }
    
    /**
     * Build prompt for vision models - uses same template detection.
     * @param userMessage The user's question/instruction about the image(s)
     * @param systemPrompt System prompt for context
     * @param imageCount Number of images attached (for multi-image context)
     */
    private fun buildVisionPrompt(userMessage: String, systemPrompt: String, imageCount: Int = 1): String {
        // For multi-image scenarios, add context to the prompt
        val contextualMessage = when {
            imageCount > 1 -> "[Viewing $imageCount images] $userMessage"
            else -> userMessage
        }
        
        val dummyMessage = Message(
            conversationId = 0,
            content = contextualMessage,
            isUser = true
        )
        return buildPrompt(listOf(dummyMessage), systemPrompt)
    }
    
    /**
     * Clean response text by removing control tokens.
     * Model-agnostic - handles all common formats.
     */
    private fun cleanResponse(
        text: String,
        collapseWhitespace: Boolean
    ): String {
        var cleaned = text

        // Fast path: if no control tokens present
        if (!cleaned.contains("<") && !cleaned.contains("###")) {
            return finaliseWhitespace(cleaned, collapseWhitespace)
        }

        // Generic regex to remove all control tokens:
        // - <|...|> format (Phi-3, ChatML, Llama 3)
        // - <start_of_turn>, <end_of_turn> (Gemma)
        // - ### markers (Alpaca)
        // - [/INST], </s> (Llama 2, Mistral)
        
        // Remove <|...|> style tokens (complete and partial)
        cleaned = cleaned.replace(Regex("<\\|[^>|]*\\|?>?"), "")
        
        // Remove <..._of_...> style tokens (Gemma)
        cleaned = cleaned.replace(Regex("</?(?:start|end)_of_(?:turn|text)>"), "")
        
        // Remove [INST], [/INST] style tokens
        cleaned = cleaned.replace(Regex("\\[/?INST\\]"), "")
        
        // Remove </s> end token
        cleaned = cleaned.replace("</s>", "")
        
        // Remove ### markers if they appear at start of response
        cleaned = cleaned.replace(Regex("^###\\s*(?:Response|Assistant|Output)?:?\\s*"), "")

        // Remove plain role prefixes that may remain after token stripping.
        cleaned = cleaned.replace(Regex("^\\s*(?:assistant|model)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("^\\s*(?:assistant|model)\\s*\\n+", RegexOption.IGNORE_CASE), "")
        
        // Remove any remaining partial tokens at end
        cleaned = cleaned.replace(Regex("<[^>]*$"), "")
        cleaned = cleaned.replace(Regex("\\[[^\\]]*$"), "")

        val normalized = finaliseWhitespace(cleaned, collapseWhitespace)
        if (normalized.isNotBlank()) {
            return normalized
        }

        // Defensive fallback: if cleanup removes everything, preserve raw text so
        // we never persist an empty assistant message after successful generation.
        val fallback = finaliseWhitespace(text, collapseWhitespace)
        if (fallback.isNotBlank()) {
            Log.w(TAG, "cleanResponse removed all content; using uncleaned fallback text")
            return fallback
        }

        return normalized
    }

    private fun finaliseWhitespace(text: String, collapseWhitespace: Boolean): String {
        var result = text
        if (collapseWhitespace) {
            result = result.replace("\r\n", "\n")
            result = result.replace(Regex("[ \t]+"), " ")
            result = result.replace(Regex("\n{3,}"), "\n\n")
            result = result.lineSequence()
                .joinToString("\n") { it.trimEnd() }
            result = result.trim()
        } else {
            result = result.trimEnd()
        }
        return result
    }
    
    operator fun invoke(
        conversationId: Long,
        userMessage: String,
        temperature: Float,
        maxTokens: Int,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        repeatLastN: Int,
        minP: Float,
        tfsZ: Float,
        typicalP: Float,
        mirostat: Int,
        mirostatTau: Float,
        mirostatEta: Float,
        systemPrompt: String,
        imagePaths: List<String> = emptyList(),
        isRegeneration: Boolean = false
    ): Flow<GenerationState> = flow {
        
        Log.d(TAG, "=== SEND MESSAGE START: temp=$temperature, maxTokens=$maxTokens, isRegen=$isRegeneration ===")
        
        // Track generation timing
        val generationStartTime = System.currentTimeMillis()
        
        // Get model info for stats (fast - just reads cached value)
        val modelName = inferenceRepository.getLoadedModelName() ?: "Unknown"
        
        // Only save user message if not a regeneration
        if (!isRegeneration) {
            val userMsg = Message(
                conversationId = conversationId,
                content = userMessage,
                isUser = true,
                imagePaths = imagePaths
            )
            chatRepository.insertMessage(userMsg)
        }
        
        emit(GenerationState.Loading)
        
        // Determine if this is a vision request
        val primaryImagePath = imagePaths.firstOrNull()
        val isVisionRequest = primaryImagePath != null && inferenceRepository.isVisionSupported()
        
        // Estimate prompt tokens (rough approximation: ~4 chars per token)
        val estimatedPromptTokens = (userMessage.length + systemPrompt.length) / 4
        
        val responseFlow = if (isVisionRequest) {
            // Vision model path
            val visionPrompt = buildVisionPrompt(userMessage, systemPrompt, imagePaths.size)
            
            inferenceRepository.generateStreamWithImage(
                prompt = visionPrompt,
                imagePath = primaryImagePath!!,
                temperature = temperature,
                maxTokens = maxTokens,
                topP = topP,
                topK = topK,
                repeatPenalty = repeatPenalty,
                repeatLastN = repeatLastN,
                minP = minP,
                tfsZ = tfsZ,
                typicalP = typicalP,
                mirostat = mirostat,
                mirostatTau = mirostatTau,
                mirostatEta = mirostatEta
            )
        } else {
            // Text-only path - use conversation history
            val messages = chatRepository.getMessagesForConversation(conversationId).firstOrNull() ?: emptyList()
            val formattedPrompt = buildPrompt(messages, systemPrompt)
            
            inferenceRepository.generateStream(
                prompt = formattedPrompt,
                temperature = temperature,
                maxTokens = maxTokens,
                topP = topP,
                topK = topK,
                repeatPenalty = repeatPenalty,
                repeatLastN = repeatLastN,
                minP = minP,
                tfsZ = tfsZ,
                typicalP = typicalP,
                mirostat = mirostat,
                mirostatTau = mirostatTau,
                mirostatEta = mirostatEta
            )
        }
        
        var fullResponse = ""
        
        responseFlow.collect { state ->
            when (state) {
                is GenerationState.Generating -> {
                    // Just pass through the text during generation - skip cleaning for performance
                    fullResponse = state.currentText
                    emit(GenerationState.Generating(fullResponse))
                }
                is GenerationState.Complete -> {
                    // Calculate generation time
                    val generationTimeMs = System.currentTimeMillis() - generationStartTime
                    
                    // Clean the final response text only (not during streaming)
                    fullResponse = cleanResponse(
                        text = state.text,
                        collapseWhitespace = true
                    )
                    
                    // Estimate tokens generated (rough: ~4 chars per token)
                    val tokensGenerated = fullResponse.length / 4 + 1
                    val tokensPerSecond = if (generationTimeMs > 0) {
                        tokensGenerated * 1000f / generationTimeMs
                    } else 0f
                    
                    // Get memory usage
                    val runtime = Runtime.getRuntime()
                    val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                    
                    // Build generation stats
                    val stats = GenerationStats(
                        generationTimeMs = generationTimeMs,
                        tokensGenerated = tokensGenerated,
                        tokensPerSecond = tokensPerSecond,
                        promptTokens = estimatedPromptTokens,
                        totalTokens = estimatedPromptTokens + tokensGenerated,
                        memoryUsedBytes = usedMemory,
                        modelName = modelName,
                        temperature = temperature,
                        topP = topP,
                        topK = topK,
                        maxTokens = maxTokens,
                        wasVisionRequest = isVisionRequest,
                        imageCount = imagePaths.size
                    )
                    
                    Log.d(TAG, "=== COMPLETE: ${stats.formatGenerationTime()}, ${stats.formatTokensPerSecond()} ===")
                    
                    // Save assistant message with generation stats
                    val assistantMsg = Message(
                        conversationId = conversationId,
                        content = fullResponse,
                        isUser = false,
                        generationStats = stats
                    )
                    chatRepository.insertMessage(assistantMsg)
                    
                    // Update conversation timestamp to reflect recent activity
                    chatRepository.updateConversationTimestamp(conversationId)
                    
                    emit(GenerationState.Complete(fullResponse))
                }
                is GenerationState.Error -> {
                    Log.e(TAG, "Error: ${state.message}")
                    emit(state)
                }
                else -> emit(state)
            }
        }
    }
}
