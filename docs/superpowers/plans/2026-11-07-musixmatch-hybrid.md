# Hybrid Musixmatch Implementation Plan

> **Execution:** Dikerjakan inline tanpa subagent. Semua kompilasi, test, dan smoke JVM dijalankan melalui GitHub Actions; perintah Gradle pada rancangan awal di bawah adalah referensi, bukan perintah lokal yang dijalankan. Bukti commit/run dicatat pada `docs/musixmatch-pr/implementer-notes.md` bagian 6.

**Goal:** Menutup dua titik buta Musixmatch dengan fallback dua arah, pencarian kandidat yang lebih dalam, peringkat kandidat yang stabil, dan fallback RichSync terpisah tanpa mengubah wiring app.

**Architecture:** Perubahan tetap berada di module `lyrics:musixmatch`. Jalur search dan playback akan memakai selector kandidat murni yang sama, tetapi search memproses hingga empat kandidat paralel sedangkan playback mencoba hingga dua kandidat secara berurutan. Resolver format, token state, dan helper search dipisah dari `Musixmatch.kt` agar perubahan berikutnya tidak menambah file yang sudah lebih dari 500 baris.

**Tech Stack:** Kotlin/JVM 21, kotlinx.coroutines, Ktor MockEngine, kotlinx.serialization, JUnit 4, Gradle.

**Spec:** `docs/musixmatch-pr/Musixmatch-Hybrid-Decisions.md`

## Global Constraints

- Semua perubahan kode berada di module `lyrics:musixmatch`; wrapper app, ViewModel, dan API provider tidak diubah.
- `DURATION_TOLERANCE` tetap `8`; jangan menambah toleransi CJK atau memakai `cleanText` yang membuang kana.
- `SEARCH_MAX_CANDIDATES` tetap `4`; `SEARCH_CONCURRENCY` tetap `2`; tambah `SEARCH_FALLBACK_MAX_ATTEMPTS = 2`.
- Format resolution tetap `richsync -> subtitle -> plain`.
- `RateLimited`, renewal token yang gagal, dan error terminal dari matcher utama tidak boleh berubah menjadi fallback diam-diam.
- `CancellationException` selalu di-rethrow.
- `track_rating` dibaca dengan default `0`; sorting harus stabil dan tidak memakai `trackId` sebagai tie-breaker.
- Tidak ada log token, request body, atau isi lirik.
- Setiap langkah harus punya test yang gagal sebelum implementasi dan test yang lulus sesudahnya.
- Tidak menambahkan dependency produksi; dependency `:betterlyrics` bila dipakai hanya untuk test round-trip.

---

### Task 0: Ekstrak helper tanpa perubahan perilaku

**Files:**
- Create: `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/SearchTokenState.kt`
- Create: `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/LyricsFormat.kt`
- Create: `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchSearch.kt`
- Modify: `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/Musixmatch.kt`
- Test: existing 68 tests under `musixmatch/src/test/kotlin/moe/rukamori/archivetune/musixmatch/`

**Interfaces:**
- `SearchTokenState` mempertahankan perilaku renewal satu kali dan terminal failure yang sekarang. Ia menerima `initialToken` dan block `suspend (String) -> T` seperti nested class saat ini.
- `LyricsFormat.kt` menyediakan helper internal yang menerima `Json` bila diperlukan dan mempertahankan output resolver sekarang:
  - `formatLrcTimestamp(seconds: Double): String`
  - `formatSubtitlesToLrc(subtitlesJson: String): String?`
  - `cleanPlainLyrics(rawBody: String): String`
  - `resolveMacroLyrics(calls: MacroCalls?, richSyncBodyStr: String? = null, jsonFormat: Json): String`
