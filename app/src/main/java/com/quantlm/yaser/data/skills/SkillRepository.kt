package com.quantlm.yaser.data.skills

import android.content.Context
import com.quantlm.yaser.domain.model.AgentSkill
import com.quantlm.yaser.domain.model.AgentSkillKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 2 (§3.9): manages the registered agent skills (built-in + imported)
 * and per-skill enable state. Today the storage is in-memory; the bundled
 * starter set is declared inline so we don't ship asset-file infrastructure
 * before the JS runner is in place (TODO below).
 *
 * The repository exposes a single [skills] StateFlow consumed by
 * [com.quantlm.yaser.presentation.chat.ChatViewModel] for the chip strip and
 * skill manager bottom sheet. [getSystemPromptInjection] returns the block
 * that should be prepended to the system prompt at send time when agent
 * skills are enabled.
 */
@Singleton
class SkillRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _skills = MutableStateFlow(builtInSkills())
    val skills: StateFlow<List<AgentSkill>> = _skills.asStateFlow()

    fun setEnabled(name: String, enabled: Boolean) {
        _skills.value = _skills.value.map {
            if (it.name == name) it.copy(enabled = enabled) else it
        }
        // TODO(Sub-phase F runtime): persist enabled-state to DataStore so
        // toggles survive app restarts.
    }

    fun enabledSkills(): List<AgentSkill> = _skills.value.filter { it.enabled }

    /**
     * Phase 2 (§3.9): build the system-prompt prefix that names every enabled
     * skill. Returns null when no skill is enabled — caller should fall back
     * to the normal system prompt.
     */
    fun getSystemPromptInjection(): String? {
        val active = enabledSkills().ifEmpty { return null }
        return AgentSystemPromptBuilder.build(active)
    }

    companion object {
        private fun builtInSkills(): List<AgentSkill> = listOf(
            AgentSkill(
                name = "kitchen-adventure",
                description = "A culinary co-pilot persona that walks the user through cooking a recipe.",
                instructions = "Adopt the persona of a friendly cooking coach. Ask clarifying questions " +
                        "about available ingredients, walk through prep step-by-step, and offer substitutions.",
                kind = AgentSkillKind.TEXT,
                builtIn = true,
                enabled = false,
            ),
            AgentSkill(
                name = "query-wikipedia",
                description = "Fetch a Wikipedia summary for a topic via a hidden WebView.",
                instructions = "Ask the user for a topic and call run_js with skill_name=query-wikipedia " +
                        "and data={topic: \"…\"}. Render the returned summary as the final answer.",
                kind = AgentSkillKind.JS,
                builtIn = true,
                enabled = false,
            ),
            AgentSkill(
                name = "send-email",
                description = "Compose a draft email through the Android share/intent flow.",
                instructions = "Ask the user for recipient, subject, and body, then call run_intent with " +
                        "intent=send_email and parameters={to,subject,body}.",
                kind = AgentSkillKind.NATIVE_INTENT,
                builtIn = true,
                enabled = false,
            ),
        )
    }
}

/**
 * Phase 2 (§3.9): builds the system-prompt prefix injected when agent skills
 * are enabled. Re-implementation of Edge_AI_Gallery's `injectSkills` in
 * QuantLM's style.
 */
object AgentSystemPromptBuilder {
    fun build(skills: List<AgentSkill>): String {
        val list = skills.joinToString("\n") { "- ${it.name}: ${it.description}" }
        return buildString {
            appendLine("You are an AI assistant that can use skills to complete tasks. For each user")
            appendLine("request, follow these steps silently:")
            appendLine()
            appendLine("1. Pick the most relevant skill from this list (or none):")
            appendLine()
            appendLine(list)
            appendLine()
            appendLine("2. If a relevant skill exists, call the `load_skill` tool to read its")
            appendLine("   instructions, then follow them. If the skill includes a JS body, call")
            appendLine("   the `run_js` tool. If it includes a native intent, call the `run_intent`")
            appendLine("   tool. Otherwise, answer normally.")
            appendLine()
            appendLine("Output ONLY the final answer. No intermediate reasoning or status updates.")
        }
    }
}
