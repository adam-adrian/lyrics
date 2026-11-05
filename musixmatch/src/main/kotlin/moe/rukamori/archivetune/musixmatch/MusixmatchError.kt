/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

internal sealed class MusixmatchError(message: String) : Exception(message) {
    class TokenInvalid(detail: String) : MusixmatchError("Token invalid: $detail")
    class RateLimited(detail: String) : MusixmatchError("Rate limited: $detail")
    class NoMatch(detail: String) : MusixmatchError("No match: $detail")
    class Restricted : MusixmatchError("Lyrics restricted")
    class ServerError(code: Int) : MusixmatchError("Server error: $code")
    class MalformedResponse(detail: String) : MusixmatchError("Malformed response: $detail")
    class Mismatch(detail: String) : MusixmatchError("Match rejected: $detail")
}
