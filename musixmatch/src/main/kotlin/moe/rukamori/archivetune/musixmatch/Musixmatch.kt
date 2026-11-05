/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.musixmatch.models.ApiResponse
import moe.rukamori.archivetune.musixmatch.models.LyricsBody
import moe.rukamori.archivetune.musixmatch.models.MacroResponseBody
import moe.rukamori.archivetune.musixmatch.models.MatcherBody
import moe.rukamori.archivetune.musixmatch.models.RichSyncBody
import moe.rukamori.archivetune.musixmatch.models.RichSyncLine
import moe.rukamori.archivetune.musixmatch.models.SubtitleLine
import moe.rukamori.archivetune.musixmatch.models.SubtitlesBody
import moe.rukamori.archivetune.musixmatch.models.TokenBody
import moe.rukamori.archivetune.musixmatch.models.Track
import moe.rukamori.archivetune.musixmatch.models.bodyAs
import java.util.Locale
import kotlin.math.abs

object Musixmatch {
    private const val BASE_URL = "https://apic-appmobile.musixmatch.com/ws/1.1/"
    private const val APP_ID = "mac-ios-v2.0"
    private const val DURATION_TOLERANCE = 8

    private val LICENSE_FOOTER_PATTERNS =
        listOf(
            "this lyrics is not for commercial use",
            "these lyrics are not for commercial use",
        )

