/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.musixmatch.models.SearchBody
import moe.rukamori.archivetune.musixmatch.models.Track
import kotlin.math.abs

internal const val DURATION_TOLERANCE = 8
internal const val SEARCH_FALLBACK_MAX_ATTEMPTS = 2

internal fun selectSearchCandidates(
    body: SearchBody,
    inputTitle: String,
    durationSeconds: Int,
    limit: Int,
): List<Track> = body.trackList
    .mapNotNull { it.track }
    .filter { track ->
        track.trackId > 0 && !track.isInstrumental &&
            (durationSeconds <= 0 || track.trackLength <= 0 ||
                abs(track.trackLength - durationSeconds) <= DURATION_TOLERANCE)
    }
    .distinctBy { it.trackId }
    .let { tracks ->
        val inputMarkers = versionMarkers(inputTitle)
        tracks.map { track -> RankedTrack(track, versionDemerit(track, inputMarkers)) }
            .sortedWith(compareBy<RankedTrack> { it.demerit }.thenByDescending { it.track.trackRating })
            .take(limit)
            .map { it.track }
    }


private data class RankedTrack(val track: Track, val demerit: Int)

private val qualifierPattern = Regex("""\([^)]*\)|\[[^]]*\]| [-–] (.+)$""")
private val markerPattern = Regex("""(?i)(?<![\p{L}\p{N}])(?:remix|live|covers?|instrumental|acoustic|arrangement|tribute|karaoke)(?![\p{L}\p{N}])""")

private fun versionMarkers(text: String): Int {
    var markers = 0
    for (match in markerPattern.findAll(text)) {
        markers = markers or when (match.value.lowercase(java.util.Locale.ROOT)) {
            "remix" -> 1
            "live" -> 2
            "cover", "covers" -> 4
            "instrumental" -> 8
            "acoustic" -> 16
            "arrangement" -> 32
            "tribute" -> 64
            else -> 128
        }
    }
    return markers
}

private fun versionDemerit(track: Track, inputMarkers: Int): Int {
    var markers = versionMarkers(track.albumName) and 4
    for (qualifier in qualifierPattern.findAll(track.trackName)) {
        markers = markers or versionMarkers(qualifier.value)
    }
    return if (markers and inputMarkers.inv() != 0) 1 else 0
}

internal suspend fun Musixmatch.resolveSearchFallback(
    tokenState: SearchTokenState,
    title: String,
    artist: String,
    durationSeconds: Int,
): String {
    val body = tokenState.execute { token -> executeTrackSearch(title, artist, token) }
    var lastFailure: MusixmatchError? = null
    for (candidate in selectSearchCandidates(body, title, durationSeconds, SEARCH_FALLBACK_MAX_ATTEMPTS)) {
        try {
            val calls = tokenState.execute { token -> executeMacroByTrackId(candidate.trackId, token) }.macroCalls
            val track = matchedTrack(calls, candidate.trackId)
            validateMatch(track, title, artist, durationSeconds)
            if (track.isInstrumental) throw MusixmatchError.NoMatch("Instrumental search candidate")
            return resolveTrackLyrics(calls, track, tokenState)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MusixmatchError.TokenInvalid) {
            throw e
        } catch (e: MusixmatchError.RateLimited) {
            throw e
        } catch (e: MusixmatchError) {
            lastFailure = e
        }
    }
    throw lastFailure ?: MusixmatchError.NoMatch("Search fallback returned no usable lyrics")
}

internal suspend fun Musixmatch.resolveTitleFallbackForSearch(
    tokenState: SearchTokenState,
    title: String,
    artist: String,
    album: String,
    durationSeconds: Int,
): String? = try {
    val calls = tokenState.execute { token -> executeMacro(title, artist, album, durationSeconds, token) }.macroCalls
    val track = matchedTrack(calls)
    validateMatch(track, title, artist, durationSeconds)
    if (track.isInstrumental) null else resolveTrackLyrics(calls, track, tokenState)
} catch (e: CancellationException) {
    throw e
} catch (e: MusixmatchError.TokenInvalid) {
    throw e
} catch (e: MusixmatchError.RateLimited) {
    throw e
} catch (e: MusixmatchError) {
    null
}
