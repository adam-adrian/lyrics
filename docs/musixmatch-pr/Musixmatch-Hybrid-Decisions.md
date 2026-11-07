# Keputusan terkunci — Hybrid Musixmatch (untuk implementer)

**Basis:** `8765c5d` · **Tanggal:** 7 Nov 2026
**Menjawab:** research implementer atas `Review-Hybrid-Search-Strategy.md` §14
**Status:** keputusan advisor, siap dikerjakan. Semua perubahan di module `lyrics:musixmatch`.

---

## 0. Dua koreksi atas research implementer

**(a) Jumlah test 68, bukan 67.** Dihitung di `8765c5d`:

| file | @Test |
|---|---:|
| `MusixmatchApiTest.kt` | 25 |
| `MusixmatchSearchSelectionTest.kt` | 16 |
| `MusixmatchSearchConcurrencyTest.kt` | 13 |
| `MusixmatchTtmlTest.kt` | 13 |
| `MusixmatchSearchTest.kt` | 1 |
| **total** | **68** |

**(b) Kekhawatiran fixture terkonfirmasi, dan lokasinya persis.** `MusixmatchSearchSelectionTest.kt:351` menulis `"has_richsync":1,"has_subtitles":1,"has_lyrics":1` secara hardcoded. `MusixmatchSearchConcurrencyTest.kt:651` sudah memakai `has_richsync:0`, jadi suite itu aman. Assertion `page_size` ada di `MusixmatchSearchSelectionTest.kt:42`.

Sisanya dari research implementer **cocok dengan kode**, termasuk: `Track` belum punya `track_rating`; `getLyrics` sudah mengirim `q_album`/`q_duration`/`f_subtitle_length`; resolver format bersama sudah benar; `validateMatch` menolak hanya bila title **dan** artist sama-sama gagal; wrapper app/ViewModel tidak perlu diubah.

---

## 1. KEPUTUSAN: fallback search→matcher berlaku untuk **kedua** sebab kosong

Implementer mengusulkan membedakan "search kosong murni" (boleh fallback) dari "pool nonempty tapi seluruhnya terfilter" (tetap tanpa request). **Usulan itu saya tolak.** Satu kondisi saja:

```kotlin
if (candidates.isEmpty()) { /* fallback ke matcher by-title */ }
```

**Alasan:** pool yang seluruhnya terfilter adalah kasus nyata, bukan tepi. Blinding Lights pada `page_size=5` menghasilkan live 253 s, instrumental, potongan 61 s, dan remix 216 s. Bila semua terbuang, matcher — yang menerima `q_duration` dan `f_subtitle_length` — masih bisa memilih rekaman kanonik yang tidak masuk jendela 5 teratas. Menolak fallback di situ justru membuang recall yang sudah terbukti ada.

**Biaya terkendala:** tepat satu request tambahan, hanya di jalur yang sekarang mengembalikan nol. `validateMatch` tetap menjaga presisi, jadi tidak ada risiko hasil salah durasi.

**Test yang harus diubah:** `emptySearchOrEntirelyFilteredPoolMakesNoMacroRequests()` (`MusixmatchSearchSelectionTest.kt:288`) sekarang mengunci perilaku yang salah. Pecah menjadi:

- `emptySearchFallsBackToTitleMatcher`
- `entirelyFilteredPoolFallsBackToTitleMatcher`
- `fallbackMatcherInstrumentalEmitsNothing`
- `fallbackMatcherRestrictedEmitsNothing`
- `nonEmptyCandidatePoolNeverCallsTitleMatcher`

**Aturan hasil fallback di search:** matcher instrumental → **tidak ada callback** (search tidak boleh menawarkan placeholder "♪ Instrumental ♪" sebagai kandidat; berbeda dari playback yang memang perlu menampilkan sesuatu). Restricted → tidak ada callback. `validateMatch` gagal → tidak ada callback.

---

## 2. KEPUTUSAN: pemilihan satu hasil untuk fallback playback

Ini yang implementer minta dikunci. **Jangan** memanggil `getAllLyrics` lalu menangkap callback. Pakai **satu selector murni yang dipakai bersama**.

### 2.1 Seam bersama

```kotlin
private fun selectSearchCandidates(
    body: SearchBody,
    inputTitle: String,
    durationSeconds: Int,
    limit: Int,
): List<Track>
```

Isi: filter (`trackId > 0`, `!isInstrumental`, gate durasi ±8 bila diketahui) → `distinctBy { trackId }` → peringkat (§4) → `take(limit)`.

