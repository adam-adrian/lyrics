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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.musixmatch.models.ApiResponse
import moe.rukamori.archivetune.musixmatch.models.LyricsBody
import moe.rukamori.archivetune.musixmatch.models.MacroResponseBody
import moe.rukamori.archivetune.musixmatch.models.MacroCalls
import moe.rukamori.archivetune.musixmatch.models.MatcherBody
import moe.rukamori.archivetune.musixmatch.models.RichSyncBody
import moe.rukamori.archivetune.musixmatch.models.SearchBody
import moe.rukamori.archivetune.musixmatch.models.TokenBody
import moe.rukamori.archivetune.musixmatch.models.Track
import moe.rukamori.archivetune.musixmatch.models.bodyAs
import kotlin.math.abs

object Musixmatch {
    private const val BASE_URL = "https://apic-appmobile.musixmatch.com/ws/1.1/"
    private const val APP_ID = "mac-ios-v2.0"
    private const val SEARCH_PAGE_SIZE = 5
    private const val SEARCH_MAX_CANDIDATES = 4
    private const val SEARCH_CONCURRENCY = 2

    private val jsonFormat by lazy {
        Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    private val lyricsFormat by lazy { LyricsFormat(jsonFormat) }

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

    private val defaultClient: HttpClient by lazy { createHttpClient() }

    @Volatile
    private var overrideClient: HttpClient? = null

    internal var client: HttpClient
        get() = overrideClient ?: defaultClient
        set(value) {
            overrideClient = value
        }

    internal fun resetForTesting(engine: HttpClientEngine? = null) {
        cachedToken = null
        overrideClient = engine?.let { createHttpClient(it) }
    }

    private val tokenMutex = Mutex()

    @Volatile
    internal var cachedToken: String? = null

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

    internal suspend fun executeTrackSearch(
        title: String,
        artist: String,
        token: String,
    ): SearchBody {
        val response =
            client.get("${BASE_URL}track.search") {
                parameter("q_track", title)
                parameter("q_artist", artist)
                parameter("q_artists", artist)
                parameter("page_size", SEARCH_PAGE_SIZE)
                parameter("f_has_lyrics", 1)
                parameter("app_id", APP_ID)
                parameter("usertoken", token)
            }
        return decodeApiResponse(response, "search")
    }

    internal suspend fun executeMacroByTrackId(
        trackId: Long,
        token: String,
    ): MacroResponseBody {
        val response =
            client.get("${BASE_URL}macro.subtitles.get") {
                parameter("format", "json")
                parameter("namespace", "lyrics_richsynched")
                parameter("subtitle_format", "mxm")
                parameter("app_id", APP_ID)
                parameter("optional_calls", "track.richsync")
                parameter("richsync_compact_type", "words")
                parameter("part", "track_lyrics_translation_status,track_structure,track_performer_tagging")
                parameter("track_id", trackId)
                parameter("usertoken", token)
            }
        return decodeApiResponse(response, "macro")
    }

    private suspend inline fun <reified T> decodeApiResponse(
        response: io.ktor.client.statement.HttpResponse,
        operation: String,
    ): T {
        if (response.status == HttpStatusCode.Unauthorized) {
            throw MusixmatchError.TokenInvalid("HTTP 401 on $operation")
        }
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw MusixmatchError.RateLimited("HTTP 429 on $operation")
        }
        if (response.status.value >= 500) {
            throw MusixmatchError.ServerError(response.status.value)
        }
        if (!response.status.isSuccess()) {
            throw MusixmatchError.MalformedResponse("HTTP ${response.status.value} on $operation")
        }

        val bodyText = response.bodyAsText()
        val apiResponse =
            runCatching { jsonFormat.decodeFromString<ApiResponse<T>>(bodyText) }
                .getOrElse { throw MusixmatchError.MalformedResponse("Failed to parse $operation response") }
        val statusCode = apiResponse.message?.header?.statusCode ?: 0
        if (statusCode == 401) throw MusixmatchError.TokenInvalid("Status code 401 on $operation")
        if (statusCode == 429) throw MusixmatchError.RateLimited("Status code 429 on $operation")
        if (statusCode >= 500) throw MusixmatchError.ServerError(statusCode)
        if (statusCode != 200) throw MusixmatchError.MalformedResponse("Status code $statusCode on $operation")
        return apiResponse.message?.body ?: throw MusixmatchError.MalformedResponse("Empty $operation body")
    }

