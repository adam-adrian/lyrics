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
import moe.rukamori.archivetune.musixmatch.models.MacroCalls
import moe.rukamori.archivetune.musixmatch.models.MacroResponseBody
import moe.rukamori.archivetune.musixmatch.models.MacroTarget
import moe.rukamori.archivetune.musixmatch.models.MatcherBody
import moe.rukamori.archivetune.musixmatch.models.ResolvedLyrics
import moe.rukamori.archivetune.musixmatch.models.RichSyncBody
import moe.rukamori.archivetune.musixmatch.models.SearchBody
import moe.rukamori.archivetune.musixmatch.models.SearchCandidate
import moe.rukamori.archivetune.musixmatch.models.SubtitleBody
import moe.rukamori.archivetune.musixmatch.models.SubtitlesBody
import moe.rukamori.archivetune.musixmatch.models.TokenBody
import moe.rukamori.archivetune.musixmatch.models.Track
import moe.rukamori.archivetune.musixmatch.models.bodyAs
import kotlin.math.abs

object Musixmatch {
    private const val BASE_URL = "https://apic-appmobile.musixmatch.com/ws/1.1/"
    private const val APP_ID = "mac-ios-v2.0"
    private const val SEARCH_PAGE_SIZE = 20
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
        val response = client.get("${BASE_URL}token.get") { parameter("app_id", APP_ID) }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.TooManyRequests) {
            throw MusixmatchError.RateLimited("HTTP ${response.status.value} on token.get")
        }
        if (response.status.value >= 500) throw MusixmatchError.ServerError(response.status.value)
        if (!response.status.isSuccess()) throw MusixmatchError.MalformedResponse("HTTP ${response.status.value} on token.get")

        val bodyText = response.bodyAsText()
        val apiResponse = runCatching { jsonFormat.decodeFromString<ApiResponse<TokenBody>>(bodyText) }
            .getOrElse { throw MusixmatchError.MalformedResponse("Failed to parse token response") }
        val statusCode = apiResponse.message?.header?.statusCode ?: 0
        if (statusCode == 401 || statusCode == 429) throw MusixmatchError.RateLimited("Status code $statusCode on token.get")
        if (statusCode >= 500) throw MusixmatchError.ServerError(statusCode)
        if (statusCode != 200) throw MusixmatchError.MalformedResponse("Status code $statusCode on token.get")

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

    internal suspend fun executeMacro(
        target: MacroTarget,
        token: String,
    ): MacroResponseBody {
        val isByName = target is MacroTarget.ByName
        if (isByName) logger?.invoke("Musixmatch macro request started")
        val response =
            client.get("${BASE_URL}macro.subtitles.get") {
                parameter("format", "json")
                parameter("namespace", "lyrics_richsynched")
                parameter("subtitle_format", "mxm")
                parameter("app_id", APP_ID)
                parameter("optional_calls", "track.richsync")
                parameter("richsync_compact_type", "words")
                parameter("usertoken", token)

                when (target) {
                    is MacroTarget.ByName -> {
                        parameter("q_track", target.title)
                        parameter("q_artist", target.artist)
                        if (!target.album.isNullOrBlank()) {
                            parameter("q_album", target.album)
                        }
                        if (target.durationSeconds > 0) {
                            parameter("q_duration", target.durationSeconds)
                            parameter("f_subtitle_length", target.durationSeconds)
                        }
                    }
                    is MacroTarget.ById -> {
                        parameter("track_id", target.trackId)
                    }
                }
            }

        val body = decodeApiResponse<MacroResponseBody>(response, "macro")
        if (isByName) logger?.invoke("Musixmatch macro response received")
        return body
    }

    private suspend inline fun <reified T> decodeApiResponse(
        response: io.ktor.client.statement.HttpResponse,
        operation: String,
    ): T {
        if (response.status == HttpStatusCode.Unauthorized) throw MusixmatchError.TokenInvalid("HTTP 401 on $operation")
        if (response.status == HttpStatusCode.TooManyRequests) throw MusixmatchError.RateLimited("HTTP 429 on $operation")
        if (response.status.value >= 500) throw MusixmatchError.ServerError(response.status.value)
        if (!response.status.isSuccess()) throw MusixmatchError.MalformedResponse("HTTP ${response.status.value} on $operation")

        val bodyText = response.bodyAsText()
        val apiResponse = runCatching { jsonFormat.decodeFromString<ApiResponse<T>>(bodyText) }
            .getOrElse { throw MusixmatchError.MalformedResponse("Failed to parse $operation response") }
        val statusCode = apiResponse.message?.header?.statusCode ?: 0
        if (statusCode == 401) throw MusixmatchError.TokenInvalid("Status code 401 on $operation")
        if (statusCode == 429) throw MusixmatchError.RateLimited("Status code 429 on $operation")
        if (statusCode >= 500) throw MusixmatchError.ServerError(statusCode)
        if (statusCode != 200) throw MusixmatchError.MalformedResponse("Status code $statusCode on $operation")
        return apiResponse.message?.body ?: throw MusixmatchError.MalformedResponse("Empty $operation body")
    }

    private suspend inline fun <reified T> decodeStandaloneResponse(
        response: io.ktor.client.statement.HttpResponse,
        operation: String,
    ): T? {
        if (response.status == HttpStatusCode.Unauthorized) throw MusixmatchError.TokenInvalid("HTTP 401 on $operation")
        if (response.status == HttpStatusCode.TooManyRequests) throw MusixmatchError.RateLimited("HTTP 429 on $operation")
        if (!response.status.isSuccess()) return null
        val bodyText = response.bodyAsText()
        val apiResponse = runCatching { jsonFormat.decodeFromString<ApiResponse<T>>(bodyText) }.getOrNull() ?: return null
        val status = apiResponse.message?.header?.statusCode
        if (status == 401) throw MusixmatchError.TokenInvalid("Status code 401 on $operation")
        if (status == 429) throw MusixmatchError.RateLimited("Status code 429 on $operation")
        if (status != 200) return null
        return apiResponse.message?.body
    }

    private suspend fun fetchRichSyncSeparately(commonTrackId: Long, trackLength: Int, token: String): String? {
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
            decodeStandaloneResponse<RichSyncBody>(response, "richsync")?.richsync?.richsyncBody
        } catch (e: CancellationException) {
            throw e
        } catch (e: MusixmatchError.TokenInvalid) {
            throw e
        } catch (e: MusixmatchError.RateLimited) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchSubtitleSeparately(trackId: Long, trackLength: Int, token: String): String? {
        if (trackId <= 0) return null
        return try {
            logger?.invoke("Musixmatch separate subtitle request started")
            val response =
                client.get("${BASE_URL}track.subtitle.get") {
                    parameter("format", "json")
                    parameter("subtitle_format", "mxm")
                    parameter("app_id", APP_ID)
                    parameter("track_id", trackId)
                    if (trackLength > 0) parameter("f_subtitle_length", trackLength)
                    parameter("usertoken", token)
                }
            decodeStandaloneResponse<SubtitleBody>(response, "subtitle")?.subtitle?.subtitleBody
        } catch (e: CancellationException) {
            throw e
        } catch (e: MusixmatchError.TokenInvalid) {
            throw e
        } catch (e: MusixmatchError.RateLimited) {
            throw e
        } catch (e: Exception) {
            null
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

    internal suspend fun resolveRequest(target: MacroTarget, tokenState: SearchTokenState): ResolvedLyrics {
        val calls = tokenState.execute { token -> executeMacro(target, token) }.macroCalls
        val track = when (target) {
            is MacroTarget.ByName -> {
                val matched = matchedTrack(calls)
                validateMatch(matched, target.title, target.artist, target.durationSeconds)
                matched
            }
            is MacroTarget.ById -> {
                val matched = matchedTrack(calls, target.trackId)
                if (target.title.isNotEmpty() || target.artist.isNotEmpty()) {
                    validateMatch(matched, target.title, target.artist, target.durationSeconds)
                } else if (target.durationSeconds > 0 && matched.trackLength > 0 &&
                    abs(matched.trackLength - target.durationSeconds) > DURATION_TOLERANCE
                ) {
                    throw MusixmatchError.Mismatch("Matcher metadata no longer matches candidate")
                }
                matched
            }
        }
        if (calls?.trackLyricsGet.bodyAs<LyricsBody>(jsonFormat)?.lyrics?.isRestricted == true) {
            throw MusixmatchError.Restricted()
        }
        if (track.isInstrumental) throw MusixmatchError.Instrumental()

        val richsync = if (calls?.trackRichsyncGet?.message?.header?.statusCode == 200) {
            calls.trackRichsyncGet.bodyAs<RichSyncBody>(jsonFormat)?.richsync?.richsyncBody
        } else if (track.isRichsyncAvailable) {
            tokenState.execute { token -> fetchRichSyncSeparately(track.commonTrackId, track.trackLength, token) }
        } else null
        lyricsFormat.tryTtml(richsync)?.let { return ResolvedLyrics(it, 2) }

        val subtitle = if (calls?.trackSubtitlesGet?.message?.header?.statusCode == 200) {
            calls.trackSubtitlesGet.bodyAs<SubtitlesBody>(jsonFormat)
                ?.subtitleList?.firstOrNull()?.subtitle?.subtitleBody
        } else if (track.isSubtitlesAvailable) {
            tokenState.execute { token -> fetchSubtitleSeparately(track.trackId, track.trackLength, token) }
        } else null
        lyricsFormat.tryLrc(subtitle)?.let { return ResolvedLyrics(it, 1) }

        val plain = calls?.trackLyricsGet
            ?.takeIf { it.message.header.statusCode == 200 }
            .bodyAs<LyricsBody>(jsonFormat)
            ?.lyrics?.lyricsBody
        lyricsFormat.tryPlain(plain)?.let { return ResolvedLyrics(it, 0) }

        throw MusixmatchError.NoMatch("No lyrics found in any format")
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
                try {
                    resolveRequest(
                        MacroTarget.ByName(cleanTitle, cleanArtist, cleanAlbum, durationSeconds),
                        tokenState,
                    ).lyrics
                } catch (e: MusixmatchError.Instrumental) {
                    "♪ Instrumental ♪"
                }
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
        logSearchFunnel(searchBody, durationSeconds, candidates.size)

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
                            resolveRequest(
                                MacroTarget.ById(candidate.track.trackId, durationSeconds = durationSeconds),
                                tokenState,
                            ).lyrics
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
