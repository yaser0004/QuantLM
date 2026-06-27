package com.quantlm.yaser.domain.usecase

import android.util.Log
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.domain.model.GenerationState
import com.quantlm.yaser.domain.model.GenerationStats
import com.quantlm.yaser.domain.model.Message
import com.quantlm.yaser.domain.model.WebSearchResult
import com.quantlm.yaser.domain.model.WebSourceRef
import com.quantlm.yaser.domain.model.toRef
import com.quantlm.yaser.domain.repository.ChatRepository
import com.quantlm.yaser.domain.repository.InferenceRepository
import com.quantlm.yaser.domain.repository.WebSearchRepository
import com.quantlm.yaser.domain.util.DateTimeContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
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
    private val inferenceRepository: InferenceRepository,
    private val webSearchRepository: WebSearchRepository
) {

    companion object {
        private const val TAG = "SendMessageUseCase"
        // Sources injected into the prompt. Modest bump 3->4 for better grounding;
        // the larger prompt costs some prefill time, an accepted trade for accuracy.
        // Remaining sources are still shown in the UI.
        private const val MAX_PROMPT_SOURCES = 4
        // Upper word count for a message to qualify as a follow-up. A longer
        // message that merely opens with "this"/"that" is almost always a
        // self-contained question and must NOT pull in the previous topic.
        private const val FOLLOW_UP_MAX_WORDS = 6

        // History budgeting. Replaces the prior count-based MAX_HISTORY_MESSAGES.
        // Reserve tokens out of the model's context for the assistant reply, and
        // approximate token count as chars/CHARS_PER_TOKEN. CHARS_PER_TOKEN=4 is
        // a deliberately conservative estimate that holds up across Llama/Qwen/
        // Gemma BPE tokenizers without per-engine plumbing.
        private const val REPLY_RESERVE_TOKENS = 1024
        private const val CHARS_PER_TOKEN = 4
        // Fallback when the active engine doesn't report its context (e.g. no
        // model loaded yet). 4 k is the smallest production model we ship for.
        private const val DEFAULT_CONTEXT_TOKENS = 4096
        // Hard ceiling: a single message longer than this dominates context and
        // is almost certainly a paste of scraped/log content. Keep it but stop
        // it from monopolising prefill.
        private const val PER_MESSAGE_TOKEN_CAP = 2048

        // Web-augmentation caps. Keep the prompt block bounded so a long scraped
        // page can't push the model far past its context window before we even
        // start generating.
        private const val WEB_PER_SOURCE_CHAR_CAP = 1800
        private const val WEB_BLOCK_CHAR_CAP = 5000
        // Total time allowed for the search-and-scrape phase. The repo already
        // has its own internal budgets; this is the outer wall-clock guard so a
        // hung scrape can't turn into multi-minute "freeze when sending".
        // Bumped to 12 s so 4 concurrent scrapes (each up to the client's 8 s
        // callTimeout) aren't cut off just short of completing; the long-run
        // reassurance indicator covers the wait for the user.
        private const val WEB_SEARCH_TOTAL_TIMEOUT_MS = 12_000L
    }

    /**
     * Drop oldest turns until the rough token total of the kept history fits
     * inside [contextTokens] - [REPLY_RESERVE_TOKENS] - systemPromptTokens.
     * Always keeps the most recent message (the one we're about to answer).
     * Individual messages over [PER_MESSAGE_TOKEN_CAP] are tail-trimmed in place
     * rather than dropped, so a long user paste still informs the reply.
     */
    internal fun fitHistory(
        priorMessages: List<Message>,
        systemPromptChars: Int,
        contextTokens: Int,
    ): List<Message> {
        val effectiveContext = if (contextTokens > 0) contextTokens else DEFAULT_CONTEXT_TOKENS
        val systemPromptTokens = systemPromptChars / CHARS_PER_TOKEN
        var remaining = (effectiveContext - REPLY_RESERVE_TOKENS - systemPromptTokens).coerceAtLeast(512)

        val capped = priorMessages.map { msg ->
            val tokens = msg.content.length / CHARS_PER_TOKEN
            if (tokens > PER_MESSAGE_TOKEN_CAP) {
                msg.copy(content = msg.content.take(PER_MESSAGE_TOKEN_CAP * CHARS_PER_TOKEN))
            } else msg
        }

        val kept = ArrayDeque<Message>()
        for (msg in capped.asReversed()) {
            val cost = msg.content.length / CHARS_PER_TOKEN + 8 // small overhead for role tags
            if (cost > remaining) break
            kept.addFirst(msg)
            remaining -= cost
        }
        return kept
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
     * Append a reasoning directive (`/think` or `/no_think`) to the system
     * prompt. SmolLM3 and Qwen3 chat templates honor these flags to toggle
     * chain-of-thought; models that don't recognise them ignore them harmlessly.
     */
    private fun appendReasoningDirective(systemPrompt: String, directive: String): String {
        return if (systemPrompt.isBlank()) directive else "$systemPrompt\n\n$directive"
    }

    /**
     * Derives the web search query from the user's message.
     *
     * Only messages that *open with a word referencing the previous turn* (a
     * pronoun/demonstrative or a continuation word) are treated as follow-ups
     * and enriched with the previous user message. A standalone question — even
     * a short one like "Who is Ada Lovelace?" — is used verbatim.
     *
     * This is deliberately conservative: enriching a standalone question pulls
     * the previous topic into the search, which made unrelated follow-up
     * questions retrieve the *previous* answer's sources. Enrichment therefore
     * requires BOTH the follow-up prefix AND a short message (<= [FOLLOW_UP_MAX_WORDS]
     * words) — a long, self-contained question that merely opens with
     * "this"/"that" is used verbatim. Length only narrows the regex match; it is
     * never a follow-up signal on its own. [history] already includes the
     * just-inserted message.
     */
    internal fun buildSearchQuery(userMessage: String, history: List<Message>): String {
        val msg = userMessage.trim()
        if (msg.isEmpty()) return msg
        val followUpPattern = Regex(
            "^(it|its|it's|that|this|they|them|those|these|he|she|him|his|her|their|theirs|" +
                "and|also|but|what about|how about|what else|tell me more|more on|the same)\\b",
            RegexOption.IGNORE_CASE
        )
        val wordCount = msg.split(Regex("\\s+")).count { it.isNotEmpty() }
        val isFollowUp = wordCount <= FOLLOW_UP_MAX_WORDS && followUpPattern.containsMatchIn(msg)
        val baseQuery = if (!isFollowUp) {
            msg
        } else {
            val priorUser = history
                .lastOrNull { it.isUser && it.content.trim() != msg }
                ?.content?.trim()
            if (priorUser.isNullOrEmpty()) msg else "$priorUser $msg"
        }
        return augmentQueryRecency(baseQuery)
    }

    /**
     * Anchors time-sensitive searches to the current year so the search backend
     * surfaces fresher results. Deliberately conservative: only acts when the
     * query is explicitly framed as time-sensitive, appends the year alone
     * (never a full date, which over-constrains recall), and never adds a year
     * the query already contains.
     */
    internal fun augmentQueryRecency(query: String): String {
        val temporalKeyword = Regex(
            "\\b(latest|current|today|now|recent|this year|this month)\\b",
            RegexOption.IGNORE_CASE
        )
        if (!temporalKeyword.containsMatchIn(query)) return query
        if (Regex("\\b(19|20)\\d{2}\\b").containsMatchIn(query)) return query
        return "$query ${DateTimeContext.currentYear()}"
    }

    /**
     * Prepends a strict, citation-grounded `<web_search_context>` block to the
     * system prompt. The model is told to synthesize an organized answer using
     * ONLY the supplied sources — never raw-paste them, never guess. Handles the
     * empty/error case so a network failure degrades gracefully instead of
     * silently producing an ungrounded answer.
     */
    internal fun buildWebAugmentedSystemPrompt(
        baseSystemPrompt: String,
        result: WebSearchResult,
    ): String {
        val block = if (result.isEmpty) {
            buildString {
                appendLine("<web_search_context>")
                append("A web search was run for the user's question but returned no usable results")
                result.error?.let { append(" ($it)") }
                appendLine(".")
                appendLine(
                    "Begin your reply by telling the user plainly that live web information could " +
                        "not be retrieved for this question."
                )
                appendLine(
                    "Then answer from your own knowledge ONLY if you are genuinely confident, and " +
                        "clearly label that part as not web-verified. If you are not confident, say " +
                        "you do not know rather than guessing."
                )
                appendLine(
                    "Never invent facts, sources, or citations. You may suggest the user rephrase " +
                        "the question or run the search again."
                )
                append("</web_search_context>")
            }
        } else {
            buildString {
                appendLine("<web_search_context>")
                appendLine(
                    "Web search results for the user's question are below. Ground your answer " +
                        "in these sources. For the current date or time, use the <current_datetime> " +
                        "block provided elsewhere in this prompt — that block, not these sources, " +
                        "is the authority on \"now\"."
                )
                appendLine(
                    "Judge how recent each source is against the current date in the " +
                        "<current_datetime> block provided in this prompt; prefer the most " +
                        "up-to-date information and flag clearly outdated sources."
                )
                appendLine()
                appendLine("How to answer:")
                appendLine(
                    "- SYNTHESIZE a single, coherent, well-organized answer in your own words. " +
                        "Do NOT paste, quote, or stitch together raw passages from the sources."
                )
                appendLine(
                    "- Lead with a direct answer to the question, then supporting detail. " +
                        "Use short paragraphs, and markdown headings/bullets only when they genuinely aid clarity."
                )
                appendLine("- Cite every factual claim inline as [1], [2], etc., matching the numbered sources below.")
                appendLine(
                    "- If the sources do not contain the answer, say so plainly — do NOT use prior knowledge " +
                        "or guess. If sources disagree, point that out briefly."
                )
                appendLine(
                    "- If the sources predate or look outdated relative to the current date, say the " +
                        "information may be out of date. Do NOT fill gaps with guessed \"current\" facts."
                )
                appendLine()
                var remainingBlockBudget = WEB_BLOCK_CHAR_CAP
                result.sources.take(MAX_PROMPT_SOURCES).forEachIndexed { index, source ->
                    val raw = source.content.ifBlank { source.snippet }
                    val perSource = raw.take(WEB_PER_SOURCE_CHAR_CAP)
                    val body = perSource.take(remainingBlockBudget)
                    if (body.isEmpty()) return@forEachIndexed
                    appendLine("[${index + 1}] ${source.title} — ${source.domain}")
                    appendLine(body)
                    appendLine()
                    remainingBlockBudget -= body.length
                    if (remainingBlockBudget <= 0) return@forEachIndexed
                }
                append("</web_search_context>")
            }
        }
        return if (baseSystemPrompt.isBlank()) block else "$block\n\n$baseSystemPrompt"
    }

    /**
     * Build prompt using the appropriate chat template format.
     * This is model-agnostic and auto-detects the right format.
     */
    private fun buildPrompt(messages: List<Message>, systemPrompt: String): String {
        val template = inferenceRepository.getChatTemplate()
        val format = detectTemplateFormat(template)
        
        Log.d(TAG, "Using chat template format: $format (detected from: $template)")
        
        // Find the last model-change marker and only use messages after it.
        // This prevents the new model from having to prefill the entire old
        // conversation history as KV cache on its first turn, which causes
        // minutes-long latency. Marker rows are filtered out before building
        // the template string — they must never reach the LLM.
        val contextStart = messages.indexOfLast { it.isModelChangeMarker } + 1
        // Only ACTIVE response versions reach the model: inactive regenerate
        // siblings are excluded from context (markers stay active = 1, so the
        // contextStart math above is unaffected).
        val nonMarkerMessages = messages.drop(contextStart)
            .filter { !it.isModelChangeMarker && it.isActiveVersion }
        val priorMessages = if (nonMarkerMessages.size > 1) {
            nonMarkerMessages.dropLast(1)
        } else {
            emptyList()
        }
        // Budget history against the active model's actual context window
        // (queried lazily — engines that don't expose it fall back to a safe
        // default). Older turns that don't fit are dropped silently rather than
        // surfaced via a <context_note>; that note used to leak into replies
        // and confuse the model.
        //
        // Date/time rides on the CURRENT turn (ephemeral copy, never persisted)
        // instead of the system prompt: a per-minute timestamp at the head of
        // the prompt invalidated the llama KV prefix cache on every send. Its
        // chars are added to the system-prompt budget so history fitting still
        // accounts for them.
        val dateTimeBlock = DateTimeContext.currentDateTimeBlock()
        val activeContextTokens = inferenceRepository.getActiveContextTokens()
        val history = fitHistory(
            priorMessages,
            systemPrompt.length + dateTimeBlock.length,
            activeContextTokens
        )
        val currentMessage = nonMarkerMessages.lastOrNull()
            ?.let { appendDateTimeToCurrentTurn(it, dateTimeBlock) }

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
     * Ephemerally append the `<current_datetime>` block to the turn that is
     * about to be answered. Returns a copy — the persisted message is never
     * mutated. See [buildPrompt] for why this must not live in the system
     * prompt (KV prefix-cache invalidation). Internal for unit testing.
     */
    internal fun appendDateTimeToCurrentTurn(message: Message, dateTimeBlock: String): Message =
        if (message.isUser) {
            message.copy(content = "${message.content}\n\n$dateTimeBlock")
        } else {
            // A non-user "current" message is never rendered as the active turn
            // by the template builders; leave it untouched.
            message
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
            // Gemma has no explicit system role, so the system prompt is
            // prepended to the first user turn as plain text. It is NOT wrapped
            // in "[System: ...]": the system prompt can contain web-scraped
            // text with ']' characters or newlines, and a bracket wrapper would
            // be closed prematurely by them — corrupting the template and
            // leaking instructions into the user turn (a hallucination source).
            var systemUsed = false

            for (msg in history) {
                if (msg.isUser) {
                    append("<start_of_turn>user\n")
                    if (!systemUsed && systemPrompt.isNotEmpty()) {
                        append(systemPrompt)
                        append("\n\n")
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
                    append(systemPrompt)
                    append("\n\n")
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
        collapseWhitespace: Boolean,
        stopSequences: List<String>
    ): String {
        var cleaned = trimAtFirstStopToken(text, stopSequences)

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
        
        // Remove any remaining partial tokens at end.
        // `[^<>]` / `[^\[\]]` prevents the regex from spanning across legitimate
        // `<` or `[` characters earlier in the response — without those negations
        // any answer containing `5 < 10`, `<div>`, or `Section [4.2` got
        // truncated from the first stray bracket onward.
        cleaned = cleaned.replace(Regex("<[^<>]*$"), "")
        cleaned = cleaned.replace(Regex("\\[[^\\[\\]]*$"), "")

        val normalized = finaliseWhitespace(cleaned, collapseWhitespace)
        if (normalized.isNotBlank()) {
            return normalized
        }

        // Defensive fallback: if cleanup removes everything, preserve raw text so
        // we never persist an empty assistant message after successful generation.
        val fallback = finaliseWhitespace(trimAtFirstStopToken(text, stopSequences), collapseWhitespace)
        if (fallback.isNotBlank()) {
            Log.w(TAG, "cleanResponse removed all content; using uncleaned fallback text")
            return fallback
        }

        return normalized
    }

    private fun trimAtFirstStopToken(text: String, stopTokens: List<String>): String {
        if (stopTokens.isEmpty()) {
            return text
        }

        val firstStopIndex = stopTokens
            .asSequence()
            .filter { it.isNotBlank() }
            .map { token -> text.indexOf(token) }
            .filter { it >= 0 }
            .minOrNull()

        return if (firstStopIndex != null) {
            text.substring(0, firstStopIndex)
        } else {
            text
        }
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
        stopSequences: List<String> = emptyList(),
        systemPrompt: String,
        imagePaths: List<String> = emptyList(),
        audioPaths: List<String> = emptyList(),
        isRegeneration: Boolean = false,
        // Versioning: the user-message id this generated answer belongs to. On a
        // normal send it's the just-inserted user message; on a regeneration the
        // ViewModel passes the existing user message id so the new answer joins
        // that turn's sibling group.
        parentUserMessageId: Long? = null,
        reasoningEnabled: Boolean? = null,
        webSearchEnabled: Boolean = false
    ): Flow<GenerationState> = flow {
        
        Log.d(TAG, "=== SEND MESSAGE START: temp=$temperature, maxTokens=$maxTokens, isRegen=$isRegeneration ===")
        
        // Track generation timing
        val generationStartTime = System.currentTimeMillis()
        
        // Get model info for stats (fast - just reads cached value)
        val modelName = inferenceRepository.getLoadedModelName() ?: "Unknown"
        val backendLabel = inferenceRepository.getActiveBackendLabel()
        val modelFormatLabel = inferenceRepository.getActiveModelFormatLabel()
        
        // Only save user message if not a regeneration. Capture its id so the
        // assistant answer below can be tagged with its parent turn.
        var insertedUserId: Long? = null
        if (!isRegeneration) {
            val userMsg = Message(
                conversationId = conversationId,
                content = userMessage,
                isUser = true,
                imagePaths = imagePaths,
                audioPaths = audioPaths
            )
            insertedUserId = chatRepository.insertMessage(userMsg)
        }
        
        emit(GenerationState.Loading)
        
        // Determine the request modality (audio takes precedence over vision)
        val primaryImagePath = imagePaths.firstOrNull()
        val isVisionRequest = primaryImagePath != null && inferenceRepository.isVisionSupported()
        val primaryAudioPath = audioPaths.firstOrNull()
        val isAudioRequest = primaryAudioPath != null && inferenceRepository.isAudioSupported()

        // Reasoning directive (/think, /no_think) is injected into the system
        // prompt for GGUF/MediaPipe models; the LiteRT path uses the native
        // enable_thinking flag instead, so its directive is left out below.
        val usesNativeThinkingFlag = inferenceRepository.getActiveModelFormatLabel() == "LiteRT-LM"
        val effectiveSystemPrompt = when {
            reasoningEnabled == null || usesNativeThinkingFlag -> systemPrompt
            reasoningEnabled -> appendReasoningDirective(systemPrompt, "/think")
            else -> appendReasoningDirective(systemPrompt, "/no_think")
        }
        val reasoningOn = reasoningEnabled == true

        // Web Search: when the toggle is on, fetch fresh web context and inject
        // it into the system prompt (model-agnostic — works for every template).
        // When off, this block is skipped entirely and the app makes no network
        // request. It runs for vision/audio requests too: the web context is
        // appended to the same system prompt that the vision/audio prompt is
        // built from, so the model can use the web AND the attached media
        // together — '+' menu features compose freely.
        var webSources: List<WebSourceRef> = emptyList()
        var finalSystemPrompt = effectiveSystemPrompt
        if (webSearchEnabled) {
            // Surface a distinct status while the (potentially slow) web fetch runs.
            emit(GenerationState.SearchingWeb())
            val history = chatRepository.getMessagesForConversation(conversationId)
                .firstOrNull() ?: emptyList()
            val searchQuery = buildSearchQuery(userMessage, history)
            AppEventLogger.info(TAG, "web_search_start", "query=$searchQuery")
            // Outer wall-clock guard. WebSearchRepositoryImpl already has its own
            // per-page budgets, but a hung scrape used to compound into multi-minute
            // "freeze on send". On timeout we degrade to no-web-context rather than
            // making the user wait.
            val searchResult = withTimeoutOrNull(WEB_SEARCH_TOTAL_TIMEOUT_MS) {
                webSearchRepository.search(searchQuery, maxResults = 4)
            }
            if (searchResult != null) {
                finalSystemPrompt = buildWebAugmentedSystemPrompt(effectiveSystemPrompt, searchResult)
                webSources = searchResult.sources.take(MAX_PROMPT_SOURCES).map { it.toRef() }
            } else {
                AppEventLogger.warn(
                    TAG,
                    "web_search_timeout",
                    "budgetMs=$WEB_SEARCH_TOTAL_TIMEOUT_MS, query=$searchQuery"
                )
                // Degrade visibly instead of silently answering ungrounded:
                // tell the model the web couldn't be reached (so its reply says
                // so) and surface a brief status so the indicator doesn't just
                // vanish. The short pause lets the collector render this before
                // the engine's first token replaces it.
                finalSystemPrompt = buildWebAugmentedSystemPrompt(
                    effectiveSystemPrompt,
                    WebSearchResult(query = searchQuery, error = "search timed out"),
                )
                emit(GenerationState.SearchingWeb(message = "Web search took too long — answering without it…"))
                kotlinx.coroutines.delay(900)
            }
        }

        // NOTE: the <current_datetime> block is injected onto the CURRENT turn
        // inside buildPrompt(), NOT appended to the system prompt here. The
        // system prompt is the first thing in every template, and a
        // minute-resolution timestamp there changed the token prefix on every
        // send — defeating the native KV prefix cache (g_cached_tokens in
        // llama_jni.cpp) and forcing a full re-prefill of the entire
        // conversation each turn. Tail placement preserves the cached prefix
        // while the model still receives the exact current time every turn.

        // Estimate prompt tokens (rough approximation: ~4 chars per token)
        val estimatedPromptTokens = (userMessage.length + systemPrompt.length) / 4

        // Pre-token indicator: surface the dominant modality before handing
        // off to the engine. The engine call itself is opaque from here on —
        // model loads input, runs prefill, then starts streaming tokens. The
        // first `Generating` / `Thinking` emit from downstream replaces this
        // state automatically. Audio > vision > reasoning, mirroring the
        // routing precedence in `when` below; web search has its own slot
        // earlier and isn't overridden here.
        //
        // Combinations: reasoning composes with audio or vision via the
        // `alsoReasoning` flag (single bubble, dual icon). Audio + vision is
        // not a real engine combination — the routing chooses audio if both
        // are attached, so we never need a tri-modal indicator.
        when {
            isAudioRequest -> emit(
                GenerationState.TranscribingAudio(
                    message = if (reasoningOn) {
                        "Listening to your audio and preparing to think this through…"
                    } else {
                        "Listening to your audio, this may take a moment…"
                    },
                    alsoReasoning = reasoningOn,
                )
            )
            isVisionRequest -> emit(
                GenerationState.AnalyzingImage(
                    message = if (reasoningOn) {
                        "Analyzing the image and preparing to think this through…"
                    } else {
                        "Analyzing the image, this may take a moment…"
                    },
                    alsoReasoning = reasoningOn,
                )
            )
            reasoningOn -> emit(GenerationState.PreparingReasoning())
            else -> { /* Loading already covers it */ }
        }

        val responseFlow = when {
            isAudioRequest -> {
                // Audio model path
                val audioPrompt = buildVisionPrompt(userMessage, finalSystemPrompt, 1)

                inferenceRepository.generateStreamWithAudio(
                    prompt = audioPrompt,
                    audioPath = primaryAudioPath!!,
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
                    mirostatEta = mirostatEta,
                    stopSequences = stopSequences,
                    reasoningEnabled = reasoningOn
                )
            }
            isVisionRequest -> {
                // Vision model path
                val visionPrompt = buildVisionPrompt(userMessage, finalSystemPrompt, imagePaths.size)

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
                    mirostatEta = mirostatEta,
                    stopSequences = stopSequences,
                    reasoningEnabled = reasoningOn
                )
            }
            else -> {
                // Text-only path - use conversation history
                val messages = chatRepository.getMessagesForConversation(conversationId).firstOrNull() ?: emptyList()
                val formattedPrompt = buildPrompt(messages, finalSystemPrompt)

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
                    mirostatEta = mirostatEta,
                    stopSequences = stopSequences,
                    reasoningEnabled = reasoningOn
                )
            }
        }
        
        var fullResponse = ""
        var extractedThinking: String? = null
        var extractedThoughtSummary: String? = null
        // Real-time stats: count streaming events as token proxy and measure TTFT.
        // Each Generating emission from llama.cpp / MediaPipe corresponds to one
        // decoded token (or a small batch), making this far more accurate than the
        // chars/4 heuristic.
        var streamingTokenCount = 0
        var firstTokenTimeMs: Long? = null

        responseFlow.collect { state ->
            when (state) {
                is GenerationState.Generating -> {
                    streamingTokenCount++
                    if (firstTokenTimeMs == null) firstTokenTimeMs = System.currentTimeMillis()
                    fullResponse = state.currentText
                    val parsed = parseThinkingStream(fullResponse)
                    when {
                        parsed.thinkingClosed -> {
                            extractedThinking = parsed.thinking
                            extractedThoughtSummary = parsed.thoughtSummary
                            // Emit closed Thinking (with summary if parsed), then start streaming answer.
                            emit(GenerationState.Thinking(
                                content = parsed.thinking ?: "",
                                partial = false,
                                thoughtSummary = parsed.thoughtSummary,
                            ))
                            emit(GenerationState.Generating(parsed.remainder))
                        }
                        parsed.thinking != null -> {
                            emit(GenerationState.Thinking(parsed.thinking, partial = true))
                        }
                        else -> emit(GenerationState.Generating(fullResponse))
                    }
                }
                is GenerationState.Complete -> {
                    // Calculate generation time
                    val generationTimeMs = System.currentTimeMillis() - generationStartTime
                    
                    // Final pass — catches the case where the whole block lands in the Complete payload.
                    val completeParse = parseThinkingStream(state.text)
                    if (completeParse.thinkingClosed && completeParse.thinking != null) {
                        extractedThinking = completeParse.thinking
                        if (completeParse.thoughtSummary != null) extractedThoughtSummary = completeParse.thoughtSummary
                    }
                    val responseText = if (completeParse.thinkingClosed) completeParse.remainder else state.text
                    if (extractedThinking != null) {
                        AppEventLogger.info(
                            component = TAG,
                            action = "thinking_block_detected",
                            details = "model=$modelName, length=${extractedThinking!!.length}"
                        )
                    }
                    // Clean the final response text only (not during streaming)
                    fullResponse = cleanResponse(
                        text = responseText,
                        collapseWhitespace = true,
                        stopSequences = stopSequences
                    )
                    
                    // Token count: streaming emissions are throttled to a
                    // 400–1200 ms cadence, so the event count *undercounts*
                    // tokens for any non-trivial reply. Take whichever proxy is
                    // larger — the chars/4 estimate dominates for throttled
                    // streams; the event count covers very short replies.
                    val tokensGenerated = maxOf(streamingTokenCount, fullResponse.length / 4 + 1)
                    // TTFT: time from request start until first token arrived.
                    val ttft = firstTokenTimeMs?.minus(generationStartTime) ?: 0L
                    // Decode time: everything after the first token.
                    val decodeMs = (generationTimeMs - ttft).coerceAtLeast(1L)
                    val tokensPerSecond = if (decodeMs > 0) tokensGenerated * 1000f / decodeMs else 0f

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
                        imageCount = imagePaths.size,
                        backend = backendLabel,
                        modelFormat = modelFormatLabel,
                        timeToFirstTokenMs = ttft,
                        decodeTimeMs = decodeMs,
                        totalLatencyMs = generationTimeMs,
                    )
                    
                    Log.d(TAG, "=== COMPLETE: ${stats.formatGenerationTime()}, ${stats.formatTokensPerSecond()} ===")
                    
                    // Save assistant message with generation stats. Tag it with
                    // the user turn it answers (regeneration siblings share this
                    // parent) and mark it the active version.
                    val answeredUserId = if (isRegeneration) parentUserMessageId else insertedUserId
                    val assistantMsg = Message(
                        conversationId = conversationId,
                        content = fullResponse,
                        isUser = false,
                        generationStats = stats,
                        thinkingContent = extractedThinking,
                        thoughtSummary = extractedThoughtSummary,
                        sources = webSources,
                        parentMessageId = answeredUserId,
                        isActiveVersion = true,
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
    }.flowOn(Dispatchers.Default)
    // All pre-inference work (Room reads, prompt building, web search timeout,
    // template formatting) is heavy enough to visibly stall the UI when it runs
    // on viewModelScope's Main.immediate dispatcher. Push the whole flow to
    // Default; collectors on Main only see the GenerationState emissions.

    /**
     * Incremental parser for the structured reasoning format:
     *   <thinking>...</thinking>
     *   <thought_summary>...</thought_summary>
     *   [final answer]
     *
     * Falls back gracefully when the model uses the old <think>...</think> format or omits the
     * summary block entirely. Only handles a single thinking block at the head of the stream.
     */
    private fun parseThinkingStream(text: String): ThinkingParse {
        // Support both tag styles: <thinking> (new) and <think> (legacy)
        val openTag = when {
            text.contains("<thinking>") -> "<thinking>"
            text.contains("<think>") -> "<think>"
            else -> return ThinkingParse(thinking = null, thinkingClosed = false, remainder = text)
        }
        val closeTag = if (openTag == "<thinking>") "</thinking>" else "</think>"

        val startIdx = text.indexOf(openTag)
        val endIdx = text.indexOf(closeTag, startIndex = startIdx + openTag.length)
        if (endIdx < 0) {
            val partial = text.substring(startIdx + openTag.length)
            return ThinkingParse(thinking = partial, thinkingClosed = false, remainder = "")
        }

        val thinking = text.substring(startIdx + openTag.length, endIdx)
        val afterThinking = text.substring(endIdx + closeTag.length)

        // Try to extract <thought_summary>...</thought_summary> from what follows
        val summaryStart = afterThinking.indexOf("<thought_summary>")
        val summaryEnd = afterThinking.indexOf("</thought_summary>")
        val (thoughtSummary, remainder) = when {
            summaryStart >= 0 && summaryEnd > summaryStart -> {
                val sum = afterThinking.substring(summaryStart + "<thought_summary>".length, summaryEnd).trim()
                val rest = afterThinking.substring(summaryEnd + "</thought_summary>".length).trimStart()
                Pair(sum, rest)
            }
            else -> Pair(null, afterThinking.trimStart())
        }

        return ThinkingParse(
            thinking = thinking,
            thinkingClosed = true,
            thoughtSummary = thoughtSummary,
            remainder = remainder,
        )
    }

    private data class ThinkingParse(
        val thinking: String?,
        val thinkingClosed: Boolean,
        val thoughtSummary: String? = null,
        val remainder: String,
    )
}