    internal suspend fun executeMacro(
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

        val body = decodeApiResponse<MacroResponseBody>(response, "macro")
        logger?.invoke("Musixmatch macro response received")
        return body
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

    internal fun validateMatch(
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
    internal fun matchedTrack(calls: MacroCalls?, expectedTrackId: Long? = null): Track {
        val matcherCall = calls?.matcherTrackGet
        val status = matcherCall?.message?.header?.statusCode ?: 0
        if (status != 200) throw MusixmatchError.NoMatch("Matcher status code is $status")
        val track = matcherCall.bodyAs<MatcherBody>(jsonFormat)?.track
            ?: throw MusixmatchError.NoMatch("No track matched")
        if (expectedTrackId != null && track.trackId != expectedTrackId) {
            throw MusixmatchError.Mismatch("Matcher track ID differs from requested ID")
        }
        return track
    }

    internal suspend fun resolveTrackLyrics(calls: MacroCalls?, track: Track, tokenState: SearchTokenState): String {
        if (calls?.trackLyricsGet.bodyAs<LyricsBody>(jsonFormat)?.lyrics?.isRestricted == true) {
            throw MusixmatchError.Restricted()
        }
        if (track.isInstrumental) return "♪ Instrumental ♪"
        val inline = calls?.trackRichsyncGet
            ?.takeIf { it.message.header.statusCode == 200 }
            .bodyAs<RichSyncBody>(jsonFormat)?.richsync?.richsyncBody
        val additional = if (inline == null && track.isRichsyncAvailable) {
            tokenState.execute { token -> fetchRichSyncSeparately(track.commonTrackId, track.trackLength, token) }
        } else null
        return lyricsFormat.resolveMacroLyrics(calls, additional)
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
            val tokenState = SearchTokenState(searchRequest { getToken() }, ::renewToken)
            val lyrics = try {
                val calls = tokenState.execute { token ->
                    executeMacro(cleanTitle, cleanArtist, cleanAlbum, durationSeconds, token)
                }.macroCalls
                val track = matchedTrack(calls)
                validateMatch(track, cleanTitle, cleanArtist, durationSeconds)
                resolveTrackLyrics(calls, track, tokenState)
            } catch (e: MusixmatchError.NoMatch) {
                resolveSearchFallback(tokenState, cleanTitle, cleanArtist, durationSeconds)
            } catch (e: MusixmatchError.Mismatch) {
                resolveSearchFallback(tokenState, cleanTitle, cleanArtist, durationSeconds)
            }
            logger?.invoke("Musixmatch lyrics format resolved")
            Result.success(lyrics)
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
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return

        val tokenState = SearchTokenState(searchRequest { getToken() }, ::renewToken)
        val searchBody = tokenState.execute { token ->
            executeTrackSearch(cleanTitle, cleanArtist, token)
        }
        val candidates = selectSearchCandidates(searchBody, cleanTitle, durationSeconds, SEARCH_MAX_CANDIDATES)

        if (candidates.isEmpty()) {
            resolveTitleFallbackForSearch(tokenState, cleanTitle, cleanArtist, album?.trim().orEmpty(), durationSeconds)
                ?.let { lyrics ->
                    currentCoroutineContext().ensureActive()
                    callback(lyrics)
                }
            return
        }

        val semaphore = Semaphore(SEARCH_CONCURRENCY)
        val callbackMutex = Mutex()
        coroutineScope {
            val searchScope = this
            candidates.forEach { candidate ->
                launch {
                    val lyrics = try {
                        semaphore.withPermit {
                            currentCoroutineContext().ensureActive()
                            val macroBody = tokenState.execute { token ->
                                executeMacroByTrackId(candidate.trackId, token)
                            }
                            currentCoroutineContext().ensureActive()
                            val calls = macroBody.macroCalls
                            val matcherCall = calls?.matcherTrackGet
                            val matcherStatus = matcherCall?.message?.header?.statusCode ?: 0
                            if (matcherStatus != 200) {
                                throw MusixmatchError.NoMatch("Matcher status code is $matcherStatus")
                            }
                            val matcherTrack = matcherCall.bodyAs<MatcherBody>(jsonFormat)?.track
                            if (matcherTrack == null || matcherTrack.trackId != candidate.trackId) {
                                throw MusixmatchError.Mismatch("Matcher track ID differs from requested ID")
                            }
                            if (matcherTrack.isInstrumental ||
                                (durationSeconds > 0 && matcherTrack.trackLength > 0 &&
                                    abs(matcherTrack.trackLength - durationSeconds) > DURATION_TOLERANCE)
                            ) {
                                throw MusixmatchError.Mismatch("Matcher metadata no longer matches candidate")
                            }
                            lyricsFormat.resolveMacroLyrics(calls)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: MusixmatchError.TokenInvalid) {
                        throw e
                    } catch (e: MusixmatchError.RateLimited) {
                        throw e
                    } catch (e: Exception) {
                        logger?.invoke("Musixmatch candidate skipped")
                        null
                    }
                    if (lyrics != null) {
                        currentCoroutineContext().ensureActive()
                        callbackMutex.withLock {
                            currentCoroutineContext().ensureActive()
                            try {
                                callback(lyrics)
                            } catch (e: CancellationException) {
                                searchScope.cancel(e)
                                throw e
                            }
                        }
                    }
                }
            }
        }
    }
}
