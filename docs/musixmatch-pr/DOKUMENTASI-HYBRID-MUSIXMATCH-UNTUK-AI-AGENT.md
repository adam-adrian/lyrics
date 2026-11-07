# Dokumentasi Handoff: Fix Hybrid Musixmatch — Idol YOASOBI Case

**Untuk:** AI Agent penerus (local harness `adam-adrian`)  
**Dari:** Advisor (analisa perbandingan Vivi vs adam-adrian + live probe 6 Nov 2026)  
**Target repo:** `adam-adrian/lyrics` branch `feat/musixmatch`  
**Tujuan:** Tingkatkan hit-rate J-Pop/CJK tanpa mengorbankan akurasi Western  
**Status:** Siap dikerjakan — semua bukti live sudah ada  

---

## 0. Ringkasan Eksekutif

Punya kamu (`adam-adrian/lyrics` feat/musixmatch) pakai **token iOS** (`apic-appmobile` + `mac-ios-v2.0` + macro) — secara arsitektur lebih modern & stabil dari Vivi (scraping JS + HMAC). Tapi **hit-rate untuk Idol YOASOBI lebih rendah** karena:

1. **Macro gagal 404** untuk kana title, sementara `track.search` berhasil.
2. **Validasi keras** (`Mismatch` durasi >8s & `titleOk&&artistOk`) menolak kandidat yang seharusnya lolos (fallback Vivi lebih permissive).
3. **Tidak ada fallback** ke `track.subtitle.get?track_id=` setelah macro gagal — Vivi punya.

**Solusi:** Tambah **jalur fallback hybrid** — coba macro dulu (cepat), kalau 404/noMatch → `track.search` (ala Vivi) → `track.subtitle.get`/`track.richsync.get` by ID.

Dokumen ini adalah kontrak kerja untuk AI agent berikutnya.

---

## 1. Bukti Live (Jangan Diabaikan — Ini Fakta, Bukan Asumsi)

### 1.1 Input

- **URL:** `https://music.youtube.com/watch?v=m9SMT5ipbxk`
- **Parsed:** `title="アイドル"`, `artist="YOASOBI"`, `duration=213s` (03:33)
- **Token:** `261106c1074a2cb0013a4558336f84de90d0dcbf0115ce928e8358` (dari `apic-appmobile token.get` — HTTP 200, kamu sudah benar)
- **Waktu tes:** 6 Nov 2026 09:53 UTC, sandbox

### 1.2 Hasil A — Macro (punya kamu sekarang)

```
GET https://apic-appmobile.musixmatch.com/ws/1.1/macro.subtitles.get
  ?q_track=アイドル&q_artist=YOASOBI&q_artists=YOASOBI
  &namespace=lyrics_richsynched&subtitle_format=mxm&optional_calls=track.richsync
  &richsync_compact_type=words&part=track_lyrics...&app_id=mac-ios-v2.0&usertoken=...

Response header status_code 200 (HTTP 200) tapi:
  macro_calls.matcher.track.get → header 404, body ""
  macro_calls.track.subtitles.get → 404
  macro_calls.track.lyrics.get → 404
  macro_calls.track.richsync.get → 404
= Total GAGAL
```

Juga dicoba `q_track=Idol` (translation) → sama 404.

### 1.3 Hasil B — Track Search + Direct Subtitle (ala Vivi, token sama)

```
GET https://apic-appmobile.musixmatch.com/ws/1.1/track.search
  ?q_track=アイドル&q_artist=YOASOBI&f_has_lyrics=1&page_size=10

Response 200:
  track_list[0]: track_id 262827810, commontrack 158379025
    track_name アイドル, artist YOASOBI, track_length 212
    has_lyrics 1, has_subtitles 1, has_richsync 0, instrumental 0
    album THE BOOK 3, track_isrc JPP302300157

Vivi scoring:
  cleanText("アイドル") → "" (regex [^a-z0-9] buang kana)
  textScore 0, durDelta abs(212-213)=1 → score 1 → BEST

Adam validate:
  normalize("アイドル")→"アイドル", normalize("YOASOBI")→"yoasobi"
  titleOk True, artistOk True, durOk True (1 ≤8) → PASS

GET https://apic-appmobile.musixmatch.com/ws/1.1/track.subtitle.get
  ?track_id=262827810&subtitle_format=lrc
→ 200 ✅
subtitle_body:
[00:00.56] 無敵の笑顔で荒らすメディア
[00:03.11] 知りたいその秘密ミステリアス
...
[03:25.72] (Oh, my savior, my true savior, my saving grace)
subtitle_length 213, subtitle_id 41930634, lyrics_id 49662066
```

