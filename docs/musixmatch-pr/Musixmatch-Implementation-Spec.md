# Spec Implementasi: Provider Musixmatch

**Dokumen ini adalah kontrak kerja untuk AI harness lokal.** Penulis: advisor (Arena). Implementer: harness lokal `adam-adrian`.

| | |
|---|---|
| Target repo | `adam-adrian/lyrics` |
| Branch | `feat/musixmatch` (sudah ada, masih di `8c00f932` = sama dengan `main`, belum ada pekerjaan) |
| Upstream PR | `rukamori/lyrics` |
| Basis desain | `musixmatch_archivetune_tracker (3).md` + `Musixmatch-Tracker-Review.md` |
| Toolchain | JDK 21 (temurin), **Gradle 8.10**, Kotlin 2.1.0, Ktor 3.1.0 |
| Tanggal | 5 Nov 2026 |
| **Revisi** | **REV 2** — memuat 2 koreksi dari smoke test live (`implementer-notes.md`) |
| Verifikasi | GitHub Actions fork, bukan build lokal (lihat §0.1) |

### Status verifikasi lapangan (smoke test 5 Nov 2026)

Sudah terbukti terhadap API nyata, bukan asumsi:

| Klaim spec | Status |
|---|---|
| `token.get` + header `X-User-Agent` iOS | ✅ HTTP 200, token 54 karakter |
| Richsync inline via `optional_calls` + `richsync_compact_type` | ✅ satu network call |
| Spasi sebagai text node, bukan `<span>` | ✅ XML valid, kompatibel `appendInterSpanText()` |
| Durasi kata terakhir `te - (ts + o)` | ✅ presisi, tanpa overlap |
| Guard instrumental | ✅ `"♪ Instrumental ♪"`, richsync tidak dipanggil |
| Guard matcher (durasi & teks) | ✅ `Mismatch` / `NoMatch` sesuai harapan |
| Model generik `MacroCall<T>` (Rev 1) | ❌ **salah** — lihat §3.5 |
| Flag boolean sebagai `Boolean` (Rev 1) | ❌ **salah** — lihat §3.5 |

---

## 0. Cara memakai dokumen ini

Kerjakan **per phase**, berurutan. Setiap phase punya *Acceptance* yang harus dipenuhi sebelum lanjut. Jangan lompat ke Phase 6 sebelum Phase 5 hijau.

Kalau ada konflik antara dokumen ini dan tracker lama, **dokumen ini yang menang** — ini sudah memuat 4 koreksi hasil verifikasi terhadap kode aktual.

Kalau menemukan fakta kode yang berbeda dari yang tertulis di sini, **berhenti dan laporkan**, jangan berasumsi. Advisor akan verifikasi.

### Jangan lakukan

- Jangan pakai `./gradlew` di submodule — **tidak ada wrapper di sana**. Pakai `gradle` sistem (CI pakai 8.10).
- Jangan import apa pun dari Android di module ini.
- Jangan commit token apa pun, termasuk token publik Spicetify.
- Jangan log token, full URL, response body, atau isi lirik.
- Jangan pakai DataStore/SharedPreferences di module ini.
- Jangan pretty-print XML di dalam `<p>` (lihat §5.3 — ini merusak spasi).

### 0.1 Cara verifikasi: GitHub Actions adalah mesin build

**Baik advisor maupun implementer tidak punya mesin yang sanggup menjalankan Gradle.** Laptop implementer kurang bertenaga; sandbox advisor pernah thrashing saat `gradle build` penuh.

Solusinya sudah tersedia di repo: `.github/workflows/test_build_push.yml` menjalankan **JDK 21 + Gradle 8.10** di `ubuntu-latest`, gratis, dengan trigger `on: push` ke **semua branch**.

Alur kerja:

```
implementer push ke feat/musixmatch
        ↓
GitHub Actions fork menjalankan `gradle build test`
        ↓
advisor baca hasil via API publik, review diff
```

Yang perlu dilakukan sekali: **pastikan tab Actions di fork `adam-adrian/lyrics` di-enable.** Fork kadang datang dengan Actions nonaktif.

Dua catatan penting:

- Workflow punya `paths-ignore: ["**.md"]`. **Push docs-only tidak memicu CI** — itu sebabnya commit `896fb62` belum menghasilkan run apa pun (`total_count: 0`). CI baru jalan saat ada perubahan kode.
- Advisor bisa membaca status tanpa autentikasi:
  `https://api.github.com/repos/adam-adrian/lyrics/actions/runs`