- `MusixmatchSearch.kt` menyediakan selector semantik sekarang terlebih dahulu:
  - `selectSearchCandidates(body: SearchBody, inputTitle: String, durationSeconds: Int, limit: Int): List<Track>`
  - Pada langkah ini implementasinya hanya `trackId > 0`, bukan instrumental, gate durasi ±8, `distinctBy(trackId)`, lalu `take(limit)`.
- Network request tetap memakai `HttpClient` dan token state milik `Musixmatch`; jangan membuat cache atau client kedua.

- [x] **Step 1: Catat baseline test dan status kerja**

Jalankan test module pada tree sebelum refactor:

```sh
./gradlew :musixmatch:test --no-daemon --max-workers=2
```

Expected: semua test yang ada lulus. Jangan mengubah expectation pada tahap ini.

- [x] **Step 2: Tulis selector pure dengan semantik lama**

Pindahkan logika berikut dari `getAllLyrics` ke `selectSearchCandidates` tanpa ranking baru:

```kotlin
fun selectSearchCandidates(
    body: SearchBody,
    inputTitle: String,
    durationSeconds: Int,
    limit: Int,
): List<Track> = body.trackList
    .mapNotNull { it.track }
    .filter { track ->
        track.trackId > 0 &&
            !track.isInstrumental &&
            (durationSeconds <= 0 || track.trackLength <= 0 ||
                abs(track.trackLength - durationSeconds) <= DURATION_TOLERANCE)
    }
    .distinctBy { it.trackId }
    .take(limit)
```

`inputTitle` boleh belum dipakai pada tahap ini bila compiler memerlukan parameter untuk kontrak langkah berikutnya. Jangan memasukkan `track_rating` atau marker versi sebelum test ranking Task 4.

- [x] **Step 3: Pindahkan token state, formatter, dan resolver secara mekanis**

Pindahkan implementasi yang ada, bukan menulis ulang perilakunya. `Musixmatch.kt` hanya mengorkestrasi request dan memanggil helper baru. Pertahankan `resolveMacroLyrics` memproses RichSync inline atau argumen RichSync terpisah sebelum subtitle dan plain.

- [x] **Step 4: Jalankan baseline setelah refactor**

```sh
./gradlew :musixmatch:test --no-daemon --max-workers=2
```

Expected: 68 test tetap lulus. Bila ada perubahan request count atau urutan hasil, hentikan task dan perbaiki ekstraksi sebelum masuk fitur.

- [x] **Step 5: Commit refactor**

```sh
git add musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch
git commit -m "refactor(musixmatch): extract search and lyric helpers"
```

---

### Task 1: Fallback dua arah dengan selector bersama

**Files:**
- Modify: `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/Musixmatch.kt`
- Modify: `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchSearch.kt`
- Modify: `musixmatch/src/test/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchSearchSelectionTest.kt`
- Modify: `musixmatch/src/test/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchApiTest.kt`

**Interfaces:**
- `getAllLyrics` memanggil selector dengan limit `SEARCH_MAX_CANDIDATES`. Bila hasil selector kosong, jalankan `executeMacro(cleanTitle, cleanArtist, cleanAlbum, durationSeconds, token)` melalui `SearchTokenState`, validasi matcher, dan resolve hasil tanpa mengeluarkan placeholder instrumental.
- `getLyrics` hanya fallback ketika jalur title macro melempar `MusixmatchError.NoMatch` atau `MusixmatchError.Mismatch`.
- Playback fallback menjalankan selector dengan limit `SEARCH_FALLBACK_MAX_ATTEMPTS`, lalu tiap kandidat secara berurutan memakai macro by ID, guard status 200 dan track ID sama, `validateMatch`, skip instrumental, separate RichSync bila perlu, dan `resolveMacroLyrics`.
- Playback fallback memakai `SearchTokenState`, bukan memanggil `getAllLyrics` atau menangkap callback.

- [x] **Step 1: Tambahkan test gagal untuk fallback search → matcher**

