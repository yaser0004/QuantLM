package com.quantlm.yaser.domain.usecase

import com.quantlm.yaser.domain.model.Conversation
import com.quantlm.yaser.domain.model.GenerationState
import com.quantlm.yaser.domain.model.Message
import com.quantlm.yaser.domain.model.WebSearchResult
import com.quantlm.yaser.domain.model.WebSource
import com.quantlm.yaser.domain.repository.ChatRepository
import com.quantlm.yaser.domain.repository.InferenceRepository
import com.quantlm.yaser.domain.repository.WebSearchRepository
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the pure prompt-composition helpers of [SendMessageUseCase].
 *
 * These lock in the web-search / date-time fixes:
 *  - the current date is injected exactly once (via the separate
 *    `<current_datetime>` block), never restated inside `<web_search_context>`;
 *  - the web-search block tells the model to flag stale sources rather than
 *    fabricate "current" facts;
 *  - an empty search result yields an explicit retrieval-failure directive
 *    instead of a silent ungrounded answer;
 *  - only short, follow-up-prefixed messages pull the previous topic into the
 *    search query — long self-contained questions are searched verbatim.
 *
 * The helpers do not touch the injected repositories, so bare stub
 * implementations are sufficient to construct the use case.
 */
class SendMessageUseCaseTest {

    private val useCase = SendMessageUseCase(
        StubChatRepository(),
        StubInferenceRepository(),
        StubWebSearchRepository(),
    )

    private fun userMessage(text: String) =
        Message(conversationId = 0L, content = text, isUser = true)

    private fun resultWithSources() = WebSearchResult(
        query = "q",
        sources = listOf(
            WebSource(
                title = "Example",
                url = "https://example.com/a",
                domain = "example.com",
                snippet = "snippet",
                content = "some scraped content",
                trustScore = 80,
            )
        ),
    )

    // --- B2: single date injection ----------------------------------------

    @Test
    fun webAugmentedPrompt_withSources_doesNotRestateTheDate() {
        val prompt = useCase.buildWebAugmentedSystemPrompt("base", resultWithSources())
        assertFalse(
            "web_search_context must not contain a literal date statement",
            prompt.contains("Today's date is"),
        )
    }

    @Test
    fun webAugmentedPrompt_emptyResult_doesNotRestateTheDate() {
        val prompt = useCase.buildWebAugmentedSystemPrompt(
            "base",
            WebSearchResult(query = "q", error = "no network"),
        )
        assertFalse(prompt.contains("Today's date is"))
    }

    // --- B3: temporal-conflict guidance -----------------------------------

    @Test
    fun webAugmentedPrompt_withSources_includesStalenessGuidance() {
        val prompt = useCase.buildWebAugmentedSystemPrompt("base", resultWithSources())
        assertTrue(
            "must instruct the model to flag outdated sources",
            prompt.contains("out of date"),
        )
    }

    // --- B4: empty-search anti-loop guidance ------------------------------

    @Test
    fun webAugmentedPrompt_emptyResult_includesRetrievalFailureDirective() {
        val prompt = useCase.buildWebAugmentedSystemPrompt(
            "base",
            WebSearchResult(query = "q", error = "no network"),
        )
        assertTrue(prompt.contains("could not be retrieved"))
        assertTrue(prompt.contains("not web-verified"))
    }

    // --- B5: follow-up query enrichment guard -----------------------------

    @Test
    fun buildSearchQuery_shortFollowUp_isEnrichedWithPriorTopic() {
        val history = listOf(
            userMessage("the quantum computer"),
            userMessage("tell me more"),
        )
        val query = useCase.buildSearchQuery("tell me more", history)
        assertEquals("the quantum computer tell me more", query)
    }

    @Test
    fun buildSearchQuery_longSelfContainedQuestion_isVerbatim() {
        val history = listOf(userMessage("the quantum computer"))
        val message = "This particular sorting algorithm has terrible worst case behavior"
        val query = useCase.buildSearchQuery(message, history)
        assertEquals(message, query)
    }

    @Test
    fun buildSearchQuery_nonFollowUp_isVerbatim() {
        val query = useCase.buildSearchQuery("Who is Ada Lovelace", emptyList())
        assertEquals("Who is Ada Lovelace", query)
    }