Jadi **tidak ada yang perlu menjalankan Gradle secara lokal.** Kalau tetap ingin mencoba di mesin lemah, gunakan batas memori ketat dan satu module saja:

```bash
gradle --no-daemon --no-parallel --max-workers=1 \
  -Dorg.gradle.jvmargs="-Xmx900m -XX:MaxMetaspaceSize=384m" \
  -Dkotlin.compiler.execution.strategy=in-process \
  :musixmatch:test
```

`kotlin.compiler.execution.strategy=in-process` adalah penghematan terbesar — menghilangkan daemon Kotlin terpisah.

---

## 1. Keputusan yang sudah dikunci

| Topik | Keputusan |
|---|---|
| Lokasi | Module baru `musixmatch/` di submodule `lyrics` |
| Package | `moe.rukamori.archivetune.musixmatch` |
| Object | `Musixmatch` |
| HTTP engine | OkHttp, `expectSuccess = false`, `bodyAsText()` + decode manual |
| Output | richsync → **TTML**; subtitle → **LRC**; plain → text |
| Prioritas | richsync TTML → subtitle LRC → plain → failure |
| Token | Runtime `token.get`, in-memory cache, `Mutex` + `@Volatile` |
| Spasi richsync | **Text node, BUKAN `<span>`** |
| Richsync fetch | Inline via `optional_calls` (primary), endpoint terpisah (fallback) |
| Waktu internal | `Double` detik, selalu |
| Matcher guard | Toleransi durasi 8 detik + cek kemiripan teks |

---

## 2. Phase 1 — Skeleton module

### 2.1 File yang dibuat

```
musixmatch/
├── build.gradle.kts
└── src/
    ├── main/kotlin/moe/rukamori/archivetune/musixmatch/
    │   ├── Musixmatch.kt
    │   ├── MusixmatchTtml.kt
    │   ├── MusixmatchError.kt
    │   └── models/
    │       ├── CommonModels.kt
    │       ├── TokenModels.kt
    │       ├── MacroModels.kt
    │       └── RichSyncModels.kt
    └── test/kotlin/moe/rukamori/archivetune/musixmatch/
        ├── MusixmatchTtmlTest.kt
        ├── MusixmatchApiTest.kt
        └── fixtures/  (resource, lihat §6.1)
```

### 2.2 `musixmatch/build.gradle.kts`

Salin pola `youlyplus/build.gradle.kts`, tambah test deps:

```kotlin
plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
}
```

### 2.3 `gradle/libs.versions.toml` — tambah SATU baris

`ktor-client-mock` **belum ada** di catalog. Tambahkan di `[libraries]`:

```toml
ktor-client-mock = { group = "io.ktor", name = "ktor-client-mock", version.ref = "ktor" }
```

Jangan ubah versi apa pun yang sudah ada.

### 2.4 `settings.gradle.kts` (root submodule)

Tambah setelah `include(":youlyplus")`:

```kotlin
include(":musixmatch")
```

### 2.5 License header

**Setiap file `.kt` baru** wajib diawali header yang sama dengan provider lain:

```kotlin
/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
```

### Acceptance Phase 1

- `gradle :musixmatch:build` sukses
- `gradle build test` di root tetap sukses (tidak merusak module lain)
- Tidak ada import Android

---

## 3. Phase 2 — Klien HTTP + token

### 3.1 Konstanta & header

```kotlin
private const val BASE_URL = "https://apic-appmobile.musixmatch.com/ws/1.1/"
private const val APP_ID = "mac-ios-v2.0"
```

Header (di `defaultRequest`, pakai `headers.append` sesuai gaya YouLyPlus):

```kotlin
headers.append("Accept", "application/json")
headers.append("Accept-Language", "en-US,en;q=0.9")
headers.append("X-Cookie", "x-mxm-token-guid=")
headers.append("x-mxm-app-version", "10.1.1")
headers.append("X-User-Agent", "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0")
```

**`X-User-Agent` adalah header yang dibaca server**, bukan `User-Agent`. Jangan ganti jadi `User-Agent` biasa. Jangan set `Host` manual (Ktor yang urus).

Timeout ikut YouLyPlus: request 20000, connect 15000, socket 20000.

Json config:

```kotlin
private val jsonFormat by lazy {
    Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
}
```

### 3.2 Token — single-flight

```kotlin
private val tokenMutex = Mutex()

@Volatile
private var cachedToken: String? = null

private suspend fun getToken(): String {
    cachedToken?.let { return it }
    return tokenMutex.withLock {
        cachedToken ?: fetchToken().also { cachedToken = it }
    }
}

private suspend fun renewToken(previous: String): String =
    tokenMutex.withLock {
        val current = cachedToken
        if (current != null && current != previous) current
        else fetchToken().also { cachedToken = it }
    }
```

