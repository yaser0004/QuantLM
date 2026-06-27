package com.quantlm.yaser.data.repository

import com.quantlm.yaser.domain.model.WebSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards the web-search per-domain cap (modest-accuracy relaxed de-dup). */
class KeepPerDomainTest {

    private val repo = WebSearchRepositoryImpl()

    private fun src(domain: String, n: Int) =
        WebSource(title = "$domain-$n", url = "https://$domain/$n", domain = domain, snippet = "", content = "", trustScore = 50)

    @Test
    fun keepsAtMostNPerDomain_preservingOrder() {
        val input = listOf(
            src("a.com", 1), src("a.com", 2), src("a.com", 3), // 3rd dropped at max=2
            src("b.com", 1),
            src("a.com", 4),                                    // dropped, a.com already full
            src("c.com", 1),
        )
        val kept = repo.keepPerDomain(input, maxPerDomain = 2)

        assertEquals(listOf("a.com-1", "a.com-2", "b.com-1", "c.com-1"), kept.map { it.title })
    }

    @Test
    fun maxOne_isStrictDistinctByDomain() {
        val input = listOf(src("a.com", 1), src("a.com", 2), src("b.com", 1))
        val kept = repo.keepPerDomain(input, maxPerDomain = 1)
        assertEquals(listOf("a.com", "b.com"), kept.map { it.domain })
    }
}