    // --- augmentQueryRecency ----------------------------------------------

    @Test
    fun augmentQueryRecency_temporalQuery_appendsYear() {
        val query = useCase.augmentQueryRecency("latest news")
        assertTrue("expected a year appended", query.startsWith("latest news "))
        assertTrue(Regex("\\b(19|20)\\d{2}\\b").containsMatchIn(query))
    }

    @Test
    fun augmentQueryRecency_queryWithExplicitYear_isUnchanged() {
        assertEquals("latest news 2024", useCase.augmentQueryRecency("latest news 2024"))
    }

    @Test
    fun augmentQueryRecency_nonTemporalQuery_isUnchanged() {
        assertEquals("explain recursion", useCase.augmentQueryRecency("explain recursion"))
    }

    // --- current-turn datetime injection ------------------------------------
    // The datetime block must ride on the CURRENT turn, never the system
    // prompt: a per-minute timestamp at the prompt head invalidates the llama
    // KV prefix cache and forces a full conversation re-prefill every send.

    @Test
    fun appendDateTimeToCurrentTurn_appendsToTail_withoutMutatingOriginal() {
        val original = userMessage("what's the time?")
        val block = "<current_datetime>NOW</current_datetime>"
        val injected = useCase.appendDateTimeToCurrentTurn(original, block)
        assertEquals("what's the time?\n\n$block", injected.content)
        assertEquals("what's the time?", original.content) // copy, not mutation
    }

    @Test
    fun appendDateTimeToCurrentTurn_leavesNonUserMessageUntouched() {
        val assistant = Message(conversationId = 0L, content = "earlier reply", isUser = false)
        val injected = useCase.appendDateTimeToCurrentTurn(assistant, "<current_datetime/>")
        assertEquals("earlier reply", injected.content)
    }

    @Test
    fun webAugmentedSystemPrompt_neverContainsDateTimeBlock() {
        // The system prompt side must stay datetime-free (its guidance text
        // may REFERENCE the <current_datetime> tag, but the actual block —
        // and so the per-minute timestamp — arrives via the current turn).
        val prompt = useCase.buildWebAugmentedSystemPrompt("base prompt", resultWithSources())
        assertFalse(prompt.contains("The current local date and time is"))
    }

    // --- fitHistory: token budgeting ----------------------------------------
    // These lock in the contract the native layer depends on: the kept history
    // (plus the reply reserve) must fit inside the model's context window, and
    // the most recent turns always survive truncation.

    @Test
    fun fitHistory_underBudget_keepsEverything() {
        val history = List(4) { userMessage("short message $it") }
        val kept = useCase.fitHistory(history, systemPromptChars = 0, contextTokens = 4096)
        assertEquals(4, kept.size)
    }

    @Test
    fun fitHistory_overBudget_dropsOldestKeepsNewest() {
        // Each message ≈ 250 tokens (1000 chars / 4). Budget at 2048 context:
        // 2048 - 1024 reserve = 1024 tokens → only the most recent ~3 fit.
        val history = List(10) { i -> userMessage("x".repeat(1000) + i) }
        val kept = useCase.fitHistory(history, systemPromptChars = 0, contextTokens = 2048)
        assertTrue("expected truncation, kept ${kept.size}", kept.size < 10)
        // Newest messages are the ones kept, in order.
        assertEquals(history.takeLast(kept.size).map { it.content }, kept.map { it.content })
    }

    @Test
    fun fitHistory_oversizedMessage_isTailTrimmedNotDropped() {
        // 20k chars ≈ 5000 tokens — over the 2048-token per-message cap.
        val huge = userMessage("y".repeat(20_000))
        val kept = useCase.fitHistory(listOf(huge), systemPromptChars = 0, contextTokens = 16_384)
        assertEquals(1, kept.size)
        assertEquals(2048 * 4, kept[0].content.length)
    }

    @Test
    fun fitHistory_zeroContext_usesDefaultBudgetInsteadOfDroppingAll() {
        // Engines that don't report a context (historically the vision-GGUF
        // path) must still get a sane default budget, not an empty history.
        val history = List(3) { userMessage("hello world $it") }
        val kept = useCase.fitHistory(history, systemPromptChars = 0, contextTokens = 0)
        assertEquals(3, kept.size)
    }
}