Tambahkan test berikut di `MusixmatchSearchSelectionTest.kt`, menggunakan helper MockEngine yang sudah ada dan helper baru yang mengembalikan `emitted` serta daftar request:

```kotlin
@Test
fun emptySearchFallsBackToTitleMatcher() = runBlocking {
    val emitted = runSearchWithSearchResponse(trackList = emptyList(), titleMacro = usableSubtitleMacro())
    assertEquals(1, emitted.size)
    assertTrue(emitted.single().contains("fallback lyric"))
}

@Test
fun entirelyFilteredPoolFallsBackToTitleMatcher() = runBlocking {
    val emitted = runSearchWithSearchResponse(trackList = listOf(instrumentalTrack()), titleMacro = usableSubtitleMacro())
    assertEquals(1, emitted.size)
}

@Test
fun fallbackMatcherInstrumentalEmitsNothing() = runBlocking {
    assertTrue(runSearchWithSearchResponse(trackList = emptyList(), titleMacro = instrumentalMacro()).isEmpty())
}

@Test
fun fallbackMatcherRestrictedEmitsNothing() = runBlocking {
    assertTrue(runSearchWithSearchResponse(trackList = emptyList(), titleMacro = restrictedMacro()).isEmpty())
}

@Test
fun nonEmptyCandidatePoolNeverCallsTitleMatcher() = runBlocking {
    val requests = runSearchAndCaptureRequests(trackList = listOf(eligibleTrack()))
    assertTrue(requests.none { request ->
        request.url.encodedPath.endsWith("macro.subtitles.get") &&
            request.url.parameters["track_id"] == null
    })
}
```

Implement these helpers with the existing MockEngine fixture. Route `track.search`, macro-by-title without `track_id`, and macro-by-ID separately. Assert the title fallback request contains `q_track`, `q_artist`, `q_album`, `q_duration`, and `f_subtitle_length`.

- [x] **Step 2: Jalankan test fallback search dan pastikan gagal**

```sh
./gradlew :musixmatch:test --tests '*MusixmatchSearchSelectionTest' --no-daemon --max-workers=2
```

Expected: test baru gagal karena selector kosong sekarang tidak melakukan matcher fallback.

- [x] **Step 3: Tambahkan test gagal untuk fallback playback → search**

Di `MusixmatchApiTest.kt`, gunakan routing endpoint eksplisit:

```kotlin
@Test
fun playbackNoMatchFallsBackToSearchThenMacroById() = runBlocking {
    val result = runPlaybackWithTitleMacro(
        titleMacro = noMatchMacro(),
        searchTracks = listOf(eligibleTrack()),
        idMacros = mapOf(1L to usableSubtitleMacro()),
    )
    assertEquals("fallback lyric", result.getOrThrow())
}

@Test
fun playbackMismatchFallsBackToSearch() = runBlocking {
    val result = runPlaybackWithTitleMacro(
        titleMacro = mismatchMacro(),
        searchTracks = listOf(eligibleTrack()),
        idMacros = mapOf(1L to usableSubtitleMacro()),
    )
    assertTrue(result.isSuccess)
}

@Test
fun playbackRestrictedDoesNotFallBackToSearch() = runBlocking {
    val requests = runPlaybackAndCaptureRequests(restrictedMacro(), searchTracks = emptyList())
    assertTrue(requests.none { request -> request.url.encodedPath.endsWith("track.search") })
}
```

Add assertions with the same fixture for a failed first candidate followed by a successful second candidate, a restricted fallback candidate being skipped, 429 and failed renewal escaping without another candidate attempt, and a successful title macro producing no `track.search` request.

- [x] **Step 4: Implementasikan fallback search → matcher**

Setelah `selectSearchCandidates(...)` menghasilkan list kosong, panggil macro by-title melalui `SearchTokenState`. Validasi matcher dengan aturan existing. Untuk `isInstrumental` dan `Restricted`, tidak panggil callback. Untuk `NoMatch`, `Mismatch`, atau response non-usable, tidak ada callback. Jangan fallback lagi bila macro fallback menerima `RateLimited` atau terminal token failure.