- `getAllLyrics` memanggil dengan `limit = SEARCH_MAX_CANDIDATES` (4), lalu fan-out paralel seperti sekarang.
- `getLyrics` fallback memanggil dengan `limit = SEARCH_FALLBACK_MAX_ATTEMPTS` (**2**), lalu mencoba **berurutan** sesuai peringkat, berhenti pada sukses pertama.

**Kenapa 2, bukan 1 atau 4:** kandidat peringkat pertama bisa tidak punya lirik dalam format apa pun; percobaan kedua murah. Dua percobaan berurutan menjaga anggaran `PROVIDER_TIMEOUT_MS = 8000` yang sudah dipakai playback. Jangan naikkan concurrency di jalur ini.

### 2.2 Prosedur per kandidat fallback playback

1. `executeMacroByTrackId(trackId, token)`
2. matcher harus 200 **dan** `matcherTrack.trackId == trackId` (guard sama dengan search)
3. `validateMatch(matcherTrack, cleanTitle, cleanArtist, durationSeconds)` — presisi playback tetap, termasuk gate teks
4. matcher instrumental → **lewati**, coba berikutnya (jalur utama sudah menangani instrumental untuk track yang cocok)
5. richsync inline; bila tidak ada dan `isRichsyncAvailable` → `fetchRichSyncSeparately(...)`. **Sama dengan jalur utama**, supaya fallback tidak menghasilkan format lebih rendah
6. `resolveMacroLyrics(calls, additionalRichSync)` → sukses → `Result.success`

### 2.3 Aturan error

| error | tindakan |
|---|---|
| `CancellationException` | rethrow, selalu |
| `TokenInvalid` | renew **sekali per operasi** (pakai `SearchTokenState`), ulang kandidat yang sama; renewal gagal → terminal |
| `RateLimited` | **terminal**, hentikan seluruh operasi |
| `Restricted` | lewati ke kandidat berikutnya; bila semua restricted → kembalikan `Restricted` |
| `NoMatch`, `Mismatch`, `MalformedResponse`, `ServerError` | coba kandidat berikutnya |
| semua habis | kembalikan error terakhir; bila tidak ada, `NoMatch` |

**Konsistensi dengan jalur utama:** `Restricted` dari **matcher utama** tetap terminal — tidak boleh fallback. Fallback hanya berjalan setelah `NoMatch`/`Mismatch`, yaitu ketika tidak ada track yang cocok, sehingga tidak ada pembatasan yang dilewati. Di dalam fallback, restricted bersifat per-rekaman, sama seperti perlakuan `getAllLyrics` saat ini.

---

## 3. KEPUTUSAN: pemicu fallback playback

`getLyrics` fallback ke search **hanya** bila jalur utama menghasilkan `NoMatch` atau `Mismatch`.

**Tidak** fallback pada: `Restricted`, `RateLimited`, `TokenInvalid` yang renewal-nya gagal, `ServerError`, `MalformedResponse`. Jalur sukses tetap **1 RTT**.

---

## 4. KEPUTUSAN: peringkat kandidat

### 4.1 Model

Tambah ke `Track` (`MacroModels.kt:52`):

```kotlin
@SerialName("track_rating") val trackRating: Int = 0,
```

Default 0 membuat semua fixture lama tetap kompilasi.

### 4.2 Kunci urutan

```
demerit ASC → trackRating DESC → (stable sort mempertahankan urutan respons)
```

**Jangan tambahkan `thenBy { trackId }`.** `sortedWith` Kotlin stabil, jadi kunci yang sama mempertahankan urutan respons. Itu sudah deterministik untuk respons yang sama **dan** tidak mengubah ekspektasi urutan pada test lama yang rating-nya semua 0. Menambah tiebreak id akan mengacak fixture yang id-nya tidak menaik tanpa manfaat.

### 4.3 `demerit` — penanda versi, hanya dari segmen kualifikasi

`demerit = 1` bila kandidat punya penanda versi **dan judul input tidak punya**; selain itu `0`.

Penanda dicari **hanya** di:
- segmen dalam `(...)` dan `[...]` pada `track_name`
- teks setelah ` - ` atau ` – ` pada `track_name`
- `album_name`, khusus penanda cover

Daftar penanda: `remix`, `live`, `cover`, `instrumental`, `acoustic`, `arrangement`, `tribute`, `karaoke`.

**Kenapa hanya segmen kualifikasi:** menghindari salah positif pada judul sah seperti `Live Forever` atau `Alive`. `Dynamite (Bedroom Remix)` → kualifikasi `Bedroom Remix` → demerit. `Live Forever` → tanpa kualifikasi → tidak ada demerit.

