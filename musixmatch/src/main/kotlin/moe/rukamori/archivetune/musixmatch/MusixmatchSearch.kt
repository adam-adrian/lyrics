/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import moe.rukamori.archivetune.musixmatch.models.SearchBody
import moe.rukamori.archivetune.musixmatch.models.Track
import kotlin.math.abs

internal const val DURATION_TOLERANCE = 8

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
    .take(limit)
