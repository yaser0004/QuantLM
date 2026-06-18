package com.quantlm.yaser.domain.model

/**
 * 🚨 FUTURE MODEL ADDITION SOP 🚨
 *
 * When adding a new model to AvailableModels, do NOT rely on UNKNOWN. You must verify the
 * creator's official recommended inference parameters (Temp, Top-P, Rep-Pen) and exact Stop
 * Tokens, and create a dedicated ModelFamily branch here. Failure to do so will result in severe
 * model degradation, looping, or gibberish.
 */
object ModelProfileRegistry {

    fun getProfileForModel(modelId: String): ModelProfile {
        return when {
            modelId.contains("smol", ignoreCase = true) -> ModelProfile(
                family = ModelFamily.SMOL,
                defaultTemperature = 0.7f,
                repetitionPenalty = 1.1f,
                minP = 0.05f,
                topP = 0.9f,
                topK = 40,
                stopTokens = listOf("<|im_end|>", "<|endoftext|>")
            )
            modelId.contains("qwen", ignoreCase = true) -> ModelProfile(
                family = ModelFamily.QWEN,
                defaultTemperature = 0.4f,
                repetitionPenalty = 1.15f,
                minP = 0.05f,
                topP = 0.8f,
                topK = 40,
                stopTokens = listOf("<|im_end|>")
            )
            modelId.contains("phi-4", ignoreCase = true) -> ModelProfile(
                family = ModelFamily.PHI_4,
                defaultTemperature = 0.8f,
                repetitionPenalty = 1.0f,
                minP = 0.05f,
                topP = 0.95f,
                topK = 50,
                stopTokens = listOf("<|im_end|>", "<|end|>")
            )
            modelId.contains("gemma", ignoreCase = true) -> ModelProfile(
                family = ModelFamily.GEMMA,
                defaultTemperature = 1.0f,
                repetitionPenalty = 1.0f,
                minP = 0.05f,
                topP = 0.95f,
                topK = 64,
                stopTokens = listOf("<end_of_turn>", "<eos>")
            )
            else -> ModelProfile(
                family = ModelFamily.UNKNOWN,
                defaultTemperature = 0.7f,
                repetitionPenalty = 1.1f,
                minP = 0.05f,
                topP = 0.9f,
                topK = 40,
                stopTokens = listOf("<|im_end|>", "<|endoftext|>")
            )
        }
    }
}
