package com.quantlm.yaser.presentation.chat

import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.quantlm.yaser.domain.tts.TtsVoiceProfile
import java.util.Locale

/**
 * Applies device TTS engine settings for the Classic legacy style.
 */
object TtsVoiceConfigurator {

    private const val CLASSIC_RATE = 1.0f
    private const val CLASSIC_PITCH = 1.0f

    @Suppress("UNUSED_PARAMETER")
    fun apply(tts: TextToSpeech, _profile: TtsVoiceProfile): Boolean {
        val locale = Locale.getDefault()
        val langResult = tts.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US)
        }

        tts.setSpeechRate(CLASSIC_RATE)
        tts.setPitch(CLASSIC_PITCH)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return true
        }

        val voices = tts.voices ?: return true
        val pool = filterPool(voices, locale)

        return applyClassic(tts, pool)
    }

    private fun applyClassic(tts: TextToSpeech, pool: List<Voice>): Boolean {
        tts.setSpeechRate(CLASSIC_RATE)
        tts.setPitch(CLASSIC_PITCH)
        val voice = pool.minWithOrNull(
            compareBy<Voice>({ it.quality }, { it.name })
        ) ?: return true
        return try {
            tts.voice = voice
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun filterPool(voices: Set<Voice>, locale: Locale): List<Voice> {
        val lang = locale.language.lowercase(Locale.ROOT)
        val country = locale.country.lowercase(Locale.ROOT)

        fun Voice.localeMatches(): Boolean {
            val l = this.locale
            if (!l.language.equals(lang, ignoreCase = true)) return false
            if (country.isEmpty()) return true
            return l.country.isEmpty() || l.country.equals(country, ignoreCase = true)
        }

        var pool = voices.filter { it.localeMatches() && !it.isNetworkConnectionRequired }
        if (pool.isEmpty()) {
            pool = voices.filter {
                it.locale.language.equals(lang, ignoreCase = true) && !it.isNetworkConnectionRequired
            }
        }
        if (pool.isEmpty()) {
            pool = voices.filter {
                it.locale.language.equals("en", ignoreCase = true) && !it.isNetworkConnectionRequired
            }
        }
        return pool.ifEmpty { voices.toList() }
    }
}