`Mutex` untuk single-flight, `@Volatile` untuk visibility fast-path. **Keduanya perlu** — jangan ganti salah satunya.

`fetchToken()`:
- `GET token.get` dengan `app_id`
- Cek HTTP status **dan** `message.header.status_code`
- `401` di endpoint ini → `MusixmatchError.RateLimited`, **jangan retry**
- Token kosong / all-zeros / diawali `UpgradeOnly` → `TokenInvalid`
- Sukses → return `message.body.user_token`

### 3.3 Klasifikasi error — `MusixmatchError.kt`

```kotlin
internal sealed class MusixmatchError(message: String) : Exception(message) {
    class TokenInvalid(detail: String) : MusixmatchError("Token invalid: $detail")
    class RateLimited(detail: String) : MusixmatchError("Rate limited: $detail")
    class NoMatch(detail: String) : MusixmatchError("No match: $detail")
    class Restricted : MusixmatchError("Lyrics restricted")
    class ServerError(code: Int) : MusixmatchError("Server error: $code")
    class MalformedResponse(detail: String) : MusixmatchError("Malformed response: $detail")
    class Mismatch(detail: String) : MusixmatchError("Match rejected: $detail")
}
```

**Cek status di DUA level.** Musixmatch sering balas HTTP 200 dengan `message.header.status_code = 401`.

| Kondisi | Klasifikasi | Aksi |
|---|---|---|
| `401` dari `token.get` | RateLimited | Failure, jangan retry |
| `401` dari `macro.subtitles.get` | TokenInvalid | Renew sekali, retry sekali |
| `429` | RateLimited | Failure, **jangan** renew token |
| `5xx` | ServerError | Failure, **jangan** renew token |
| `404` / matcher status ≠ 200 | NoMatch | Failure |
| `lyrics.restricted == true` | Restricted | Failure |
| Macro gagal decode total | MalformedResponse | Failure, **jangan** renew token |
| `richsync_body` gagal decode | — | Fallback ke subtitle |
| `subtitle_body` gagal decode | — | Fallback ke plain |

Maksimal **satu** renew + **satu** retry per pemanggilan `getLyrics`. Jangan loop.

### 3.4 Macro request

`GET macro.subtitles.get` dengan parameter:

```
format=json
namespace=lyrics_richsynched
subtitle_format=mxm
app_id=mac-ios-v2.0
optional_calls=track.richsync
richsync_compact_type=words
part=track_lyrics_translation_status,track_structure,track_performer_tagging
q_track=<title>
q_artist=<artist>
q_artists=<artist>
usertoken=<token>
```

Opsional, hanya kalau ada nilai:

```
q_album=<album>            (kalau album tidak blank)
q_duration=<duration>      (kalau duration > 0)
f_subtitle_length=<floor(duration)>  (kalau duration > 0)
```

**`optional_calls` dan `richsync_compact_type` dua-duanya wajib.** `namespace` saja tidak menghasilkan richsync. `richsync_compact_type=words` yang menjamin bentuk `{c, o}`; tanpa itu model bisa diam-diam kosong karena `ignoreUnknownKeys = true`.

### 3.5 Model macro — REV 2, hasil smoke test live

> **Revisi ini berasal dari `implementer-notes.md` (smoke test 5 Nov 2026).** Desain generik `MacroCall<T>` pada Rev 1 **salah** dan akan gagal di produksi. Dua temuan lapangan menggugurkannya.

#### Temuan A — `body` bisa berupa `[]`, bukan `{}`

Kalau sub-call tidak punya data, Musixmatch mengembalikan `body: []` (array kosong), bukan object atau `null`. Model generik yang mengharapkan object akan gagal parse — dan karena semua sub-call ada dalam satu payload, **satu sub-call kosong bisa merusak seluruh response**.

**Solusi: jangan pakai generik.** Simpan `body` sebagai `JsonElement?`, decode per sub-call secara terpisah:

