/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.musixmatch

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.musixmatch.models.ApiResponse
import moe.rukamori.archivetune.musixmatch.models.Track

sealed interface MacroTarget {
    data class ByName(
        val title: String,
        val artist: String,
        val album: String = "",
        val durationSeconds: Int = -1,
    ) : MacroTarget

    data class ById(
        val trackId: Long,
        val title: String = "",
        val artist: String = "",
        val durationSeconds: Int = -1,
    ) : MacroTarget
}

data class ResolvedLyrics(
    val lyrics: String,
    val quality: Int = 0,
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

internal suspend inline fun <reified T> decodeApiResponse(
    response: HttpResponse,
    jsonFormat: Json,
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

internal suspend inline fun <reified T> decodeStandaloneResponse(
    response: HttpResponse,
    jsonFormat: Json,
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
