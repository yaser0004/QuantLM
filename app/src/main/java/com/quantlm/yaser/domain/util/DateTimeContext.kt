package com.quantlm.yaser.domain.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Supplies the current local date and time to LLM prompts.
 *
 * The app's on-device models have no inherent notion of "now", so a small,
 * authoritative block is injected into the system prompt on every message
 * (see SendMessageUseCase). This is recomputed per call, so it is always
 * current. Functions are pure and model-agnostic.
 *
 * SimpleDateFormat is not thread-safe; instances are constructed locally on
 * each call rather than cached, because callers run on coroutine dispatchers.
 */
object DateTimeContext {

    /**
     * A tagged, authoritative block describing the current local date/time,
     * appended to the system prompt for every model family. Kept compact to
     * limit token cost on modest on-device context windows.
     */
    fun currentDateTimeBlock(): String {
        val now = Date()
        val locale = Locale.getDefault()
        val tz = TimeZone.getDefault()

        val dateFmt = SimpleDateFormat("EEEE, MMMM d, yyyy", locale)
        val timeFmt = SimpleDateFormat("h:mm a", locale)
        val tzName = tz.getDisplayName(false, TimeZone.SHORT, locale)

        return buildString {
            appendLine("<current_datetime>")
            appendLine(
                "The current local date and time is: " +
                    "${dateFmt.format(now)}, ${timeFmt.format(now)} ($tzName)."
            )
            appendLine(
                "Treat this as the correct current date and time. Use it ONLY for " +
                    "questions that depend on the current date or time — today's date, " +
                    "the day of the week, or how long ago a past date was / how long " +
                    "until a future date. It tells you nothing about any other topic; " +
                    "do not let it influence unrelated factual answers."
            )
            append("</current_datetime>")
        }
    }

    /**
     * Just the current date, e.g. "Tuesday, May 19, 2026". Used to anchor the
     * web-search context so the model can judge source recency.
     */
    fun currentDateLine(): String {
        return SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
    }

    /** The current four-digit year, used to anchor time-sensitive search queries. */
    fun currentYear(): Int {
        return Calendar.getInstance().get(Calendar.YEAR)
    }
}