```kotlin
@Serializable
data class MacroCall(
    val message: MacroMessage = MacroMessage(),
)

@Serializable
data class MacroMessage(
    val header: MusixmatchHeader = MusixmatchHeader(),
    val body: JsonElement? = null,
)

@Serializable
data class MacroCalls(
    @SerialName("matcher.track.get") val matcherTrackGet: MacroCall? = null,
    @SerialName("track.lyrics.get") val trackLyricsGet: MacroCall? = null,
    @SerialName("track.subtitles.get") val trackSubtitlesGet: MacroCall? = null,
    @SerialName("track.richsync.get") val trackRichsyncGet: MacroCall? = null,
)

// Decode terisolasi: body non-object → null, bukan exception.
inline fun <reified T> MacroCall?.bodyAs(json: Json): T? {
    val body = this?.message?.body
    if (body == null || body !is JsonObject) return null
    return runCatching { json.decodeFromJsonElement<T>(body) }.getOrNull()
}
```

Keuntungan dibanding desain generik Rev 1:

- `body: []` → bukan `JsonObject` → `null` → fallback chain lanjut normal
- Satu sub-call rusak **tidak** menjatuhkan sub-call lain
- Tidak perlu custom serializer
- Status header tetap bisa dicek per sub-call sebelum decode

Sub-call yang teramati di respons nyata: `matcher.track.get`, `track.richsync.get`, `track.subtitles.get`, `track.lyrics.get`, `track.snippet.get`, `userblob.get`. **Abaikan** dua yang terakhir — `ignoreUnknownKeys = true` sudah menangani.

#### Temuan B — semua flag boolean adalah Integer `0`/`1`

Musixmatch mengembalikan `instrumental`, `has_richsync`, `has_subtitles`, `has_lyrics`, `has_lyrics_crowd`, `restricted` sebagai **integer**, bukan `true`/`false`.

Mendeklarasikannya `Boolean` menghasilkan `SerializationException: Expected boolean, found 1`. Perlu diketahui: **`coerceInputValues = true` TIDAK menolong** — opsi itu hanya menangani `null` → default dan enum tak dikenal, bukan `Int` → `Boolean`. `isLenient` juga tidak.

**Aturan umum: setiap field yang tampak boolean di API Musixmatch harus dideklarasikan `Int = 0`.** Sediakan computed property untuk keterbacaan:

```kotlin
@Serializable
data class Track(
    @SerialName("track_id") val trackId: Long = 0,
    @SerialName("commontrack_id") val commonTrackId: Long = 0,
    @SerialName("track_name") val trackName: String = "",
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("track_length") val trackLength: Int = 0,
    @SerialName("has_richsync") val hasRichsync: Int = 0,
    @SerialName("has_subtitles") val hasSubtitles: Int = 0,
    @SerialName("has_lyrics") val hasLyrics: Int = 0,
    @SerialName("has_lyrics_crowd") val hasLyricsCrowd: Int = 0,
    @SerialName("instrumental") val instrumental: Int = 0,
) {
    val isInstrumental: Boolean get() = instrumental == 1
    val isRichsyncAvailable: Boolean get() = hasRichsync == 1
    val isSubtitlesAvailable: Boolean get() = hasSubtitles == 1
    val isLyricsAvailable: Boolean get() = hasLyrics == 1 || hasLyricsCrowd == 1
}
```

Berlaku juga untuk `lyrics.restricted` → `Int = 0`, computed `isRestricted`.

**Konsekuensi ke §4.1 dan §4.2:** semua pengecekan pakai computed property (`track.isInstrumental`, `track.isRichsyncAvailable`), bukan perbandingan `== true`.

Path yang harus diverifikasi terhadap fixture:

```
message.body.macro_calls["matcher.track.get"].message.body.track
  → track_id, commontrack_id, track_name, artist_name, track_length,
    has_richsync, has_subtitles, has_lyrics, has_lyrics_crowd, instrumental
message.body.macro_calls["track.subtitles.get"].message.body.subtitle_list[0].subtitle.subtitle_body
message.body.macro_calls["track.lyrics.get"].message.body.lyrics.lyrics_body
message.body.macro_calls["track.richsync.get"].message.body.richsync.richsync_body
```

Semua field pakai default value + nullable. `subtitle_body` dan `richsync_body` adalah **string berisi JSON** → decode dua lapis dengan `runCatching`.

### Acceptance Phase 2

- `getToken()` sukses dengan MockEngine
- Dua `getToken()` paralel → hanya **satu** request ke `token.get`
- HTTP 200 + body `status_code 401` terdeteksi sebagai error
- `429` dan `5xx` **tidak** memanggil `token.get`
- Macro malformed → failure, tanpa renew token

---

## 4. Phase 3 — Validasi match, subtitle, plain

### 4.1 Guard validasi match — WAJIB

ArchiveTune tidak punya Spotify ID, jadi match Musixmatch bersifat fuzzy. Tanpa guard, user bisa dapat lirik lagu yang salah.

