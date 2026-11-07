# Review `552720b` — implementasi Hybrid Musixmatch

**Rentang:** `8765c5d..552720b` · **17 commit** · 22 file, **+3.431/−247**
**Tanggal review:** 7 Nov 2026 · **Basis keputusan:** `Musixmatch-Hybrid-Decisions.md`

---

## 0. Verdict

**Spesifikasi dikerjakan penuh, dan disiplin regression-first-nya terbukti di CI.** Tidak ada blocker correctness. Ada **satu celah presisi** yang nyata (§2.1), satu risiko latensi yang perlu diukur (§2.2), dan **satu penghambat uji perangkat yang bukan soal kode** (§2.5).

| metrik | sebelum | sekarang |
|---|---:|---:|
| `Musixmatch.kt` | 638 baris (melanggar batas 500) | **461** ✓ |
| file helper produksi | — | `MusixmatchSearch.kt` 162, `LyricsFormat.kt` 100, `SearchTokenState.kt` 81 |
| test | 68 | **105** |
| `SEARCH_PAGE_SIZE` | 5 | **20** ✓ |

**Lima pasang red→green**, semuanya terdokumentasi dengan run ID di `implementer-notes.md` §6:

| tahap | test dulu (gagal) | implementasi (lulus) |
|---|---|---|
| fallback dua arah | `18b9dfe` — 84 test, 14 gagal | `9616ec8` |
| separate RichSync search | `9c050b6` — 93 test, 6 gagal | `da6ed94` |
| pool 20 | `d87ec52` — 94 test, 1 gagal | `bca963f` |
| stable ranking | `de64e59` — 104 test, 6 gagal | `4635fa5` |
| round-trip | `a255f51` — compile gagal | `143184b` |

Itu bukan test yang ditulis setelah kode. Setiap perubahan perilaku punya sinyal gagal yang mendahuluinya.

---

## 1. Setiap keputusan terkunci punya test yang menjaganya

| keputusan | test pengunci |
|---|---|
| fallback pada **kedua** sebab kosong (usulan pemisahan ditolak) | `entirelyFilteredPoolStillTriesTitleMatcher` |
| kasus Koiiro: artist beda aksara, judul cocok | `emptySearchFallsBackWithAlbumDurationAndDifferentScriptArtist` |
| pool nonempty tidak memanggil matcher | `nonemptyPoolDoesNotTryTitleMatcherEvenIfAllCandidatesFail` |
| playback maksimal 2 kandidat, berurutan | `playbackAttemptsAtMostTwoCandidatesSequentially`, `playbackStopsAfterFirstSuccess` |
| playback fallback hanya setelah NoMatch/Mismatch | `primarySuccessRestrictedServerMalformedAndRateLimitDoNotSearch` |
| restricted per-rekaman di fallback | `restrictedFallbackCandidateIsSkippedButAllRestrictedReturnsRestricted` |
| 429 dan renewal gagal tetap terminal | `fallbackRateLimitAndFailedRenewalStopBeforeSecondCandidate`, `emptySearchMatcherRateLimitIsTerminal` |
| **stable sort, tanpa tiebreak trackId** | `equalKeysKeepResponseOrderRatherThanTrackIdOrder` |
| penanda versi hanya dari kualifikasi | `normalTitlesAndPartialMarkerWordsAreNotQualifiers` |
| versi yang diminta tidak dihukum | `explicitlyRequestedVersionIsNotPenalized` |
| gate dan dedup mendahului peringkat | `gateAndDedupStillPrecedeRatingAndLimit` |
| parity format kedua jalur | `standaloneRichsyncWinsOverSubtitleAndPlainInBothPaths` |
| fixture richsync opt-in | `hasRichsync: Int = 0` di `MusixmatchSearchSelectionTest.kt:362` |

Dua di antaranya menutup celah yang sudah lama terbuka: `missingInlineRichsyncFetchesByCommontrackIdAndEmitsTtml` adalah **test positif pertama** untuk `fetchRichSyncSeparately`, dan `MusixmatchTtmlRoundTripTest` menjalankan converter kita melalui **parser produksi**.

