package com.quantlm.yaser.data.repository

import com.quantlm.yaser.data.diagnostics.AppEventLogger
import com.quantlm.yaser.domain.model.WebSearchResult
import com.quantlm.yaser.domain.model.WebSource
import com.quantlm.yaser.domain.repository.WebSearchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [WebSearchRepository] backed by DuckDuckGo's keyless HTML endpoint.
 *
 * Pipeline: discover URLs from DuckDuckGo → rank by domain trust → scrape the
 * top pages concurrently → return cleaned text. Every step is failure-tolerant:
 * any error produces a [WebSearchResult] with [WebSearchResult.error] set
 * rather than an exception, so chat generation never crashes on a network fault.
 */
@Singleton
class WebSearchRepositoryImpl @Inject constructor() : WebSearchRepository {

    companion object {
        private const val TAG = "WebSearchRepo"
        private const val DDG_ENDPOINT = "https://html.duckduckgo.com/html/"
        // A desktop User-Agent; DuckDuckGo's HTML endpoint returns an empty page to clients it cannot identify.
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        // Per-page scraped text cap. Keeps the injected prompt small enough for
        // modest on-device context windows even with several sources.
        private const val MAX_CONTENT_CHARS = 1200
        // How many discovered results to consider before trust-ranking.
        private const val DISCOVERY_POOL = 12
        // Hard wall-clock budget for an entire search (discovery + all page
        // scrapes). A backstop above the per-request OkHttp timeouts so a
        // chat turn can never sit on "Searching the web…" indefinitely.
        private const val SEARCH_BUDGET_MS = 20_000L

        /** Domains explicitly trusted, scored above the neutral default. */
        private val TRUSTED_DOMAINS: Map<String, Int> = mapOf(
            "wikipedia.org" to 95,
            "britannica.com" to 90,
            "reuters.com" to 88,
            "apnews.com" to 88,
            "bbc.com" to 85,
            "bbc.co.uk" to 85,
            "nytimes.com" to 82,
            "theguardian.com" to 82,
            "npr.org" to 82,
            "bloomberg.com" to 82,
            "nature.com" to 88,
            "sciencedirect.com" to 85,
            "who.int" to 90,
            "developer.android.com" to 85,
            "developer.mozilla.org" to 85,
            "mozilla.org" to 80,
            "stackoverflow.com" to 75,
            "github.com" to 72,
        )

        /** Domains down-ranked: user-generated or low-reliability content. */
        private val LOW_TRUST_DOMAINS: Set<String> = setOf(
            "pinterest.com", "quora.com", "reddit.com", "medium.com",
            "blogspot.com", "wordpress.com", "tumblr.com", "facebook.com",
            "x.com", "twitter.com",
        )
    }

