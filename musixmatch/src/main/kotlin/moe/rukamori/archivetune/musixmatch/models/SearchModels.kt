/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import moe.rukamori.archivetune.musixmatch.MusixmatchError

@Serializable
data class SearchBody(
    @SerialName("track_list") val trackList: List<SearchTrackItem> = emptyList(),
)

@Serializable
data class SearchTrackItem(
    val track: Track? = null,
)

data class SearchCandidate(
    val track: Track,
    val demerit: Int,
)

internal sealed interface CandidateOutcome {
    val candidate: SearchCandidate
    val rank: Int

    data class Success(
        override val candidate: SearchCandidate,
        override val rank: Int,
        val lyrics: ResolvedLyrics,
    ) : CandidateOutcome

    data class TerminalFailure(
        override val candidate: SearchCandidate,
        override val rank: Int,
        val error: MusixmatchError,
    ) : CandidateOutcome

    data class LocalFailure(
        override val candidate: SearchCandidate,
        override val rank: Int,
        val error: Throwable,
    ) : CandidateOutcome
}

internal fun errorLabel(e: Throwable): String =
    if (e is MusixmatchError) e::class.simpleName ?: "MusixmatchError"
    else "OtherException"