**Kesimpulan:** Track ada, lirik ada, tapi hanya bisa diambil via `track.search` → `track.subtitle.get`, bukan via `macro`.

### 1.4 Bukti Tambahan

- `track.richsync.get?commontrack_id=158379025` → 404 (benar, `has_richsync=0`)
- `apic.musixmatch.com` (base lama Vivi) dengan track_id sama → 403 (signing diperlukan, jangan pakai)

---

## 2. Root Cause Analisis

| Penyebab | Detail | Dampak Idol |
|---|---|---|
| **Namespace filter macro terlalu sempit** | `namespace=lyrics_richsynched` hanya return track dengan richsync. Idol `has_richsync=0` → 404 | Langsung gagal |
| **Tidak ada fallback search** | Punya kamu `getLyrics` hanya `executeMacro()` → `validateMatch` → fail. Vivi: `track.search` → scoring → fetch by ID | Tidak pernah coba jalur yang berhasil |
| **Durasi 8s terlalu ketat untuk J-Pop** | TV-size vs full version bisa delta 100s+ (Isol bukan kasus ini, tapi untuk J-Pop umum) | Masa depan akan hit lagi |
| **cleanText vs normalize** | Vivi buang kana → "" → dianggap exact → selalu hit. Kamu keep kana → lebih akurat tapi untuk `q_track=アイドル` vs track `アイドル` sebenarnya OK, cuma macro yang gagal | Bukan penyebab utama Idol, tapi relevan untuk judul romaji vs kana |

---

## 3. Spec Fix Hybrid (Yang Harus AI Agent Kerjakan)

### 3.1 Tujuan

Tambah fungsi `fetchViaSearchFallback()` yang dipanggil **hanya jika macro gagal** (404/NoMatch/Restricted) dan tidak menambah latency untuk kasus Western yang sudah sukses via macro.

Urutan prioritas akhir:

```
1. macro.subtitles.get (existing, 1 RTT, cepat) → jika sukses, return TTML/LRC/plain
2. track.search fallback (baru) → track.subtitle.get / track.richsync.get / track.lyrics.get → jika sukses, return
3. failure → MusixmatchError.NoMatch
```

### 3.2 Konstanta Baru

```kotlin
// di Musixmatch object
private const val DURATION_TOLERANCE_CJK = 30 // detik, untuk artist J-Pop/K-Pop
private val CJK_ARTIST_HINT = Regex("[\\p{Hiragana}\\p{Katakana}\\p{Han}]") // deteksi kana/kanji di artist/title

private fun isCjk(text: String): Boolean = CJK_ARTIST_HINT.containsMatchIn(text)
private fun durationToleranceFor(artist: String, title: String): Int =
    if (isCjk(artist) || isCjk(title)) DURATION_TOLERANCE_CJK else DURATION_TOLERANCE // 8 vs 30
```

### 3.3 Fungsi Baru — track.search

```kotlin
private suspend fun searchTracks(
    title: String,
    artist: String,
    token: String,
): List<Track> {
    val response = client.get("${BASE_URL}track.search") {
        parameter("app_id", APP_ID)
        parameter("format", "json")
        parameter("q_track", title)
        parameter("q_artist", artist)
        parameter("f_has_lyrics", 1)
        parameter("page_size", 10)
        parameter("usertoken", token)
    }
    // handle HTTP + api header status_code seperti fetchToken()
    if (!response.status.isSuccess()) return emptyList()
    val bodyText = response.bodyAsText()
    val api = jsonFormat.decodeFromString<ApiResponse<SearchBody>>(bodyText)
    if (api.message?.header?.statusCode != 200) return emptyList()
    return api.message.body?.trackList?.map { it.track } ?: emptyList()
    // SearchBody perlu dibuat: data class SearchBody(track_list: List<TrackContainer>)
    // TrackContainer sudah ada: data class TrackContainer(track: Track)
}

// Vivi scoring — permissive, tidak throw
private fun scoreTrackVivi(track: Track, inputTitle: String, inputDuration: Int): Long {
    fun cleanText(s: String): String {
        var t = s.replace(",", " ").replace("&", " ")
        t = t.replace(Regex("\\(.*?\\)"), "").replace(Regex("\\[.*?\\]"), "")
        t = t.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
        return t
    }
    val normalizedQuery = cleanText(inputTitle)
    val normalizedTrack = cleanText(track.trackName)
    val textScore = when {
        normalizedTrack == normalizedQuery -> 0L
        normalizedTrack.isNotEmpty() && normalizedQuery.isNotEmpty() && (normalizedTrack.contains(normalizedQuery) || normalizedQuery.contains(normalizedTrack)) -> 1L
        else -> 2L
    }
    val durDelta = if (inputDuration > 0 && track.trackLength > 0) abs(track.trackLength - inputDuration).toLong() else 999L
    return (textScore shl 32) + durDelta
}
```