Jalankan setelah `matcher.track.get` status 200:

```
1. Guard durasi (hard gate):
   kalau durationInput > 0 DAN track_length > 0:
     tolak jika |track_length - durationInput| > 8

2. Guard teks (soft gate):
   normalisasi = lowercase, buang non-alphanumeric, collapse whitespace
   titleOk  = normalized(track_name) saling-contain dengan normalized(titleInput)
   artistOk = normalized(artist_name) saling-contain dengan normalized(artistInput)
   tolak HANYA jika titleOk == false DAN artistOk == false
```

Toleransi 8 detik konsisten dengan KuGou. Guard teks sengaja longgar supaya tidak menolak remix / featured artist secara keliru.

Kalau ditolak → `MusixmatchError.Mismatch` → `Result.failure`. Biarkan provider lain mengambil.

### 4.2 Instrumental

Kalau `track.instrumental == true`:
- Jangan panggil richsync
- Return `Result.success("♪ Instrumental ♪")`

Konsisten dengan Spicetify, dan lebih informatif daripada failure.

### 4.3 Subtitle → LRC

`subtitle_body` adalah JSON string:

```json
[{ "text": "Hello world", "time": { "total": 12.34 } }]
```

Format timestamp **ikut konvensi repo** (`YouLyPlus.formatLrcTimestamp`): 3 digit milidetik, bukan 2 digit centisecond seperti di tracker lama.

```kotlin
private fun formatLrcTimestamp(seconds: Double): String {
    val ms = (seconds * 1000).toLong().coerceAtLeast(0L)
    return String.format(
        Locale.US, "[%02d:%02d.%03d]",
        ms / 60000L, (ms % 60000L) / 1000L, ms % 1000L,
    )
}
```

Baris dengan `text` blank → tetap emit timestamp dengan teks `♪` (jangan dibuang, menjaga jeda).

### 4.4 Plain

`lyrics_body` → trim. **Periksa dan buang footer lisensi Musixmatch** bila ada (baris yang mengandung `This Lyrics is NOT for Commercial use` atau serupa). Verifikasi bentuk persisnya dari respons nyata; kalau tidak ada, tidak perlu apa-apa.

### 4.5 Public API

```kotlin
suspend fun getLyrics(
    title: String,
    artist: String,
    album: String? = null,
    durationSeconds: Int = -1,
): Result<String>

suspend fun getAllLyrics(
    title: String,
    artist: String,
    album: String? = null,
    durationSeconds: Int = -1,
    callback: (String) -> Unit,
)
```

`getAllLyrics` MVP: delegasikan seperti YouLyPlus —
`getLyrics(...).onSuccess(callback)`. Emit multi-format ditunda ke Phase 7.

Input validation: trim, tolak kalau title atau artist blank → `Result.failure(IllegalArgumentException(...))`.

Struktur error handling:

```kotlin
return try {
    Result.success(...)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
```

### Acceptance Phase 3

- Match selisih durasi 30 detik → failure
- Match selisih 3 detik → lolos
- `durationSeconds = -1` → guard durasi tidak diterapkan
- Title & artist dua-duanya beda jauh → failure
- Hanya artist beda (remix) → lolos
- Instrumental → `"♪ Instrumental ♪"`, tanpa panggil richsync
- Subtitle → LRC valid, timestamp 3 digit
- Richsync gagal decode → jatuh ke LRC, bukan failure

---

## 5. Phase 4 — Richsync → TTML

Ini bagian paling rawan. Kerjakan dengan test dulu (Phase 5 fixture sudah disediakan di §6.2).

### 5.1 Model

```kotlin
@Serializable
data class RichSyncLine(
    @SerialName("ts") val startTime: Double = 0.0,
    @SerialName("te") val endTime: Double = 0.0,
    @SerialName("l") val words: List<RichSyncWord> = emptyList(),
    @SerialName("x") val text: String? = null,
)

@Serializable
data class RichSyncWord(
    @SerialName("c") val text: String = "",
    @SerialName("o") val offset: Double = 0.0,
)
```

`Double`, **bukan `Int`**. Kalau `Int`, semua offset terpotong ke detik penuh dan setiap kata dalam satu baris dapat timestamp identik.

### 5.2 Semantik waktu

- `ts` = awal baris (detik), `te` = akhir baris (detik)
- `o` = offset **relatif terhadap `ts`**
- **Waktu absolut kata = `ts + o`**
- `l[]` mencampur kata dan spasi; spasi punya `c == " "`
- Chunk **tidak dijamin urut menaik** berdasarkan `o`