    // Dedicated client with short timeouts — a slow page must not stall a chat
    // turn. callTimeout is the per-request hard cap; kept well below
    // SEARCH_BUDGET_MS so discovery + the concurrent scrapes finish inside it.
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun search(query: String, maxResults: Int): WebSearchResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return WebSearchResult(query = query, error = "Empty search query")
        }
        return try {
            withTimeout(SEARCH_BUDGET_MS) {
                val discovered = discover(trimmed)
                if (discovered.isEmpty()) {
                    AppEventLogger.info(TAG, "web_search_empty", "queryLen=${trimmed.length}")
                    return@withTimeout WebSearchResult(query = trimmed, error = "No web results found")
                }
                // Trust-rank, then keep the strongest few for scraping.
                val ranked = discovered
                    .sortedByDescending { it.trustScore }
                    .take(maxResults.coerceAtLeast(1))

                val sources = coroutineScope {
                    ranked.map { candidate ->
                        async(Dispatchers.IO) { scrapePage(candidate) }
                    }.awaitAll()
                }.filter { it.content.isNotBlank() || it.snippet.isNotBlank() }

                if (sources.isEmpty()) {
                    WebSearchResult(query = trimmed, error = "Web pages could not be read")
                } else {
                    // Log only the result count; query text is user PII and must
                    // not appear in the on-disk event log or diagnostic exports.
                    AppEventLogger.info(TAG, "web_search_ok", "sources=${sources.size}")
                    WebSearchResult(query = trimmed, sources = sources)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Our own wall-clock budget elapsed. Degrade gracefully: the caller
            // treats an error result as "no web info" and still answers.
            AppEventLogger.info(TAG, "web_search_timeout", "")
            WebSearchResult(query = trimmed, error = "Web search timed out")
        } catch (e: CancellationException) {
            // Genuine coroutine cancellation (e.g. the user pressed Stop). Must
            // propagate so the whole generation actually aborts — never swallow.
            throw e
        } catch (e: Exception) {
            AppEventLogger.info(TAG, "web_search_error", "error=${e.message}")
            WebSearchResult(query = trimmed, error = e.message ?: "Web search failed")
        }
    }

    /** Queries DuckDuckGo's HTML endpoint and parses out candidate results. */
    private suspend fun discover(query: String): List<WebSource> = withContext(Dispatchers.IO) {
        val url = DDG_ENDPOINT + "?q=" + URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val html = response.body?.string() ?: return@withContext emptyList()
            val doc = Jsoup.parse(html)
            val results = mutableListOf<WebSource>()
            // Skip sponsored results (`result--ad`).
            for (node in doc.select("div.result:not(.result--ad)")) {
                val anchor = node.selectFirst("a.result__a") ?: continue
                val rawHref = anchor.attr("href")
                val realUrl = resolveDdgUrl(rawHref) ?: continue
                val domain = domainOf(realUrl) ?: continue
                val title = anchor.text().trim().ifEmpty { domain }
                val snippet = node.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
                results += WebSource(
                    title = title,
                    url = realUrl,
                    domain = domain,
                    snippet = snippet,
                    content = "",
                    trustScore = trustScoreFor(domain),
                )
                if (results.size >= DISCOVERY_POOL) break
            }
            // De-duplicate by domain so one site cannot dominate the answer.
            results.distinctBy { it.domain }
        }
    }

    /** Fetches a candidate page and fills in its cleaned [WebSource.content]. */
    private fun scrapePage(candidate: WebSource): WebSource {
        return try {
            val request = Request.Builder()
                .url(candidate.url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(request).execute().use { response ->
                val html = response.body?.string()
                if (!response.isSuccessful || html.isNullOrBlank()) return candidate
                val doc = Jsoup.parse(html, candidate.url)
                // Strip non-content chrome before extracting text.
                doc.select("script, style, noscript, nav, header, footer, aside, form").remove()
                val body = doc.body() ?: return candidate
                val text = body.text().replace(Regex("\\s+"), " ").trim()
                candidate.copy(content = text.take(MAX_CONTENT_CHARS))
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation — let awaitAll/withTimeout tear down.
            throw e
        } catch (e: Exception) {
            AppEventLogger.info(TAG, "web_scrape_error", "url=${candidate.url}, error=${e.message}")
            // Keep the candidate — its snippet is still usable context.
            candidate
        }
    }

    /**
     * DuckDuckGo wraps result links as `//duckduckgo.com/l/?uddg=<encoded-url>`.
     * Decodes the `uddg` parameter back to the real destination URL.
     */
    private fun resolveDdgUrl(href: String): String? {
        if (href.isBlank()) return null
        val normalized = if (href.startsWith("//")) "https:$href" else href
        return try {
            val uddg = Regex("[?&]uddg=([^&]+)").find(normalized)?.groupValues?.get(1)
            val resolved = if (uddg != null) {
                URLDecoder.decode(uddg, "UTF-8")
            } else {
                normalized
            }
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved else null
        } catch (e: Exception) {
            null
        }
    }

    /** Extracts a registrable-ish host (strips a leading `www.`) from a URL. */
    private fun domainOf(url: String): String? = try {
        URI(url).host?.lowercase()?.removePrefix("www.")
    } catch (e: Exception) {
        null
    }

    /** Trust score for a host: explicit map, TLD heuristics, then a neutral default. */
    private fun trustScoreFor(domain: String): Int {
        TRUSTED_DOMAINS[domain]?.let { return it }
        // Match subdomains of trusted entries (e.g. `en.wikipedia.org`).
        TRUSTED_DOMAINS.entries.firstOrNull { domain.endsWith(".${it.key}") }?.let { return it.value }
        if (LOW_TRUST_DOMAINS.any { domain == it || domain.endsWith(".$it") }) return 25
        return when {
            domain.endsWith(".gov") || domain.contains(".gov.") -> 92
            domain.endsWith(".edu") || domain.contains(".edu.") -> 88
            domain.endsWith(".org") -> 60
            else -> 50
        }
    }
}