- [x] **Step 5: Implementasikan fallback playback → search**

Tangkap hanya `NoMatch` dan `Mismatch` dari operasi title macro. Semua error lain keluar seperti sebelumnya. Buat fungsi internal yang mencoba maksimal dua kandidat secara berurutan:

```kotlin
for (candidate in selectSearchCandidates(searchBody, cleanTitle, durationSeconds, SEARCH_FALLBACK_MAX_ATTEMPTS)) {
    try {
        val macroBody = tokenState.execute { token -> executeMacroByTrackId(candidate.trackId, token) }
        // status 200, same track ID, validateMatch, skip instrumental
        // resolve inline RichSync, separate RichSync if advertised, then subtitle/plain
    } catch (e: Restricted) {
        lastFailure = e
    } catch (e: NoMatch) {
        lastFailure = e
    } catch (e: Mismatch) {
        lastFailure = e
    } catch (e: MalformedResponse) {
        lastFailure = e
    } catch (e: ServerError) {
        lastFailure = e
    }
}
throw lastFailure ?: MusixmatchError.NoMatch("Search fallback returned no usable lyrics")
```

`CancellationException`, `RateLimited`, dan terminal `TokenInvalid` tidak boleh masuk ke daftar kandidat berikutnya.

- [x] **Step 6: Jalankan test Task 1**

```sh
./gradlew :musixmatch:test --tests '*MusixmatchSearchSelectionTest' --tests '*MusixmatchApiTest' --no-daemon --max-workers=2
```

Expected: test fallback baru dan test lama lulus.

- [x] **Step 7: Commit fallback dua arah**

```sh
git add musixmatch/src/main musixmatch/src/test
 git commit -m "feat(musixmatch): add symmetric search fallbacks"
```

---

### Task 2: Separate RichSync pada jalur search

**Files:**
- Modify: `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/Musixmatch.kt`
- Modify: `musixmatch/src/test/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchSearchSelectionTest.kt`
- Modify: `musixmatch/src/test/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchSearchConcurrencyTest.kt` only if a candidate-isolation fixture belongs there

**Interfaces:**
- Search candidate resolution mengikuti playback: bila inline RichSync kosong dan `matcherTrack.isRichsyncAvailable`, panggil `fetchRichSyncSeparately(commonTrackId, trackLength, token)` lalu berikan hasilnya ke resolver.
- Failure endpoint separate RichSync tetap candidate-local. Kandidat lain masih boleh mengirim callback.
- `has_richsync=0` tidak boleh memanggil endpoint separate.

- [x] **Step 1: Ubah fixture default**

Di helper `track(...)` pada `MusixmatchSearchSelectionTest.kt`, ubah default JSON `has_richsync` menjadi `0`. Sediakan parameter eksplisit `hasRichsync: Boolean = false` untuk test yang memerlukan endpoint separate.

- [x] **Step 2: Tambahkan test gagal positive separate RichSync**

Tambahkan fixture yang mengembalikan:

```text
macro by ID: matcher 200 with has_richsync=1
macro inline richsync: 404
subtitle: 404
lyrics: 404
track.richsync.get by commontrack_id: 200 with valid RichSync JSON
```

Assert callback menerima TTML dan request `track.richsync.get` memuat `commontrack_id` yang benar. Tambahkan test `has_richsync=0` tanpa request separate, failure separate tidak membatalkan kandidat lain, dan playback/search menghasilkan format yang sama pada fixture identik.

- [x] **Step 3: Jalankan test dan pastikan positive case gagal**

```sh
./gradlew :musixmatch:test --tests '*MusixmatchSearchSelectionTest' --no-daemon --max-workers=2
```

Expected: positive separate-RichSync test gagal sebelum production change.

- [x] **Step 4: Implementasikan fallback RichSync search**

