/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class MusixmatchLoggingTest {
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val logs = ConcurrentLinkedQueue<String>()

    private val allowlistPatterns = listOf(
        Regex("""^Musixmatch token request (started|acquired successfully)$"""),
        Regex("""^Musixmatch (fallback|fan-out): track search request started$"""),
        Regex("""^Musixmatch (playback|title-fallback): macro request started \(by-name\)$"""),
        Regex("""^Musixmatch (fallback|fan-out) ranked \d+: macro by-id request started$"""),
        Regex("""^Musixmatch (fallback|fan-out) ranked \d+: separate (richsync|subtitle) request started$"""),
        Regex("""^Musixmatch (playback|title-fallback): (success quality=[0-2]|instrumental|failed \([A-Za-z]+\))$"""),
        Regex("""^Musixmatch (fallback|fan-out) ranked \d+: (success demerit=\d+ quality=[0-2] rating=\d+|instrumental|failed \([A-Za-z]+\)|cancelled \(early-stop\))$"""),
        Regex("""^Musixmatch fallback: (ranked search|early-stop|winner from ranked \d+|final terminal \([A-Za-z]+\) from ranked \d+|all candidates failed \([A-Za-z]+\) from ranked \d+)$"""),
        Regex("""^Musixmatch title-fallback: title matcher$"""),
        Regex("""^Musixmatch fan-out: (queue stopped — terminal \([A-Za-z]+\) at ranked \d+|final terminal \([A-Za-z]+\) from ranked \d+)$"""),
        Regex("""^Musixmatch (fallback|fan-out): search funnel: returned=\d+ eligible=\d+ candidates=\d+$"""),
    )

    @After
    fun tearDown() {
        Musixmatch.resetForTesting()
        Musixmatch.logger = null
    }

    private fun assertAllLogsMatchAllowlist() {
        for (line in logs) {
            val matches = allowlistPatterns.any { it.matches(line) }
            assertTrue("Log line violated allowlist pattern: $line", matches)
            assertFalse("Log line contained secret or url: $line", line.contains("token") && !line.startsWith("Musixmatch token request") && !line.contains("TokenInvalid"))
            assertFalse("Log line contained lyrics or message: $line", line.contains("Lyrics") || line.contains("http://") || line.contains("https://"))
        }
    }

    @Test
    fun playbackMainSuccess_logsClassAAndB() = runBlocking {
        Musixmatch.logger = { logs.add(it) }
        Musixmatch.resetForTesting(MockEngine {
            respond(macro(lyrics = "Test lyrics"), headers = headers)
        })
        Musixmatch.cachedToken = "test_token"

        val result = Musixmatch.getLyrics("Levitating", "Dua Lipa")
        assertTrue(result.isSuccess)

        assertAllLogsMatchAllowlist()
        assertEquals(
            listOf(
                "Musixmatch playback: macro request started (by-name)",
                "Musixmatch playback: success quality=1",
            ),
            logs.toList(),
        )
    }

    @Test
    fun playbackFallbackSuccessWithEarlyStop_logsCorrectClasses() = runBlocking {
        Musixmatch.logger = { logs.add(it) }
        Musixmatch.resetForTesting(MockEngine { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(envelope("{\"track_list\":[{\"track\":${track(1)}},{\"track\":${track(2)}}]}"), headers = headers)
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull()
                    if (id == null) respond(macro(matcherStatus = 404), headers = headers)
                    else respond(macro(id, richsync = "richsync"), headers = headers)
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "test_token"

        val result = Musixmatch.getLyrics("Song", "Artist", durationSeconds = 100)
        assertTrue(result.isSuccess)

        assertAllLogsMatchAllowlist()
        assertTrue(logs.contains("Musixmatch playback: macro request started (by-name)"))
        assertTrue(logs.contains("Musixmatch playback: failed (NoMatch)"))
        assertTrue(logs.contains("Musixmatch fallback: ranked search"))
        assertTrue(logs.contains("Musixmatch fallback: track search request started"))
        assertTrue(logs.any { it.startsWith("Musixmatch fallback: search funnel:") })
        assertTrue(logs.contains("Musixmatch fallback: winner from ranked 1"))
    }

    @Test
    fun playbackFallbackOneTerminalOneSuccess_winnerLogged() = runBlocking {
        Musixmatch.logger = { logs.add(it) }
        Musixmatch.resetForTesting(MockEngine { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(envelope("{\"track_list\":[{\"track\":${track(1)}},{\"track\":${track(2)}}]}"), headers = headers)
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull()
                    if (id == null) respond(macro(matcherStatus = 404), headers = headers)
                    else if (id == 1L) respond(status(429), headers = headers)
                    else respond(macro(id), headers = headers)
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "test_token"

        val result = Musixmatch.getLyrics("Song", "Artist")
        assertTrue(result.isSuccess)

        assertAllLogsMatchAllowlist()
        assertTrue(logs.contains("Musixmatch fallback ranked 1: failed (RateLimited)"))
        assertTrue(logs.contains("Musixmatch fallback ranked 2: success demerit=0 quality=1 rating=80"))
        assertTrue(logs.contains("Musixmatch fallback: winner from ranked 2"))
    }

    @Test
    fun playbackFallbackAllTerminal_finalTerminalLogged() = runBlocking {
        Musixmatch.logger = { logs.add(it) }
        Musixmatch.resetForTesting(MockEngine { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(envelope("{\"track_list\":[{\"track\":${track(1)}},{\"track\":${track(2)}}]}"), headers = headers)
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull()
                    if (id == null) respond(macro(matcherStatus = 404), headers = headers)
                    else respond(status(429), headers = headers)
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "test_token"

        val result = Musixmatch.getLyrics("Song", "Artist")
        assertTrue(result.isFailure)

        assertAllLogsMatchAllowlist()
        assertTrue(logs.contains("Musixmatch fallback: final terminal (RateLimited) from ranked 1"))
    }

    @Test
    fun fanOutEmptySearch_titleFallbackLogged() = runBlocking {
        Musixmatch.logger = { logs.add(it) }
        Musixmatch.resetForTesting(MockEngine { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(envelope("{\"track_list\":[]}"), headers = headers)
                "macro.subtitles.get" -> respond(macro(), headers = headers)
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "test_token"

        val lyricsList = mutableListOf<String>()
        Musixmatch.getAllLyrics("Song", "Artist", callback = lyricsList::add)
        assertEquals(1, lyricsList.size)

        assertAllLogsMatchAllowlist()
        assertTrue(logs.contains("Musixmatch fan-out: track search request started"))
        assertTrue(logs.any { it.startsWith("Musixmatch fan-out: search funnel: returned=0") })
        assertTrue(logs.contains("Musixmatch title-fallback: title matcher"))
        assertTrue(logs.contains("Musixmatch title-fallback: macro request started (by-name)"))
        assertTrue(logs.contains("Musixmatch title-fallback: success quality=1"))
    }

    @Test
    fun fanOutQueueStoppedAndFinalTerminal_loggedOnTerminalWhenQueueWaiting() = runBlocking {
        Musixmatch.logger = { logs.add(it) }
        val entered1 = CompletableDeferred<Unit>()
        val release1 = CompletableDeferred<Unit>()
        Musixmatch.resetForTesting(MockEngine { request ->
            when (request.url.encodedPath.substringAfterLast('/')) {
                "track.search" -> respond(
                    envelope("{\"track_list\":[{\"track\":${track(1)}},{\"track\":${track(2)}},{\"track\":${track(3)}},{\"track\":${track(4)}}]}"),
                    headers = headers,
                )
                "macro.subtitles.get" -> {
                    val id = request.url.parameters["track_id"]?.toLongOrNull()
                    if (id == 1L) {
                        entered1.complete(Unit)
                        release1.await()
                        respond(status(429), headers = headers)
                    } else {
                        respond(macro(id ?: 0), headers = headers)
                    }
                }
                else -> error("Unexpected endpoint: ${request.url.encodedPath}")
            }
        })
        Musixmatch.cachedToken = "test_token"

        val op = async {
            runCatching {
                Musixmatch.getAllLyrics("Song", "Artist") {}
            }
        }
        entered1.await()
        release1.complete(Unit)
        val res = op.await()
        assertTrue(res.isFailure)

        assertAllLogsMatchAllowlist()
        assertTrue(logs.contains("Musixmatch fan-out: queue stopped — terminal (RateLimited) at ranked 1"))
        assertTrue(logs.contains("Musixmatch fan-out: final terminal (RateLimited) from ranked 1"))
    }
}
