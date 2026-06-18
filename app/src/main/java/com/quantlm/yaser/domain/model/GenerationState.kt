package com.quantlm.yaser.domain.model

sealed class GenerationState {
    object Idle : GenerationState()
    object Loading : GenerationState()
    data class Generating(val currentText: String = "") : GenerationState()
    /**
     * Phase 2 (§3.6): chain-of-thought streaming. Emitted while the response
     * is still inside a `<think>...</think>` block. Backward-compat note: any
     * collector that doesn't special-case [Thinking] should treat it as a
     * no-op equivalent to [Generating] (UI will still show the spinner).
     */
    data class Thinking(
        val content: String = "",
        val partial: Boolean = true,
        val thoughtSummary: String? = null,
    ) : GenerationState()
    /**
     * Web Search: emitted while the Web Search feature is discovering and
     * scraping pages, before token generation begins. This phase can take a
     * few seconds, so the UI surfaces [message] to set expectations.
     * Collectors that don't special-case it should treat it like [Loading].
     */
    data class SearchingWeb(
        val message: String = "Searching the web, this may take a few seconds…",
    ) : GenerationState()

    /**
     * Audio Scribe: emitted right before the model begins decoding an audio
     * attachment. From the app's perspective audio "processing" and token
     * generation are a single opaque call inside the engine, so this state
     * lives only until the first generated token arrives, then transitions
     * to [Generating]. Same UX role as [SearchingWeb].
     *
     * [alsoReasoning] is set when the user has reasoning enabled for the
     * same turn — the indicator UI then surfaces both phases in a single
     * bubble (mic icon + brain icon, combined wording) since the engine
     * processes audio and prefills the reasoning context in one opaque call.
     */
    data class TranscribingAudio(
        val message: String = "Listening to your audio, this may take a moment…",
        val alsoReasoning: Boolean = false,
    ) : GenerationState()

    /**
     * Vision: emitted right before the model begins decoding an image
     * attachment. Same lifecycle as [TranscribingAudio] — replaced by
     * [Generating] as soon as the first token streams in. Same
     * [alsoReasoning] handling as the audio state.
     */
    data class AnalyzingImage(
        val message: String = "Analyzing the image, this may take a moment…",
        val alsoReasoning: Boolean = false,
    ) : GenerationState()

    /**
     * Reasoning prelude: emitted right before a generation that has chain-of-
     * thought enabled and has no media attachment. While reasoning combined
     * with audio or vision is shown via the [alsoReasoning] flag on
     * [TranscribingAudio] / [AnalyzingImage] (so the user sees one bubble
     * with two icons rather than two stacked bubbles), this state covers the
     * text-only-with-reasoning case. Once the model emits its first `<think>`
     * token the state transitions to [Thinking]; once it emits an answer
     * token it becomes [Generating].
     */
    data class PreparingReasoning(
        val message: String = "Preparing to think this through…",
    ) : GenerationState()

    data class Complete(val text: String) : GenerationState()
    data class Error(val message: String) : GenerationState()
    data class Cancelled(val partialText: String = "") : GenerationState()
}