// --- Stub repositories ----------------------------------------------------
// The prompt helpers under test never invoke these; the stubs exist only to
// satisfy the SendMessageUseCase constructor.

private class StubChatRepository : ChatRepository {
    override suspend fun createConversation(title: String, modelName: String): Long = TODO()
    override suspend fun getConversation(id: Long): Conversation? = TODO()
    override fun getAllConversations(): Flow<List<Conversation>> = TODO()
    override suspend fun updateConversation(conversation: Conversation) = TODO()
    override suspend fun updateConversationTimestamp(id: Long) = TODO()
    override suspend fun deleteConversation(id: Long) = TODO()
    override suspend fun insertMessage(message: Message): Long = TODO()
    override fun getMessagesForConversation(conversationId: Long): Flow<List<Message>> = TODO()
    override suspend fun deleteAllMessages(conversationId: Long) = TODO()
    override suspend fun clearAllHistory() = TODO()
    override fun observeLastMessagesPerConversation(): Flow<List<Pair<Long, String>>> = TODO()
    override suspend fun deleteMessage(messageId: Long) = TODO()
    override suspend fun updateMessage(message: Message) = TODO()
    override suspend fun deleteMessagesAfter(conversationId: Long, afterMessageId: Long) = TODO()
    override suspend fun insertModelChangeMarker(conversationId: Long, newModelName: String) = TODO()
}

private class StubInferenceRepository : InferenceRepository {
    override suspend fun generate(
        prompt: String, temperature: Float, maxTokens: Int, topP: Float, topK: Int,
        repeatPenalty: Float, repeatLastN: Int, minP: Float, tfsZ: Float, typicalP: Float,
        mirostat: Int, mirostatTau: Float, mirostatEta: Float, stopSequences: List<String>,
    ): Result<String> = TODO()

    override fun generateStream(
        prompt: String, temperature: Float, maxTokens: Int, topP: Float, topK: Int,
        repeatPenalty: Float, repeatLastN: Int, minP: Float, tfsZ: Float, typicalP: Float,
        mirostat: Int, mirostatTau: Float, mirostatEta: Float, stopSequences: List<String>,
        reasoningEnabled: Boolean,
    ): Flow<GenerationState> = TODO()

    override suspend fun stopGeneration() = TODO()
    override fun getChatTemplate(): String? = TODO()
    override fun getLoadedModelName(): String? = TODO()
    override fun getActiveBackendLabel(): String = TODO()
    override fun getActiveModelFormatLabel(): String = TODO()
    override fun isVisionSupported(): Boolean = TODO()

    override suspend fun generateWithImage(
        prompt: String, imagePath: String, temperature: Float, maxTokens: Int, topP: Float,
        topK: Int, repeatPenalty: Float, repeatLastN: Int, minP: Float, tfsZ: Float,
        typicalP: Float, mirostat: Int, mirostatTau: Float, mirostatEta: Float,
        stopSequences: List<String>,
    ): Result<String> = TODO()

    override fun generateStreamWithImage(
        prompt: String, imagePath: String, temperature: Float, maxTokens: Int, topP: Float,
        topK: Int, repeatPenalty: Float, repeatLastN: Int, minP: Float, tfsZ: Float,
        typicalP: Float, mirostat: Int, mirostatTau: Float, mirostatEta: Float,
        stopSequences: List<String>, reasoningEnabled: Boolean,
    ): Flow<GenerationState> = TODO()

    override fun generateStreamWithAudio(
        prompt: String, audioPath: String, temperature: Float, maxTokens: Int, topP: Float,
        topK: Int, repeatPenalty: Float, repeatLastN: Int, minP: Float, tfsZ: Float,
        typicalP: Float, mirostat: Int, mirostatTau: Float, mirostatEta: Float,
        stopSequences: List<String>, reasoningEnabled: Boolean,
    ): Flow<GenerationState> = TODO()
}

private class StubWebSearchRepository : WebSearchRepository {
    override suspend fun search(query: String, maxResults: Int): WebSearchResult = TODO()
}
