/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.musixmatch.models.LyricsBody
import moe.rukamori.archivetune.musixmatch.models.MatcherBody
import moe.rukamori.archivetune.musixmatch.models.RichSyncBody
import moe.rukamori.archivetune.musixmatch.models.RichSyncLine
import moe.rukamori.archivetune.musixmatch.models.SubtitleLine
import moe.rukamori.archivetune.musixmatch.models.SubtitlesBody
import moe.rukamori.archivetune.musixmatch.models.bodyAs
import java.util.Locale

internal class LyricsFormat(private val jsonFormat: Json) {
    private val LICENSE_FOOTER_PATTERNS =
        listOf(
            "this lyrics is not for commercial use",
            "these lyrics are not for commercial use",
        )

    fun resolveMacroLyrics(
        calls: moe.rukamori.archivetune.musixmatch.models.MacroCalls?,
        richSyncBodyStr: String? = null,
    ): String {
        val lyricsData = calls?.trackLyricsGet.bodyAs<LyricsBody>(jsonFormat)?.lyrics
        if (lyricsData?.isRestricted == true) throw MusixmatchError.Restricted()

        if (calls?.matcherTrackGet.bodyAs<MatcherBody>(jsonFormat)?.track?.isInstrumental == true) {
            return "♪ Instrumental ♪"
        }

        val macroRichSync = calls?.trackRichsyncGet
            ?.takeIf { it.message.header.statusCode == 200 }
            .bodyAs<RichSyncBody>(jsonFormat)
            ?.richsync?.richsyncBody
        val richSync = richSyncBodyStr ?: macroRichSync
        if (!richSync.isNullOrBlank()) {
            val ttml = runCatching {
                val lines = jsonFormat.decodeFromString<List<RichSyncLine>>(richSync)
                MusixmatchTtml.convertRichSyncToTtml(lines)
            }.getOrNull()
            if (!ttml.isNullOrBlank()) return ttml
        }

        val subtitleBody = calls?.trackSubtitlesGet
            ?.takeIf { it.message.header.statusCode == 200 }
            .bodyAs<SubtitlesBody>(jsonFormat)
            ?.subtitleList?.firstOrNull()?.subtitle?.subtitleBody
        if (!subtitleBody.isNullOrBlank()) {
            formatSubtitlesToLrc(subtitleBody)?.takeIf { it.isNotBlank() }?.let { return it }
        }

        val plain = calls?.trackLyricsGet
            ?.takeIf { it.message.header.statusCode == 200 }
            .bodyAs<LyricsBody>(jsonFormat)
            ?.lyrics?.lyricsBody
        if (!plain.isNullOrBlank()) {
            cleanPlainLyrics(plain).takeIf { it.isNotBlank() }?.let { return it }
        }
        throw MusixmatchError.NoMatch("No lyrics found in any format")
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
}
