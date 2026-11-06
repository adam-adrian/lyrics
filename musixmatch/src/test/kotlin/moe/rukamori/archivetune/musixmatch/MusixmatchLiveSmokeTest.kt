/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class MusixmatchLiveSmokeTest {
    @Test
    fun productionSearchSmoke() = runBlocking {
        Musixmatch.resetForTesting()
        Musixmatch.logger = null
        val started = System.nanoTime()
        var count = 0
        try {
            withTimeout(8_000) {
                Musixmatch.getAllLyrics("Levitating", "Dua Lipa") { lyrics ->
                    val format = when {
                        lyrics.contains("<tt") -> {
                            val factory = DocumentBuilderFactory.newInstance()
                            factory.isNamespaceAware = true
                            factory.newDocumentBuilder().parse(InputSource(StringReader(lyrics)))
                            "ttml"
                        }
                        lyrics.startsWith("[") -> "lrc"
                        else -> "plain"
                    }
                    count++
                    println("MXM_SMOKE candidate=$count format=$format elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                }
            }
            check(count in 1..4) { "No production search results" }
        } finally {
            Musixmatch.resetForTesting()
            Musixmatch.logger = null
        }
    }
}