Di resolver kandidat search, ambil `matcherTrack` terlebih dahulu. Resolve inline RichSync bila ada. Bila tidak ada dan flag tersedia, panggil helper existing. Teruskan string hasil ke `resolveMacroLyrics(calls, additionalRichSync, jsonFormat)`. Jangan mengubah prioritas format.

- [x] **Step 5: Jalankan seluruh module test**

```sh
./gradlew :musixmatch:test --no-daemon --max-workers=2
```

Expected: seluruh test lulus, termasuk candidate isolation dan cancellation.

- [x] **Step 6: Commit separate RichSync**

```sh
git add musixmatch/src/main musixmatch/src/test
 git commit -m "fix(musixmatch): recover separate richsync during search"
```

---

### Task 3: Perbesar pool search tanpa mengubah gate

**Files:**
- Modify: `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/Musixmatch.kt` or extracted search constants file
- Modify: `musixmatch/src/test/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchSearchSelectionTest.kt`

**Interfaces:**
- `SEARCH_PAGE_SIZE` berubah dari `5` menjadi `20`.
- `SEARCH_MAX_CANDIDATES`, concurrency, duration gate, dedup, dan ranking saat ini tidak berubah pada task ini.

- [x] **Step 1: Ubah test assertion menjadi page size 20**

Perbarui assertion request `track.search` dari `"5"` menjadi `"20"`. Tambahkan fixture dengan kandidat valid di indeks lebih besar dari 5 dan assert selector masih memasukkannya sebelum cap.

- [x] **Step 2: Jalankan test baru dan seluruh module test**

```sh
./gradlew :musixmatch:test --no-daemon --max-workers=2
```

Expected: test baru membuktikan kandidat di luar lima besar tersedia; semua test lulus.

- [x] **Step 3: Commit page size**

```sh
git add musixmatch/src/main musixmatch/src/test
 git commit -m "fix(musixmatch): expand track search candidate pool"
```

---

### Task 4: Tambahkan ranking kandidat stabil

**Files:**
- Modify: `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/models/MacroModels.kt`
- Modify: `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchSearch.kt`
- Modify: `musixmatch/src/test/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchSearchSelectionTest.kt`

**Interfaces:**
- Tambahkan field model:

```kotlin
@SerialName("track_rating") val trackRating: Int = 0,
```

- Selector menjadi `filter -> distinctBy -> sortedWith(demerit ASC, trackRating DESC) -> take(limit)`.
- `demerit = 1` hanya bila marker muncul di qualifier segment track title (`(...)`, `[...]`, teks setelah ` - ` atau ` – `) atau `album_name` untuk marker `cover`, dan input title tidak mengandung marker tersebut.
- Marker: `remix`, `live`, `cover`, `instrumental`, `acoustic`, `arrangement`, `tribute`, `karaoke`.
- Sorting stabil. Jangan menambahkan `thenBy(trackId)`.

- [x] **Step 1: Tambahkan test pure untuk ranking**

Gunakan kandidat berikut:

```text
Dynamite, rating 90, album canonical
Dynamite (Bedroom Remix), rating 42
Dynamite (Live), rating 51
Dynamite - Cover, rating 29
```

Assert canonical muncul lebih dulu. Tambahkan kasus candidate dengan marker tetapi input title juga mengandung marker, sehingga tidak mendapat demerit. Tambahkan `Live Forever` tanpa qualifier marker, sehingga tidak mendapat demerit. Tambahkan tie rating dan demerit, lalu assert urutan response dipertahankan.

- [x] **Step 2: Jalankan ranking test dan pastikan gagal**

```sh
./gradlew :musixmatch:test --tests '*MusixmatchSearchSelectionTest' --no-daemon --max-workers=2
```

Expected: test gagal karena field rating dan ranking belum ada.

- [x] **Step 3: Implementasikan field dan stable ranking**