### 5.3 Algoritma konverter

```
untuk setiap line:
  1. skip kalau words kosong DAN x blank
  2. lineStart = ts; lineEnd = te
     skip kalau lineEnd <= lineStart
  3. pisahkan token jadi daftar berurut:
       Word(text=c, start=ts+o)  kalau c tidak blank
       Gap(text=c)               kalau c blank
  4. clamp setiap Word.start ke [lineStart, lineEnd)
  5. hitung end:
       urutkan index Word berdasarkan start ASCENDING (stabil)
       end[k] = start[k+1] dalam urutan itu; yang terakhir = lineEnd
       end[k] = max(end[k], start[k] + 0.001)   // parser butuh end > start
     → end dihitung dalam urutan kronologis,
       tapi OUTPUT tetap urutan asli dari provider
  6. kalau tidak ada Word sama sekali:
       emit <p begin end>escape(x)</p>   // line-synced saja
  7. kalau ada Word, emit dalam urutan asli:
       Word → <span begin=".." end="..">escape(text)</span>
       Gap  → escape(text) sebagai TEXT NODE, tanpa span
```

**Kenapa spasi jadi text node, bukan span:**

`TTMLParser.appendInterSpanText()` (`TTMLParser.kt:99-113`) menempelkan text node ke segmen **sebelumnya**. Jadi `"Don't"` + `" "` → `"Don't "`. Output tetap lossless dan tidak menghasilkan word kosong.

Kalau spasi dijadikan `<span>`, ia lolos filter `isNotEmpty` di `LyricsUtils.parseTtml` dan menjadi `PreparedLyricsWord(text=" ")` dengan timing nyata — ikut di-highlight saat karaoke. Itu bug.

**Konsekuensi formatting — PENTING:** di dalam `<p>`, tulis semua span dan text node dalam **satu baris tanpa indentasi**. `appendInterSpanText` membuang text node yang mengandung newline. Kalau XML di-pretty-print, spasi asli hilang.

### 5.4 Bentuk output

```xml
<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
  <body>
    <div>
      <p begin="7.748s" end="11.283s"><span begin="7.748s" end="7.863s">Don&apos;t</span> <span begin="7.979s" end="8.086s">know</span> ...</p>
    </div>
  </body>
</tt>
```

Isi `<p>` satu baris. Antar `<p>` boleh newline.

### 5.5 Helper

```kotlin
private fun formatTtmlTime(seconds: Double): String =
    String.format(Locale.US, "%.3fs", seconds)

private fun escapeXml(value: String): String  // & dulu, lalu < > " '
```

`Locale.US` **wajib** — kalau tidak, locale Indonesia menghasilkan koma desimal dan TTML rusak.

### 5.6 Fallback fetch richsync

Primary: ambil dari `macro_calls["track.richsync.get"]`.

Fallback: kalau key itu tidak ada **tapi** `has_richsync == true` dan bukan instrumental, panggil endpoint terpisah:

```
GET track.richsync.get
  ?format=json&subtitle_format=mxm&app_id=mac-ios-v2.0
  &richsync_compact_type=words
  &commontrack_id=<dari matcher>
  &f_subtitle_length=<track_length>
  &q_duration=<track_length>
  &usertoken=<token>
```

Gunakan `track_length` dari respons, bukan durasi input. Sertakan `richsync_compact_type=words` di sini juga.

### Acceptance Phase 4

- Fixture §6.2 → kata terakhir berdurasi **0.633s**
- Semua `span.end > span.begin`
- Tidak ada `<span>` yang isinya blank
- Hasil parse **tidak punya** `WordTimestamp` dengan `text.isBlank()`
- `entry.text` hasil parse == `x` asli (setelah normalisasi whitespace)
- Chunk out-of-order → end tetap benar, urutan output tetap urutan provider
- Output bisa diparse `TTMLParser.parseTTML()` tanpa exception

---

## 6. Phase 5 — Test

### 6.1 Infrastruktur

JUnit 4 (`libs.junit`, sudah ada di catalog) + `ktor-client-mock` (ditambah di Phase 1). Pakai `runBlocking` — **tidak perlu** `kotlinx-coroutines-test`, jadi tidak menambah dependensi baru.

Catatan: repo ini **belum punya satu pun file test**, meski 4 module sudah declares `libs.junit`. Test suite ini akan yang pertama. Jaga supaya hijau, karena CI menjalankan `gradle build test` di setiap push.

Fixture JSON sintetis di `src/test/resources/`. Jangan pakai lirik berhak cipta utuh — potongan pendek sintetis cukup.

