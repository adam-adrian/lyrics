/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import moe.rukamori.archivetune.musixmatch.models.SearchBody
import moe.rukamori.archivetune.musixmatch.models.SearchTrackItem
import moe.rukamori.archivetune.musixmatch.models.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Test

class MusixmatchCandidateRankingTest {
    private fun selected(title: String, vararg tracks: Track, limit: Int = 4): List<Long> =
        selectSearchCandidates(SearchBody(tracks.map { SearchTrackItem(it) }), title, 100, limit).map { it.trackId }

    private fun track(id: Long, title: String = "Song", rating: Int = 0, album: String = "", length: Int = 100) =
        Json { ignoreUnknownKeys = true }.decodeFromString<Track>(
            """{"track_id":$id,"track_name":${Json.encodeToString(title)},"artist_name":"Artist","track_length":$length,"track_rating":$rating,"album_name":${Json.encodeToString(album)}}"""
        )

    @Test
    fun canonicalBeatsHigherRatedVersionBeforeCap() {
        assertEquals(listOf(2L, 3L), selected("Song", track(1, "Song (Live)", 100), track(2, rating = 80), track(3, rating = 20), limit = 2))
    }

    @Test
    fun ratingBreaksTiesWithinSameVersionTier() {
        assertEquals(listOf(3L, 1L, 2L), selected("Song", track(1, rating = 20), track(2, rating = 10), track(3, rating = 90)))
    }

    @Test
    fun equalKeysKeepResponseOrderRatherThanTrackIdOrder() {
        assertEquals(listOf(9L, 2L, 7L), selected("Song", track(9), track(2), track(7)))
    }

    @Test
    fun everyQualifierMarkerGetsDemerit() {
        for (marker in listOf("remix", "live", "cover", "instrumental", "acoustic", "arrangement", "tribute", "karaoke")) {
            assertEquals(listOf(2L, 1L), selected("Song", track(1, "Song ($marker)", 100), track(2, rating = 10)))
        }
    }

    @Test
    fun bracketsAndBothDashSeparatorsAreQualifiers() {
        for (name in listOf("Song [Live]", "Song - Live", "Song – Live")) {
            assertEquals(listOf(2L, 1L), selected("Song", track(1, name, 100), track(2, rating = 10)))
        }
    }

    @Test
    fun explicitlyRequestedVersionIsNotPenalized() {
        assertEquals(listOf(1L, 2L), selected("Song (Slow Jam Remix)", track(1, "Song (Bedroom Remix)", 100), track(2, rating = 10)))
    }

    @Test
    fun requestedRemixDoesNotExcuseUnrequestedLiveMarker() {
        assertEquals(listOf(2L, 1L), selected("Song (Remix)", track(1, "Song (Live Remix)", 100), track(2, rating = 10)))
    }

    @Test
    fun normalTitlesAndPartialMarkerWordsAreNotQualifiers() {
        for (name in listOf("Live Forever", "Alive", "Song (Olive Sessions)", "Song (Discovery Edition)")) {
            assertEquals(listOf(1L, 2L), selected("Song", track(1, name, 100), track(2, rating = 10)))
        }
    }

    @Test
    fun albumCoverMarkerIsPenalizedButOtherAlbumMarkersAreIgnored() {
        assertEquals(listOf(2L, 1L), selected("Song", track(1, rating = 100, album = "Official Covers, Vol.1"), track(2, rating = 10)))
        assertEquals(listOf(1L, 2L), selected("Song", track(1, rating = 100, album = "Live at Wembley"), track(2, rating = 10)))
        assertEquals(listOf(1L, 2L), selected("Song", track(1, rating = 100, album = "Discovery"), track(2, rating = 10)))
    }

    @Test
    fun gateAndDedupStillPrecedeRatingAndLimit() {
        assertEquals(listOf(2L, 3L), selected("Song", track(1, rating = 100, length = 109), track(2, rating = 10), track(2, rating = 100), track(3, rating = 5), limit = 2))
    }
}