### 3.4 Fungsi Baru — fetch by ID

```kotlin
private suspend fun fetchSubtitleByTrackId(trackId: Long, token: String): String? {
    if (trackId <=0) return null
    return try {
        val r = client.get("${BASE_URL}track.subtitle.get") {
            parameter("format", "json"); parameter("app_id", APP_ID)
            parameter("track_id", trackId); parameter("subtitle_format", "lrc")
            parameter("usertoken", token)
        }
        if (!r.status.isSuccess()) return null
        val api = jsonFormat.decodeFromString<ApiResponse<SubtitleResponse>>(r.bodyAsText())
        if (api.message?.header?.statusCode != 200) return null
        val body = api.message.body?.subtitle?.subtitleBody ?: return null
        formatSubtitlesToLrc(body) // existing
    } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
}

private suspend fun fetchLyricsByTrackId(trackId: Long, token: String): String? { /* similar track.lyrics.get */ }
private suspend fun fetchRichSyncByCommonId(commonId: Long, trackLength: Int, token: String): String? { /* existing fetchRichSyncSeparately */ }
```

### 3.5 Modifikasi getLyrics()

```kotlin
suspend fun getLyrics(title, artist, album, durationSeconds): Result<String> {
  // validasi input tetap
  return try {
    var token = getToken()
    // --- attempt 1: macro ---
    val macroResult = try {
        val macroBody = executeMacro(cleanTitle, cleanArtist, cleanAlbum, durationSeconds, token)
        // existing logic: validateMatch, check instrumental/restricted, richsync TTML → subtitle LRC → plain
        // if found, return Result.success
        processMacroBody(macroBody, ...) // ekstrak existing block ke fungsi
    } catch (e: MusixmatchError.TokenInvalid) {
        token = renewToken(token); // retry macro once
        val macroBody2 = executeMacro(cleanTitle, cleanArtist, cleanAlbum, durationSeconds, token)
        processMacroBody(macroBody2, ...)
    } catch (e: MusixmatchError) { null } // NoMatch, Mismatch, Restricted, RateLimited, ServerError → fallback
    if (macroResult != null) return Result.success(macroResult)

    // --- attempt 2: search fallback (Vivi strategy) ---
    logger?.invoke("Musixmatch fallback to track.search")
    val candidates = searchTracks(cleanTitle, cleanArtist, token)
    if (candidates.isEmpty()) throw MusixmatchError.NoMatch("search empty")

    // Skor ala Vivi, tapi juga cek dengan validateMatch longgar untuk CJK
    val tolerance = durationToleranceFor(cleanArtist, cleanTitle)
    val filtered = candidates.filter { 
        if (durationSeconds >0 && it.trackLength>0) abs(it.trackLength - durationSeconds) <= tolerance else true
        // jangan filter titleOk keras — biarkan scoring yang putuskan
    }.ifEmpty { candidates } // kalau semua terfilter, pakai semua (permissive)

    val best = filtered.minByOrNull { scoreTrackVivi(it, cleanTitle, durationSeconds) } ?: candidates[0]

    // Coba fetch by ID dengan urutan sama: subtitle LRC → richsync TTML → plain
    // Subtitle via track.subtitle.get adalah yang sukses untuk Idol
    val subtitleLrc = fetchSubtitleByTrackId(best.trackId, token)
    if (!subtitleLrc.isNullOrBlank()) return Result.success(subtitleLrc)

    val richTtml = if (best.isRichsyncAvailable) fetchRichSyncByCommonId(best.commonTrackId, best.trackLength, token)?.let {
        runCatching { MusixmatchTtml.convertRichSyncToTtml(jsonFormat.decodeFromString(it)) }.getOrNull()
    } else null
    if (!richTtml.isNullOrBlank()) return Result.success(richTtml)

    val plain = fetchLyricsByTrackId(best.trackId, token)?.let { cleanPlainLyrics(it) }
    if (!plain.isNullOrBlank()) return Result.success(plain)

    throw MusixmatchError.NoMatch("fallback also empty")
  } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}
```