**Kenapa dibandingkan dengan judul input:** bila user memang memutar `Dynamite (Slow Jam Remix)`, input juga punya penanda itu, sehingga kandidat remix tidak dihukum.

**Bukti pendukung** (§12.3): Dynamite kanonik rating 90; remix 42–53; cover 29; instrumental 38. `Blinding Lights` dari album `Official Covers, Vol.1` tertangkap lewat penanda cover di album.

**Gate durasi ±8 tidak berubah.** Peringkat dilakukan di klien **setelah** gate. `s_track_rating=desc` server-side sudah diuji dan **lebih buruk** — jangan dipakai.

---

## 5. KEPUTUSAN: separate RichSync di search, dan fixture

Tambahkan fallback yang sama dengan playback ke `getAllLyrics`: bila richsync tidak inline dan `candidate.isRichsyncAvailable` → `fetchRichSyncSeparately(commonTrackId, trackLength, token)`, hasilnya diteruskan sebagai argumen kedua `resolveMacroLyrics`.

**Fixture:** ubah default `track(...)` di `MusixmatchSearchSelectionTest.kt:351` menjadi `has_richsync:0`, lalu tambah parameter eksplisit untuk test positif. Ini menjaga ekspektasi jumlah request pada test lama dan membuat perilaku baru opt-in per test.

**Uji positif yang wajib ada** (sekaligus menutup celah uji MVP #1):

1. inline `track.richsync.get` **404**, `has_richsync=1`, subtitle **404**, lyrics **404** → assert `track.richsync.get?commontrack_id=` dipanggil dan callback menerima **TTML**
2. `has_richsync=0` → endpoint terpisah **tidak** dipanggil
3. endpoint terpisah gagal → hanya kandidat itu dilewati, kandidat lain tetap jalan
4. playback dan search menghasilkan format yang sama untuk fixture identik

---

## 6. Urutan pengerjaan

Setiap langkah punya sinyal regresi sendiri di CI. Jangan digabung.

| # | langkah | sifat | sinyal |
|---|---|---|---|
| **0** | ekstrak `selectSearchCandidates(...)` dengan semantik **sekarang** (filter → distinctBy → take), tanpa peringkat | refactor murni | **68 test harus tetap hijau**, tanpa perubahan ekspektasi |
| **1** | fallback dua arah (§1–§3) memakai seam itu | fitur | test baru §1 + §2; tulis test gagal dulu |
| **2** | separate RichSync di search + default fixture (§5) | fitur | test positif 1–4 |
| **3** | `SEARCH_PAGE_SIZE = 20` | konstanta | update assertion `:42`; tambah test kandidat di indeks >5 tetap lolos |
| **4** | peringkat + `track_rating` (§4) | perubahan perilaku | test peringkat sendiri; test lama tetap hijau karena stable sort |
| **5** | uji round-trip TTML + log corong | diagnosis terpisah | bukan prasyarat hybrid |

**Langkah 0 penting:** ia menciptakan seam tanpa mengubah perilaku, sehingga langkah 1 bisa ditulis regression-first dengan bersih. Ini pola yang sudah berhasil di `a701ab3` → `741bc0a`.

**Konstanta baru:** `SEARCH_FALLBACK_MAX_ATTEMPTS = 2`. `SEARCH_MAX_CANDIDATES` tetap 4, `SEARCH_CONCURRENCY` tetap 2, `DURATION_TOLERANCE` tetap 8.

**Batas file:** `Musixmatch.kt` sudah 638 baris (>500, melanggar `CONTRIBUTING.md`). Fallback dan peringkat akan menambah lagi. **Pecah file bersamaan dengan langkah 0**, bukan sesudah: `SearchTokenState.kt`, `LyricsFormat.kt` (resolver + formatter), `MusixmatchSearch.kt` (selector + peringkat + fallback). Sisanya tinggal objek utama.

---

## 7. Yang tidak dikerjakan di paket ini

- Bug `isEscapedTtml` di `LyricsUtils` — bug app terpisah, tidak berhubungan dengan Koiiro. Setuju dengan implementer: jangan dicampur.
- `warmUp()` — tetap eksperimen terpisah.
- Perluasan query / transliterasi artist — fallback matcher sudah menyelesaikan kasus yang terbukti; jangan tambah scope.
- Pemulihan multi-kandidat dengan artist hasil matcher (§14.2 C) — tunda; Koiiro hanya punya 1 track.
- Melonggarkan gate durasi, `cleanText` ala Vivi, `s_track_rating=desc`, menghapus `f_has_lyrics=1` — semuanya sudah diuji dan ditolak.