Round-trip itu bahkan memakai teks `Don't lose <3 & hope` — tepat kasus yang saya laporkan. Ia lulus, yang **mengukuhkan** bahwa bug `isEscapedTtml` ada di lapisan app (`LyricsUtils`), bukan di converter atau parser. Keputusan implementer untuk tidak mencampurnya ke paket ini benar.

---

## 2. Temuan

### 2.1 Celah presisi: versi yang diminta kalah oleh rating (sedang)

`versionDemerit` hanya menghukum penanda yang **tidak diminta**:

```kotlin
return if (markers and inputMarkers.inv() != 0) 1 else 0
```

Bila user memutar `Dynamite (Slow Jam Remix)`:

| kandidat | markers | demerit | rating |
|---|---:|---:|---:|
| `Dynamite` (kanonik) | 0 | **0** | **90** |
| `Dynamite (Slow Jam Remix)` | remix | **0** | 44 |

Keduanya demerit 0, lalu rating memutuskan → **kanonik peringkat pertama**, padahal user memutar remix. Untuk search itu masih bisa diterima; untuk **fallback playback** ia memilih versi yang salah secara otomatis.

`explicitlyRequestedVersionIsNotPenalized` tidak menangkap ini karena hanya memeriksa bahwa remix tidak dihukum, bukan bahwa remix menang.

**Perbaikan satu baris — beda simetris, bukan satu arah:**

```kotlin
return if ((markers xor inputMarkers) != 0) 1 else 0
```

Saya periksa terhadap kesepuluh test peringkat yang ada: untuk input tanpa penanda (`inputMarkers = 0`), `xor` menghasilkan nilai yang sama dengan ekspresi sekarang, jadi **semua test lama tetap hijau**. Untuk input berpenanda, kandidat yang kehilangan penanda yang diminta kini dihukum.

**Test baru yang diperlukan:** `requestedVersionOutranksHigherRatedCanonical` — input `Dynamite (Slow Jam Remix)`, kandidat kanonik rating 90 dan remix rating 44, keduanya lolos gate → assert remix dipilih lebih dulu.

### 2.2 Risiko latensi: richsync terpisah memegang permit semaphore (sedang)

`resolveTrackLyrics` kini melakukan request kedua bila richsync tidak inline. Di `getAllLyrics` itu terjadi **di dalam** `semaphore.withPermit`. Dengan concurrency 2 dan cap 4, worst case berubah dari 4 request / 2 gelombang menjadi **8 request / 4 gelombang**, terhadap `PROVIDER_TIMEOUT_MS = 8000`.

Di fallback playback, anggaran yang sama kini bisa memuat: macro utama + search + 2 macro kandidat + 2 richsync terpisah = **hingga 6 request**, ditambah token.

Ini bukan bug — parity format memang mengharuskan richsync dikejar. Tapi ini alasan paling mungkin bila nanti muncul hasil sebagian di perangkat. **Jangan mengubahnya sekarang.** Ukur dulu memakai log funnel yang sudah ada. Pemicu yang jelas: bila log menunjukkan `candidates=4` tetapi hanya 1–2 hasil tiba, turunkan `SEARCH_FALLBACK_MAX_ATTEMPTS` ke 1 **sebelum** menyentuh concurrency atau gate durasi.

### 2.3 Log funnel tidak mencakup jalur fallback (rendah)

`logSearchFunnel` dipanggil sebelum pemeriksaan `candidates.isEmpty()`, jadi saat fallback berjalan log hanya menunjukkan `candidates=0` tanpa mengatakan apakah matcher kemudian berhasil. GlobalLog belum bisa membedakan "0 kandidat → fallback sukses" dari "0 kandidat → tidak ada apa-apa".

Tambah satu baris di masuk `resolveTitleFallbackForSearch` dan `resolveSearchFallback`, misalnya `Musixmatch search fallback: title matcher` dan `Musixmatch playback fallback: ranked search`. Tanpa token, judul, atau isi lirik. Ini murah dan membuat pengukuran §2.2 bisa dibaca.

### 2.4 Nit: ekspresi path dependency perlu komentar (rendah)

```kotlin
testImplementation(project(project.path.substringBeforeLast(':') + ":betterlyrics"))
```