    private val jsonFormat by lazy {
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    var logger: ((String) -> Unit)? = null

    internal fun createHttpClient(engine: HttpClientEngine? = null): HttpClient {
        val clientConfig: HttpClientConfig<*>.() -> Unit = {
            install(HttpTimeout) {
                requestTimeoutMillis = 20000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 20000
            }

            defaultRequest {
                headers.append("Accept", "application/json")
                headers.append("Accept-Language", "en-US,en;q=0.9")
                headers.append("X-Cookie", "x-mxm-token-guid=")
                headers.append("x-mxm-app-version", "10.1.1")
                headers.append("X-User-Agent", "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0")
            }

            expectSuccess = false
        }

        return if (engine != null) HttpClient(engine, clientConfig) else HttpClient(OkHttp, clientConfig)
    }

    @Volatile
    private var customClient: HttpClient? = null

    internal var client: HttpClient
        get() = customClient ?: createHttpClient().also { customClient = it }
        set(value) {
            customClient = value
        }

    internal fun resetForTesting(engine: HttpClientEngine? = null) {
        cachedToken = null
        customClient = engine?.let { createHttpClient(it) }
    }

    internal suspend fun getToken(): String {
        cachedToken?.let { return it }
        return tokenMutex.withLock {
            cachedToken ?: fetchToken().also { cachedToken = it }
        }
    }

    internal suspend fun renewToken(previous: String): String =
        tokenMutex.withLock {
            val current = cachedToken
            if (current != null && current != previous) {
                current
            } else {
                fetchToken().also { cachedToken = it }
            }
        }

    private suspend fun fetchToken(): String {
        logger?.invoke("Musixmatch token request started")
        val response =
            client.get("${BASE_URL}token.get") {
                parameter("app_id", APP_ID)
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw MusixmatchError.RateLimited("HTTP 401 on token.get")
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw MusixmatchError.RateLimited("HTTP 429 on token.get")
        }
        if (response.status.value >= 500) {
            throw MusixmatchError.ServerError(response.status.value)
        }
        if (!response.status.isSuccess()) {
            throw MusixmatchError.MalformedResponse("HTTP ${response.status.value} on token.get")
        }

        val bodyText = response.bodyAsText()
        val apiResponse =
            runCatching {
                jsonFormat.decodeFromString<ApiResponse<TokenBody>>(bodyText)
            }.getOrElse {
                throw MusixmatchError.MalformedResponse("Failed to parse token response")
            }

        val statusCode = apiResponse.message?.header?.statusCode ?: 0
        if (statusCode == 401) {
            throw MusixmatchError.RateLimited("Status code 401 on token.get")
        }
        if (statusCode == 429) {
            throw MusixmatchError.RateLimited("Status code 429 on token.get")
        }
        if (statusCode >= 500) {
            throw MusixmatchError.ServerError(statusCode)
        }
        if (statusCode != 200) {
            throw MusixmatchError.MalformedResponse("Status code $statusCode on token.get")
        }

        val token = apiResponse.message?.body?.userToken
        if (token.isNullOrBlank() || token.all { it == '0' } || token.startsWith("UpgradeOnly")) {
            throw MusixmatchError.TokenInvalid("Token missing or blacklisted")
        }

        logger?.invoke("Musixmatch token acquired successfully")
        return token
    }

    private suspend fun executeMacro(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int,
        token: String,
    ): MacroResponseBody {
        logger?.invoke("Musixmatch macro request started")
        val response =
            client.get("${BASE_URL}macro.subtitles.get") {
                parameter("format", "json")
                parameter("namespace", "lyrics_richsynched")
                parameter("subtitle_format", "mxm")
                parameter("app_id", APP_ID)
                parameter("optional_calls", "track.richsync")
                parameter("richsync_compact_type", "words")
                parameter("part", "track_lyrics_translation_status,track_structure,track_performer_tagging")
                parameter("q_track", title)
                parameter("q_artist", artist)
                parameter("q_artists", artist)
                parameter("usertoken", token)

                if (!album.isNullOrBlank()) {
                    parameter("q_album", album)
                }
                if (durationSeconds > 0) {
                    parameter("q_duration", durationSeconds)
                    parameter("f_subtitle_length", durationSeconds)
                }
            }

        if (response.status == HttpStatusCode.Unauthorized) {
            throw MusixmatchError.TokenInvalid("HTTP 401 on macro")
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw MusixmatchError.RateLimited("HTTP 429 on macro")
        }
        if (response.status.value >= 500) {
            throw MusixmatchError.ServerError(response.status.value)
        }
        if (!response.status.isSuccess()) {
            throw MusixmatchError.MalformedResponse("HTTP ${response.status.value} on macro")
        }

        val bodyText = response.bodyAsText()
        val apiResponse =
            runCatching {
                jsonFormat.decodeFromString<ApiResponse<MacroResponseBody>>(bodyText)
            }.getOrElse {
                throw MusixmatchError.MalformedResponse("Failed to parse macro response")
            }

        val statusCode = apiResponse.message?.header?.statusCode ?: 0
        if (statusCode == 401) {
            throw MusixmatchError.TokenInvalid("Status code 401 on macro")
        }
        if (statusCode == 429) {
            throw MusixmatchError.RateLimited("Status code 429 on macro")
        }
        if (statusCode >= 500) {
            throw MusixmatchError.ServerError(statusCode)
        }
        if (statusCode != 200) {
            throw MusixmatchError.MalformedResponse("Status code $statusCode on macro")
        }

        logger?.invoke("Musixmatch macro response received")
        return apiResponse.message?.body ?: throw MusixmatchError.MalformedResponse("Empty macro body")
    }

    private suspend fun fetchRichSyncSeparately(
        commonTrackId: Long,
        trackLength: Int,
        token: String,
    ): String? {
        if (commonTrackId <= 0) return null
        return try {
            logger?.invoke("Musixmatch separate richsync request started")
            val response =
                client.get("${BASE_URL}track.richsync.get") {
                    parameter("format", "json")
                    parameter("subtitle_format", "mxm")
                    parameter("app_id", APP_ID)
                    parameter("richsync_compact_type", "words")
                    parameter("commontrack_id", commonTrackId)
                    parameter("f_subtitle_length", trackLength)
                    parameter("q_duration", trackLength)
                    parameter("usertoken", token)
                }

            if (!response.status.isSuccess()) return null
            val bodyText = response.bodyAsText()
            val apiResponse = jsonFormat.decodeFromString<ApiResponse<RichSyncBody>>(bodyText)
            if (apiResponse.message?.header?.statusCode != 200) return null
            apiResponse.message?.body?.richsync?.richsyncBody
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeString(value: String): String =
        value.filter { it.isLetterOrDigit() }.lowercase()

    private fun validateMatch(
        track: Track?,
        inputTitle: String,
        inputArtist: String,
        inputDuration: Int,
    ) {
        if (track == null) {
            throw MusixmatchError.NoMatch("No track matched")
        }

        if (inputDuration > 0 && track.trackLength > 0) {
            if (abs(track.trackLength - inputDuration) > DURATION_TOLERANCE) {
                throw MusixmatchError.Mismatch(
                    "Track length ${track.trackLength} differs from input $inputDuration by > $DURATION_TOLERANCE",
                )
            }
        }

        val normTrackTitle = normalizeString(track.trackName)
        val normInputTitle = normalizeString(inputTitle)
        val normTrackArtist = normalizeString(track.artistName)
        val normInputArtist = normalizeString(inputArtist)

        val titleOk =
            normTrackTitle.isNotEmpty() &&
                normInputTitle.isNotEmpty() &&
                (normTrackTitle.contains(normInputTitle) || normInputTitle.contains(normTrackTitle))
        val artistOk =
            normTrackArtist.isNotEmpty() &&
                normInputArtist.isNotEmpty() &&
                (normTrackArtist.contains(normInputArtist) || normInputArtist.contains(normTrackArtist))

        if (!titleOk && !artistOk) {
            throw MusixmatchError.Mismatch("Track and artist both mismatched")
        }
    }

    private fun formatLrcTimestamp(seconds: Double): String {
        val ms = (seconds * 1000).toLong().coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "[%02d:%02d.%03d]",
            ms / 60000L,
            (ms % 60000L) / 1000L,
            ms % 1000L,
        )
    }

    private fun formatSubtitlesToLrc(subtitlesJson: String): String? =
        runCatching {
            val lines = jsonFormat.decodeFromString<List<SubtitleLine>>(subtitlesJson)
            if (lines.isEmpty()) return null
            lines
                .joinToString("\n") { line ->
                    val timestamp = formatLrcTimestamp(line.time?.total ?: 0.0)
                    val text = line.text?.trim()?.ifEmpty { "♪" } ?: "♪"
                    "$timestamp $text"
                }.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun cleanPlainLyrics(rawBody: String): String {
        val lines = rawBody.lines()
        val filtered =
            lines.filterNot { line ->
                val lower = line.lowercase()
                LICENSE_FOOTER_PATTERNS.any { lower.contains(it) }
            }
        return filtered.joinToString("\n").trim()
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
    ): Result<String> {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        val cleanAlbum = album?.trim().orEmpty()

        if (cleanTitle.isBlank() || cleanArtist.isBlank()) {
            return Result.failure(IllegalArgumentException("Song title and artist are required"))
        }

        return try {
            var currentToken = getToken()
            val macroBody =
                try {
                    executeMacro(cleanTitle, cleanArtist, cleanAlbum, durationSeconds, currentToken)
                } catch (e: MusixmatchError.TokenInvalid) {
                    logger?.invoke("Musixmatch token invalid on macro, renewing once")
                    currentToken = renewToken(currentToken)
                    executeMacro(cleanTitle, cleanArtist, cleanAlbum, durationSeconds, currentToken)
                }

            val calls = macroBody.macroCalls

            val matcherCall = calls?.matcherTrackGet
            val matcherStatusCode = matcherCall?.message?.header?.statusCode ?: 0
            if (matcherStatusCode != 200) {
                throw MusixmatchError.NoMatch("Matcher status code is $matcherStatusCode")
            }

            val matcherBody = matcherCall.bodyAs<MatcherBody>(jsonFormat)
            val track = matcherBody?.track
            validateMatch(track, cleanTitle, cleanArtist, durationSeconds)

            val lyricsBody = calls?.trackLyricsGet.bodyAs<LyricsBody>(jsonFormat)
            val lyricsData = lyricsBody?.lyrics
            if (lyricsData?.isRestricted == true) {
                throw MusixmatchError.Restricted()
            }

            if (track != null && track.isInstrumental) {
                return Result.success("♪ Instrumental ♪")
            }

            // 1. Richsync TTML
            val richSyncBodyObj = calls?.trackRichsyncGet.bodyAs<RichSyncBody>(jsonFormat)
            val richSyncBodyStr =
                richSyncBodyObj?.richsync?.richsyncBody
                    ?: if (track != null && track.isRichsyncAvailable) {
                        fetchRichSyncSeparately(track.commonTrackId, track.trackLength, currentToken)
                    } else {
                        null
                    }

            if (!richSyncBodyStr.isNullOrBlank()) {
                val ttmlResult =
                    runCatching {
                        val lines = jsonFormat.decodeFromString<List<RichSyncLine>>(richSyncBodyStr)
                        MusixmatchTtml.convertRichSyncToTtml(lines)
                    }.getOrNull()

                if (!ttmlResult.isNullOrBlank()) {
                    logger?.invoke("Musixmatch richsync TTML resolved")
                    return Result.success(ttmlResult)
                }
            }

            // 2. Subtitle LRC
            val subtitlesBody = calls?.trackSubtitlesGet.bodyAs<SubtitlesBody>(jsonFormat)
            val subtitleBodyStr =
                subtitlesBody?.subtitleList?.firstOrNull()?.subtitle?.subtitleBody
            if (!subtitleBodyStr.isNullOrBlank()) {
                val lrcResult = formatSubtitlesToLrc(subtitleBodyStr)
                if (!lrcResult.isNullOrBlank()) {
                    logger?.invoke("Musixmatch subtitle LRC resolved")
                    return Result.success(lrcResult)
                }
            }

            // 3. Plain lyrics
            val lyricsBodyStr = lyricsData?.lyricsBody
            if (!lyricsBodyStr.isNullOrBlank()) {
                val plainResult = cleanPlainLyrics(lyricsBodyStr)
                if (plainResult.isNotBlank()) {
                    logger?.invoke("Musixmatch plain lyrics resolved")
                    return Result.success(plainResult)
                }
            }

            throw MusixmatchError.NoMatch("No lyrics found in any format")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int = -1,
        callback: (String) -> Unit,
    ) {
        getLyrics(
            title = title,
            artist = artist,
            album = album,
            durationSeconds = durationSeconds,
        ).onSuccess(callback)
    }
}