### 6.2 Fixture wajib (regresi Spicetify #2248/#2254)

```json
{
  "ts": 7.748,
  "te": 11.283,
  "l": [
    { "c": "Don't", "o": 0 },
    { "c": " ", "o": 0.115 },
    { "c": "know", "o": 0.231 },
    { "c": " ", "o": 0.338 },
    { "c": "where", "o": 0.445 },
    { "c": " ", "o": 0.538 },
    { "c": "we", "o": 0.632 },
    { "c": " ", "o": 0.832 },
    { "c": "disconnected", "o": 1.033 },
    { "c": " ", "o": 1.772 },
    { "c": "each", "o": 2.074 },
    { "c": " ", "o": 2.185 },
    { "c": "of", "o": 2.297 },
    { "c": " ", "o": 2.453 },
    { "c": "our", "o": 2.609 },
    { "c": " ", "o": 2.755 },
    { "c": "stories", "o": 2.902 }
  ],
  "x": "Don't know where we disconnected each of our stories"
}
```

Kata terakhir: `11.283 - (7.748 + 2.902) = 0.633s`.

### 6.3 Daftar test

**Konverter (`MusixmatchTtmlTest`)**
1. Fixture → durasi kata terakhir 0.633s (toleransi 1ms)
2. Semua span `end > begin`
3. Tidak ada span berisi blank
4. Round-trip: parse dengan `TTMLParser`, tidak ada `WordTimestamp.text.isBlank()`
5. Round-trip: `entry.text` == `x`
6. `Locale` non-US (set `Locale.setDefault(Locale("id","ID"))`) → output tetap pakai titik desimal
7. XML escaping: `&`, `<`, `>`, `"`, `'`
8. Chunk out-of-order → end benar, urutan output terjaga
9. `words` kosong tapi `x` ada → `<p>` tanpa span
10. `te <= ts` → baris di-skip
11. Offset negatif / melebihi `te` → ter-clamp

**API (`MusixmatchApiTest`, MockEngine)**
12. `token.get` 200 → token tersimpan
13. Dua request paralel → satu panggilan `token.get`
14. HTTP 200 + body `status_code 401` → error terdeteksi
15. `401` dari `token.get` → RateLimited, tanpa retry
16. `429` → failure, `token.get` tidak dipanggil
17. `5xx` → failure, `token.get` tidak dipanggil
18. `401` dari macro → renew sekali + retry sekali, tidak loop
19. Macro malformed → failure, tanpa renew
20. Fixture macro → semua field penting tidak null
21. Double-decode `subtitle_body` dan `richsync_body`
22. Richsync inline dari macro dipakai (bukan endpoint terpisah)
23. Richsync inline absen + `has_richsync` true → panggil endpoint terpisah
24. Guard durasi: selisih 30s → failure; 3s → lolos; `-1` → guard dilewati
25. Guard teks: dua-duanya beda → failure; hanya artist beda → lolos
26. Instrumental → marker, richsync tidak dipanggil
27. `restricted` → failure
28. Fallback: richsync gagal → LRC; LRC gagal → plain; plain kosong → failure

**Regresi smoke test (REV 2 — wajib, ini yang menggugurkan desain Rev 1)**

29. Sub-call dengan `body: []` → `bodyAs<T>()` mengembalikan `null`, **bukan** exception
30. `track.subtitles.get` punya `body: []` sementara `track.richsync.get` valid → richsync tetap berhasil (isolasi per sub-call terbukti)
31. Flag integer `has_richsync: 1` / `instrumental: 0` → ter-decode tanpa `SerializationException`, computed property benar
32. `instrumental: 1` → marker, richsync tidak dipanggil
33. `restricted: 1` → failure
34. `body: null` dan `body` absen → `null`, tanpa exception

### Acceptance Phase 5

- `gradle :musixmatch:test` hijau
- `gradle build test` di root hijau
- Logger tidak pernah menerima token/URL/body/lirik — verifikasi dengan logger penampung di test

---

## 7. Phase 6 — Integrasi app (repo terpisah)

**Ini PR kedua, ke `rukamori/ArchiveTune`.** Jangan campur dengan PR submodule.