Ekspresinya **benar** untuk kedua konteks: standalone `:musixmatch` → `:betterlyrics`; terintegrasi `:lyrics:musixmatch` → `:lyrics:betterlyrics`. Tapi maksudnya tidak jelas dari kode, dan inilah yang membuat CI app `42319573238` gagal sebelumnya. Satu komentar di atasnya akan mencegah orang "menyederhanakannya" kembali menjadi path absolut.

### 2.5 Penghambat uji perangkat: APK dibangun tapi tidak diterbitkan

Ini bukan masalah kode provider, dan **ini yang menahan user memverifikasi Koiiro di perangkat**.

- App fork HEAD `ce94b86d` → gitlink `552720b` → CI **success** (run `42321917302`).
- Job `verify` menjalankan step **Build Mobile Arm64 Debug APK** → **success**.
- Tetapi `artifacts` untuk run itu: **`total_count = 0`**. `build_pull_request.yml` berakhir pada step build, tanpa `actions/upload-artifact`.

Jadi APK ada lalu dibuang. Karena laptop user tidak bisa menjalankan Gradle, satu-satunya jalan mendapatkan APK adalah CI. **Tambahkan step `actions/upload-artifact` setelah build di workflow PR fork** — perubahan kecil, khusus fork, tidak perlu masuk PR upstream. Tanpa itu, tidak ada yang bisa diuji di perangkat.

---

## 3. Yang jangan diubah saat revisi

- `resolveTrackLyrics` sebagai satu-satunya resolver untuk keempat jalur (playback utama, fallback playback, search normal, fallback search). Ini yang menjamin parity format dan sudah diuji.
- `matchedTrack(calls, expectedTrackId)` dengan guard id opsional — dipakai bersama, tidak diduplikasi.
- `SearchTokenState` dengan satu budget renewal per operasi yang mencakup transisi endpoint. `renewalUsed` di dalam mutex, `token`/`terminalFailure` `@Volatile`, dan reuse token bila korutin lain sudah memperbarui — semuanya tepat.
- Stable sort tanpa tiebreak id.
- Pemisahan file menjadi `MusixmatchSearch.kt` / `LyricsFormat.kt` / `SearchTokenState.kt`.
- Dependency `:betterlyrics` sebagai `testImplementation` saja.

---

## 4. Yang belum terverifikasi

Dinyatakan jujur oleh implementer, dan saya konfirmasi:

- **Tidak ada uji API live untuk jalur hybrid.** Smoke JVM `6eca335` memakai MockEngine, bukan Musixmatch nyata. Ia membuktikan urutan endpoint, ranking sebelum cap, dan parsing TTML — bukan bahwa API live berperilaku seperti fixture.
- **Tidak ada uji perangkat.** Render, sync, pemilihan kandidat di menu, cache 2 menit, toggle off — semuanya belum.
- `implementer-notes.md` §6 sedikit tertinggal: menyatakan gitlink app belum diperbarui, padahal `ce94b86d` sudah memperbarui ke `552720b` dan CI-nya hijau. Perlu satu kalimat koreksi.

**Verifikasi live yang paling bernilai sekarang:** ulangi kasus Koiiro (`q_track=Koiiro`, `q_artist=Mosawo`) melalui provider produksi. Pengukuran saya menunjukkan `track.search` mengembalikan 0 dan matcher mengembalikan `track_id=250999168`; fallback seharusnya menghasilkan LRC. Itu satu request search + satu macro, volume kecil, dan membuktikan perbaikan pada kasus yang dilaporkan — bukan pada fixture.

---

## 5. Aksi sebelum PR upstream

1. §2.1 beda simetris + test baru (satu-satunya perubahan perilaku yang saya minta).
2. §2.3 dua baris log fallback.
3. §2.4 komentar path dependency.
4. §2.5 step upload artifact di fork, lalu uji perangkat: Koiiro, アイドル, Blinding Lights, Dynamite.
5. Koreksi satu kalimat di `implementer-notes.md`.
6. Blocker upstream yang lama masih berlaku: `.gitmodules` harus kembali ke `rukamori/lyrics.git`, hapus `branch=feat/musixmatch`, pointer SHA upstream setelah submodule di-merge.
