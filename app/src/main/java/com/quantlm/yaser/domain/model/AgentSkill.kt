package com.quantlm.yaser.domain.model

import android.net.Uri

/**
 * Phase 2 (§3.9): agent skill domain model. Mirrors Edge_AI_Gallery's
 * `SkillManagerViewModel` skill record, re-implemented in QuantLM's style.
 *
 * - [TEXT] skills supply persona / instruction-only prompts.
 * - [JS] skills carry a small JS bundle executed in a hidden WebView via
 *   `quantlm_skill_run` (Sub-phase F runtime).
 * - [NATIVE_INTENT] skills map onto whitelisted intents through
 *   `QuantLMIntentRouter` (send_email / send_sms / open_url / open_map / make_call).
 */
enum class AgentSkillKind { TEXT, JS, NATIVE_INTENT }

data class AgentSkill(
    val name: String,
    val description: String,
    val instructions: String,
    val kind: AgentSkillKind,
    val sourceUri: Uri? = null,
    val homepage: String? = null,
    val requiresSecret: Boolean = false,
    val requiresSecretDescription: String? = null,
    val builtIn: Boolean = false,
    val enabled: Boolean = true,
)