1. `settings.gradle.kts` utama → `include(":lyrics:musixmatch")`
2. `app/build.gradle.kts` → `implementation(project(":lyrics:musixmatch"))`
3. `app/src/main/kotlin/moe/rukamori/archivetune/lyrics/MusixmatchLyricsProvider.kt` — implement `LyricsProvider`
4. `constants/PreferenceKeys.kt` → `EnableMusixmatchLyricsKey`, entry enum `PreferredLyricsProvider` (~:603), `DefaultLyricsProviderOrder` (~:618)
5. `lyrics/LyricsHelper.kt` (~:400-411) → mapping enum → object
6. `lyrics/LyricsProviderSettings.kt` → `providerKeys`
7. `ui/screens/settings/LyricsSettings.kt` → `enableLabel()` (~:599), `displayName()` (~:619)
8. `res/values/strings.xml` → `enable_musixmatch_lyrics`

**Wajib dites manual:** `deserializeLyricsProviderOrder()` (~:634). Order tersimpan milik user lama mungkin tidak memuat provider baru → provider tidak pernah dipakai. Pastikan normalisasi menambahkan provider baru.

Sambungkan `Musixmatch.logger` ke logger app **hanya di build debug**, dan pastikan tidak membocorkan token.

---

## 8. Ditunda (jangan kerjakan sekarang)

| Fitur | Alasan |
|---|---|
| Terjemahan (`crowd.track.translations.get`) | PR terpisah supaya PR pertama tidak membengkak |
| Performer tagging → `ttm:agent` | Di ArchiveTune `agent` mengatur **alignment**, bukan nama (`v1`→kiri, `v2`→kanan, `v1000`→tengah). Nama tidak ditampilkan UI |
| Romanisasi `x-roman` | ArchiveTune sudah punya Kuromoji + ICU. Jangan duplikasi |
| Persistence token | Module JVM-only. Kalau perlu, pakai hook `tokenProvider`/`tokenSink` yang di-inject app layer |
| `getAllLyrics` multi-format | MVP delegasi seperti YouLyPlus |
| Overload Spotify URI | ArchiveTune tidak punya Spotify ID |

---

## 9. Checklist akhir sebelum PR

- [ ] `gradle build test` hijau (Gradle 8.10, JDK 21)
- [ ] Semua file baru punya license header
- [ ] Tidak ada import Android
- [ ] Tidak ada token ter-commit (`git log -p | grep` untuk pola hex panjang)
- [ ] Tidak ada log token/URL/body/lirik
- [ ] Tidak ada `<span>` blank di output TTML
- [ ] `Locale.US` di semua formatter
- [ ] Semua waktu internal `Double` detik
- [ ] `CancellationException` di-rethrow
- [ ] Retry token maksimal sekali
- [ ] Deskripsi PR menyebut: endpoint tidak resmi, risiko ToS, dan bahwa ini provider Musixmatch **kedua** (setelah Paxsenix) dengan pembeda richsync word-level + tanpa proxy
- [ ] Deskripsi PR menyebut ini test suite pertama di repo

---

## 10. Referensi kode (sudah diverifikasi)

| Hal | Lokasi |
|---|---|
| Gaya provider utama | `youlyplus/src/main/kotlin/moe/rukamori/archivetune/youlyplus/YouLyPlus.kt` (217 baris) |
| Template build file | `youlyplus/build.gradle.kts` |
| Parser TTML | `betterlyrics/src/main/kotlin/moe/koiverse/rukamori/betterlyrics/TTMLParser.kt` (803 baris) |
| **Package** parser | `moe.rukamori.archivetune.betterlyrics` — **beda dari path direktori**, jangan salah import |
| Spasi → segmen sebelumnya | `TTMLParser.kt:99-113` (`appendInterSpanText`) |
| Segmen tidak di-trim | `TTMLParser.kt:89-97`, `:600` |
| Filter word `isNotEmpty` | `LyricsUtils.parseTtml` (app repo) |
| Agent → alignment | `PrepareLyricsUseCase.kt:305-328` (app repo) |
| CI | `.github/workflows/test_build_push.yml` — JDK 21, Gradle 8.10, `gradle build test` |
| Aturan kontribusi | `docs/CONTRIBUTING.md` — stateless, no Android, package `moe.rukamori.archivetune.<name>`, file < 500 baris |

---

## 11. Protokol kolaborasi

- **Implementer:** harness lokal, push ke `adam-adrian/lyrics` branch `feat/musixmatch`
- **Advisor:** clone branch itu, review diff, verifikasi terhadap kode ArchiveTune
- **Sumber kebenaran:** dokumen ini. Kalau perlu menyimpang, laporkan dulu
- **Jangan** ubah dokumen ini dari sisi implementer; kirim usulan perubahan sebagai catatan terpisah

Lapor ke advisor setelah **setiap phase**, sertakan: file yang berubah, hasil `gradle test`, dan hal yang menyimpang dari spec.
