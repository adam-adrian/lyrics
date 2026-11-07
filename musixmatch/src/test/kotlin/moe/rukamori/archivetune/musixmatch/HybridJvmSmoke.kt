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
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.betterlyrics.TTMLParser

object HybridJvmSmoke {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val headers = headersOf(HttpHeaders.ContentType, "application/json")
        fun envelope(body: String, status: Int = 200) =
            """{"message":{"header":{"status_code":$status},"body":$body}}"""
        fun track(id: Int, version: Boolean = false) =
            """{"track_id":$id,"commontrack_id":${1000 + id},"track_name":"${if (version) "Song (Live)" else "Song"}","artist_name":"Artist","track_length":100,"has_richsync":1,"track_rating":${if (version) 100 else 50}}"""
        fun macro(id: Int, status: Int = 200) = envelope(
            """{"macro_calls":{"matcher.track.get":${envelope("{\"track\":${track(id)}}", status)}}}""",
        )
        val text = "Don't lose <3 & hope"
        val richLines = """[{"ts":1.0,"te":3.0,"l":[{"c":${Json.encodeToString(text)},"o":0.0}],"x":${Json.encodeToString(text)}}]"""
        val rich = envelope("""{"richsync":{"richsync_body":${Json.encodeToString(richLines)}}}""")
        for (playback in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val logs = mutableListOf<String>()
            var titleCalls = 0
            Musixmatch.resetForTesting(MockEngine { request ->
                val endpoint = request.url.encodedPath.substringAfterLast('/')
                val body = when (endpoint) {
                    "token.get" -> {
                        calls.add("renew")
                        envelope("{\"user_token\":\"smoke_renewed\"}")
                    }
                    "track.search" -> {
                        calls.add("search")
                        val entries = if (playback) listOf(track(1, true), track(2), track(3)) else emptyList()
                        envelope("{\"track_list\":[${entries.joinToString(",") { "{\"track\":$it}" }}]}")
                    }
                    "macro.subtitles.get" -> {
                        val id = request.url.parameters["track_id"]?.toInt()
                        if (id == null) {
                            calls.add("title")
                            titleCalls++
                            if (playback && titleCalls == 1) envelope("{}", 401)
                            else macro(3, if (playback) 404 else 200)
                        } else {
                            calls.add("id:$id")
                            macro(id, if (id == 2) 404 else 200)
                        }
                    }
                    "track.richsync.get" -> { calls.add("richsync"); rich }
                    else -> error("Unexpected smoke endpoint")
                }
                respond(body, headers = headers)
            })
            Musixmatch.cachedToken = "smoke_initial"
            Musixmatch.logger = logs::add
            try {
                val outputs = mutableListOf<String>()
                if (playback) outputs.add(Musixmatch.getLyrics("Song", "Artist", durationSeconds = 100).getOrThrow())
                else Musixmatch.getAllLyrics("Song", "Artist", durationSeconds = 100, callback = outputs::add)
                val parsed = TTMLParser.parseTTML(outputs.single()).single()
                check(parsed.text == text && parsed.startTime == 1.0 && parsed.endTime == 3.0)
                check(parsed.words.joinToString("") { it.text } == text)
                check(parsed.words.all { it.endTime > it.startTime })
                val expected = if (playback) listOf("title", "renew", "title", "search", "id:2", "id:3", "richsync")
                    else listOf("search", "title", "richsync")
                check(calls == expected)
                if (!playback) check(logs.filter { it.startsWith("Musixmatch search:") } == listOf("Musixmatch search: returned=0 eligible=0 candidates=0"))
                println("HYBRID_SMOKE path=${if (playback) "playback" else "search"} requests=${calls.size} renewals=${calls.count { it == "renew" }} lines=1 words=${parsed.words.size} format=TTML status=PASS")
            } finally {
                Musixmatch.logger = null
                Musixmatch.resetForTesting()
            }
        }
    }
}
