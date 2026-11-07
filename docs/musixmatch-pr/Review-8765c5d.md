# Review Advisor — `8765c5d` (multi-kandidat Search Lyrics)

**Rangkaian:** `a701ab3` → `741bc0a` → `fd866c0` → `8765c5d`
**Ukuran:** +1.643/−96, 8 file
**Test:** 36 → **68**
**CI:** `a701ab3` failure → `741bc0a` success → `fd866c0` success
**Tanggal:** 6 Nov 2026

---

## Verdict

**Implementasi multi-kandidat selesai dengan kualitas tinggi.** Yang paling menonjol bukan jumlah test, tapi **disiplin regression-first**: empat regresi ditemukan review independen, masing-masing dibuktikan dengan CI run yang gagal **sebelum** diperbaiki. Itu bukti test benar-benar berdaya diskriminasi — bukan test yang ditulis setelah kode dan lolos apa pun yang terjadi.

Verifikasi produksi juga nyata: run `42237306045` menjalankan **implementasi Kotlin sebenarnya** ke API live dan menerima empat callback (TTML 1.730 ms, TTML 1.805 ms, **LRC 1.841 ms**, TTML 1.991 ms). Callback LRC itu sekaligus menutup keraguan yang muncul dari bug script-ku: jalur subtitle memang berfungsi di produksi.

Tersisa **1 pelanggaran panduan**, **1 deviasi yang perlu dinyatakan**, dan **3 celah uji lama yang belum tertutup**. Tidak ada bug logika yang kutemukan.

---

## 1. Pelanggaran panduan — `Musixmatch.kt` 638 baris

`docs/CONTRIBUTING.md`: *"Keep files under 500 lines where possible."*

Aku menandai risiko ini saat file masih 459 baris. Sekarang **638**, dan pemecahannya jelas mungkin:

| Kandidat pecahan | Isi | Perkiraan |
|---|---|---|
| `SearchTokenState.kt` | kelas `SearchTokenState` + `searchRequest` | ~70 baris |
| `LyricsFormat.kt` | `resolveMacroLyrics`, `formatSubtitlesToLrc`, `formatLrcTimestamp`, `cleanPlainLyrics`, `LICENSE_FOOTER_PATTERNS` | ~120 baris |
| `MusixmatchApi.kt` | `executeTrackSearch`, `executeMacroByTrackId`, `executeMacro`, `fetchRichSyncSeparately`, `fetchToken` | ~180 baris |

Setelah itu `Musixmatch.kt` tinggal ~270 baris: konfigurasi klien, token cache, `getLyrics`, `getAllLyrics`.

Ini bukan sekadar estetika. `resolveMacroLyrics` sekarang dipakai **dua** jalur; menempatkannya di file sendiri membuat kontrak bersama itu terlihat, bukan tersembunyi di dalam objek besar. Sebaiknya dilakukan sebelum PR upstream, karena maintainer kemungkinan menandainya.

---

## 2. Deviasi yang perlu dinyatakan: cap 4, bukan 3

Kita menyepakati **3 kandidat**. Kode memakai `SEARCH_MAX_CANDIDATES = 4` dengan pool 5.

Aku **menerima** deviasi ini karena ada bukti: run produksi menghasilkan empat callback dengan yang terakhir pada 1.991 ms — jauh di dalam anggaran 8 detik. Dan emisi inkremental melindungi kalau perangkat lebih lambat.

Tapi dua catatan:

- Runner GitHub bukan perangkat user. Margin di data seluler belum terukur.
- Karena ini menyimpang dari kesepakatan, **sebutkan di deskripsi PR** sebagai pilihan beserta buktinya, jangan biarkan reviewer menemukannya sendiri di konstanta.

Kalau nanti uji perangkat menunjukkan hasil keempat sering terpotong timeout, turunkan ke 3 — bukan menaikkan concurrency.

---

## 3. Tiga celah uji dari audit MVP masih terbuka

Aku periksa specifically karena ini item lama:

### 3.1 `fetchRichSyncSeparately` — masih belum diuji secara positif

Satu-satunya assertion yang menyebut endpoint itu adalah:

```kotlin
assertFalse(result.requests.any { it.encodedPath.endsWith("track.richsync.get") })
```

Itu memverifikasi **search tidak memanggilnya** — bagus, tapi bukan menguji fungsinya. Jalur playback yang benar-benar memanggilnya (richsync inline absen + `has_richsync == 1`) **masih nol test**.

### 3.2 `q_album` / `f_subtitle_length` — baru diassert absen, belum asserted benar

Referensi satu-satunya ada di loop yang memastikan parameter playback **tidak muncul di search**. Konstruksi parameter di sisi playback sendiri masih tanpa assertion.

Catatan kecil: loop itu juga menyebut `f_subtitle_length_max_deviation`, padahal kode **tidak pernah mengirimnya**. Assertion itu trivially true — tidak salah, tapi tidak menambah perlindungan.

