package com.quantlm.yaser.domain.tts

/**
 * Read-aloud voice style for chat bubbles.
 */
enum class TtsVoiceProfile(val storageValue: String) {
    /** Older-style engine voice: standard rate, lower-tier local voice when possible. */
    CLASSIC_LEGACY("classic");

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun fromStorage(_profile: String?, _legacyGenderOnly: String?): TtsVoiceProfile {
            return CLASSIC_LEGACY
        }
    }
}