**Catatan penting untuk AI agent:**
- Jangan ubah `getAllLyrics` dulu — fokus `getLyrics` single result (sesuai handoff user: dokumentasikan, nanti ditindaklanjuti).
- Untuk `getAllLyrics` multi-candidate (probe-search.mjs style) bisa jadi phase 2 — tapi spec hybrid ini sudah siapkan `searchTracks` yang reusable.
- Jangan log token/body. Cukup `logger?.invoke("fallback search: ${candidates.size} candidates, best ${best.trackName}")`.

### 3.6 Model Tambahan

```kotlin
// TokenModels.kt atau SearchModels.kt
@Serializable
data class SearchBody(
    @SerialName("track_list") val trackList: List<TrackContainer> = emptyList()
)
@Serializable
data class TrackContainer(val track: Track)
@Serializable
data class SubtitleResponse(val subtitle: SubtitleData? = null)
// SubtitleData sudah ada di MacroModels.kt
```

### 3.7 Update Test

Tambah di `MusixmatchApiTest.kt`:

- `testFallback_searchThenSubtitle_succeeds_whenMacro404` — MockEngine: macro 404 → search return Idol 212 → subtitle.get 200 → assert success LRC contains "無敵の笑顔"
- `testCjkDurationTolerance_30` — trackLength 89 vs input 213 → PASS untuk CJK, REJECT untuk non-CJK
- `testViviScoring_kanaEmptyCleanText` — cleanText("アイドル")=="" → score 0 vs cleanText("Idol")=="idol" → score 0 juga (tie → durasi putuskan)

---

## 4. Acceptance Criteria

- [ ] `gradle :musixmatch:test` tetap hijau (existing 10+ tests)
- [ ] Test baru fallback 3 di atas hijau
- [ ] Manual live test: `getLyrics("アイドル","YOASOBI",null,213)` → success LRC (bukan NoMatch) — pakai token real di sandbox atau probe script
- [ ] Western tetap lewat macro tanpa ekstra call: `getLyrics("Levitating","Dua Lipa",null,204)` → success via macro (1-2 calls, bukan 3+4)
- [ ] Tidak ada import Android, tidak ada log token, tidak ada perubahan libs.versions.toml kecuali sudah ada ktor-client-mock
- [ ] File baru/modified pakai header GPL ArchiveTune

---

## 5. Instruksi untuk AI Agent

1. Baca `docs/musixmatch-pr/Musixmatch-Implementation-Spec.md` Rev 2 + `Lyrics-Search-Wiring.md` dulu (konteks wiring ArchiveTune).
2. Baca file ini sampai habis — jangan lompat ke coding sebelum paham bukti live §1.
3. Kerjakan **berurutan**: model → searchTracks → fetchById → modifikasi getLyrics → test.
4. Setiap phase, push ke `feat/musixmatch` — CI `test_build_push.yml` akan jalan (docs-only tidak trigger, harus ada perubahan .kt).
5. Kalau `token.get` 401 captcha (hint captcha), jangan spam — reuse cached token, atau tunggu 1 menit. Live test Idol sudah pakai token `261106c...` yang masih valid 5 menit.
6. Kalau menemukan fakta berbeda (mis. track.search di apic-appmobile return 401), stop dan tanya advisor — jangan asumsi.

---

## 6. Referensi Cepat

- **Repo Vivi:** `vivizzz007/vivi-music` → `lyricsProvider/musixmatch/Musixmatch.kt` (scoring Vivi shl32)
- **Repo kamu:** `adam-adrian/lyrics` → `musixmatch/Musixmatch.kt` (macro, Mutex, MusixmatchError sealed)
- **Live token:** `261106c1074a2cb0013a4558336f84de90d0dcbf0115ce928e8358` (kadaluarsa ~10 menit, jangan hardcode)
- **Track Idol:** `track_id 262827810`, `commontrack 158379025`, `len 212`, `subtitle_id 41930634`
- **Endpoint fallback yang work:** `GET apic-appmobile/track.subtitle.get?track_id=...&subtitle_format=lrc` → 200 LRC kana
- **Yang tidak work:** `macro.subtitles.get?q_track=アイドル` → 404, `apic(track.subtitle)` → 403

---

**Selesai. Berikan file ini ke AI agent-mu — dia punya semua evidence, skor, URL, dan skeleton code untuk eksekusi.**

