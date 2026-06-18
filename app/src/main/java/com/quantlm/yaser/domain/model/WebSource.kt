package com.quantlm.yaser.domain.model

/**
 * A single web page gathered by the Web Search feature: discovered via
 * DuckDuckGo, ranked for trust, and scraped down to clean text.
 *
 * [content] is the cleaned, truncated page text — raw context fed to the model
 * only. It is never shown to the user verbatim.
 */
data class WebSource(
    val title: String,
    val url: String,
    val domain: String,
    val snippet: String,
    val content: String,
    val trustScore: Int,
)

/**
 * Slim reference to a source that was used to produce an answer. This is the
 * shape persisted in the database and shown in the collapsible "Sources" UI.
 */
data class WebSourceRef(
    val title: String,
    val url: String,
    val domain: String,
)

/**
 * Outcome of a web search. [error] is non-null when the search could not be
 * completed (no network, blocked, parse failure) — callers must handle this
 * gracefully and never crash.
 */
data class WebSearchResult(
    val query: String,
    val sources: List<WebSource> = emptyList(),
    val error: String? = null,
) {
    val isEmpty: Boolean get() = sources.isEmpty()
}

/** Projects a full [WebSource] to the slim [WebSourceRef] used for persistence/UI. */
fun WebSource.toRef(): WebSourceRef =
    WebSourceRef(title = title, url = url, domain = domain)