### 3.3 Footer lisensi — masih nol test

`LICENSE_FOOTER_PATTERNS` masih dua string dugaan, belum dicocokkan dengan respons nyata. Kalau bunyi footer aslinya berbeda, teks itu tampil ke user.

**Ketiganya berisiko rendah dan gagal dengan aman.** Bukan blocker untuk fitur search. Tapi jangan diklaim tertutup di deskripsi PR.

---

## 4. Nit

**(a) `resolveMacroLyrics` membaca `restricted` tanpa guard status.**

```kotlin
val lyricsData = calls?.trackLyricsGet.bodyAs<LyricsBody>(jsonFormat)?.lyrics
if (lyricsData?.isRestricted == true) throw MusixmatchError.Restricted()
```

Bacaan sub-call lain di fungsi yang sama memakai `takeIf { it.message.header.statusCode == 200 }`. Yang ini tidak. Konsistenkan — kalau sub-call gagal, body-nya bukan sumber kebenaran.

**(b) Pemeriksaan ganda di jalur playback.** `getLyrics` memeriksa `restricted` dan `instrumental`, lalu `resolveMacroLyrics` memeriksa keduanya lagi. Idempoten dan tidak berbahaya, tapi membuat pembaca bertanya mana yang otoritatif. Pilih satu tempat.

---

## 5. Yang dilakukan dengan benar — jangan diubah saat revisi

| Hal | Kenapa penting |
|---|---|
| `resolveMacroLyrics` dipakai bersama playback **dan** search | Satu sumber kebenaran pemilihan format; kedua jalur tidak bisa melenceng satu sama lain |
| `takeIf { statusCode == 200 }` pada bacaan sub-call | Menutup celah laten: body dari sub-call gagal tidak lagi dibaca sebagai data |
| `matchingIdAcceptsDifferentMatcherTitleAndArtist` | Mengunci keputusan "tanpa guard teks di search" sebagai test, bukan cuma komentar |
| `durationDifferenceOfEightPassesInBothDirectionsButNineFails` | Uji batas dua arah, bukan satu titik |
| Callback dipanggil **di luar** `semaphore.withPermit` | Callback lambat tidak menahan fetch kandidat lain |
| `callbackMutex` + `searchScope.cancel(e)` | Callback diserialkan, dan `CancellationException` dari callback membatalkan seluruh pencarian — bukan satu child |
| `SearchTokenState`: renewal sekali per operasi, `terminalFailure` menjalar | 429 dan kegagalan renewal menghentikan operasi; kegagalan kandidat biasa tidak |
| `renewalUsed` di dalam mutex, `token`/`terminalFailure` `@Volatile` | Pembagian yang tepat: atomicity lewat mutex, visibility lewat volatile |
| Matcher divalidasi ulang setelah fetch (ID + durasi + instrumental) | Metadata bisa berubah antara search dan fetch |
| Live smoke dihapus setelah bukti diambil | Suite permanen tetap offline dan deterministik |
| `distinctBy { trackId }` sebelum cap | Dedup sebelum pemilihan, bukan sesudah |

---

## 6. Yang masih belum terverifikasi

Implementer menyatakannya sendiri dengan tepat: **tidak ada perangkat Android terhubung** (`adb devices -l`). Jadi untuk fitur multi-kandidat, yang belum diverifikasi adalah permukaan app:

- tampilan daftar hasil
- pemilihan kandidat
- render dan sinkronisasi setelah memilih
- perilaku setelah cache dua menit habis
- toggle off dan playback biasa

APK sudah dibangun (`c76edf420`, CI app `42237722810`, Mobile Arm64 Debug). Checklist smoke perangkat di `implementer-notes.md` §5 sudah tepat dan lengkap — termasuk saran mengaktifkan **hanya** Musixmatch supaya sumber hasil tidak ambigu. Itu detail yang bagus.

Satu penegasan yang layak dikutip di PR: **empat adalah batas kandidat, bukan janji jumlah hasil UI.** Filter, error, dan dedup app bisa mengurangi jumlahnya.

---

## 7. Aksi sebelum PR upstream

Berurut:

1. **Pecah `Musixmatch.kt`** (§1) — satu-satunya pelanggaran panduan
2. **Nyatakan deviasi cap 4** beserta bukti run produksi di deskripsi PR (§2)
3. Konsistenkan guard status pada `restricted`, hapus pemeriksaan ganda (§4)
4. Tutup tiga celah uji lama (§3) — atau nyatakan terbuka secara eksplisit
5. **Smoke perangkat** sesuai checklist implementer — ini yang menahan klaim "selesai", bukan CI

Untuk PR submodule, urutan tetap: merge di `rukamori/lyrics` dulu, baru naikkan gitlink di repo app dan kembalikan `.gitmodules` ke upstream.