Parse `track_rating` dengan default zero agar fixture lama tetap valid. Implementasikan ekstraksi qualifier secara terstruktur, bukan regex yang menghapus seluruh judul. Hanya qualifier yang dinilai marker; judul utama seperti `Live Forever` tidak dihukum. Bandingkan marker input title sebelum memberi demerit.

- [x] **Step 4: Jalankan seluruh test module**

```sh
./gradlew :musixmatch:test --no-daemon --max-workers=2
```

Expected: ranking test dan 68 test baseline yang telah diperbarui lulus. Test lama dengan rating default zero mempertahankan urutan response karena Kotlin `sortedWith` stabil.

- [x] **Step 5: Commit ranking**

```sh
git add musixmatch/src/main musixmatch/src/test
 git commit -m "feat(musixmatch): rank search candidates by version and rating"
```

---

### Task 5: Optional diagnostic safety net, terpisah dari hybrid

**Files:**
- Modify: `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/Musixmatch.kt` or extracted search orchestration file
- Optional Create/Modify: `musixmatch/src/test/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchTtmlRoundTripTest.kt`
- Optional Modify: `musixmatch/build.gradle.kts`

**Interfaces:**
- Log search funnel tanpa title, token, body, atau lirik:

```text
Musixmatch search: returned=<raw> eligible=<eligible> candidates=<selected>
```

- Test-only `testImplementation(project(":betterlyrics"))` boleh ditambahkan bila round-trip TTML dikerjakan.

- [x] **Step 1: Tambahkan test round-trip TTML bila dependency tersedia**

Fixture RichSync harus mencakup spasi antar-kata, apostrophe, ampersand, dan literal `<3`. Jalankan converter lalu `TTMLParser.parseTTML`. Assert hasil tidak kosong, jumlah baris sama, text tidak blank, words tidak kosong, dan `endTime > startTime`.

- [x] **Step 2: Tambahkan log funnel di boundary selector**

Hitung jumlah raw search entries, jumlah setelah eligibility filter, dan jumlah setelah dedup/ranking/cap. Log hanya angka. Jangan menambahkan log empty-preview di app karena perubahan app berada di luar paket ini.

- [x] **Step 3: Jalankan verification akhir**

```sh
./gradlew :musixmatch:test --no-daemon --max-workers=2
```

Expected: seluruh suite module lulus. Bila `:betterlyrics` dependency menimbulkan masalah Gradle, hapus hanya test round-trip dan tetap pertahankan test converter yang sudah ada; jangan menambahkan dependency produksi.

- [x] **Step 4: Commit diagnostic safety net**

```sh
git add musixmatch
 git commit -m "test(musixmatch): add parser round trip and search funnel diagnostics"
```

---

## Final verification

- [x] Verifikasi commit kode `143184b` melalui CI `42314679553` (`build test`), tanpa Gradle lokal. Smoke JVM terpisah pada `6eca335`, CI `42317352849`, lulus untuk fallback dua arah, renewal bersama, ranking, separate RichSync, dan parser. Runner sementara dihapus pada `f86fbc7`; CI cleanup dicatat pada implementer notes.
- [x] Pastikan tidak ada import Android atau perubahan `libs.versions.toml`.
- [x] Pastikan seluruh file Kotlin baru memiliki header GPL ArchiveTune.
- [x] Pastikan `getAllLyrics` tetap maksimal empat kandidat dan concurrency dua.
- [x] Pastikan playback fallback maksimal dua kandidat berurutan dan tetap dalam token renewal budget yang sama.
- [x] Pastikan jalur macro yang sukses tidak menambah request search.
- [x] Pastikan `RateLimited`, terminal token failure, dan `CancellationException` tidak tertelan oleh fallback.
- [x] Hasil CI dan commit dicatat pada implementer notes: cleanup `f86fbc7`, CI `42317619028` lulus. Gitlink app dan APK tidak diubah.
