package com.quantlm.yaser.domain.repository

import com.quantlm.yaser.domain.model.WebSearchResult

/**
 * Discovers and scrapes web pages for the Web Search feature.
 *
 * Implementations must be self-contained and failure-tolerant: any network or
 * parsing error is reported via [WebSearchResult.error], never thrown. This is
 * the only component that touches the network for chat answers, and it is only
 * ever invoked when the user has the "Web Search" toggle enabled.
 */
interface WebSearchRepository {
    /**
     * Runs a web search for [query] and returns up to [maxResults] scraped,
     * trust-ranked sources. Safe to call from any coroutine; does its own IO
     * dispatching.
     */
    suspend fun search(query: String, maxResults: Int = 4): WebSearchResult
}
