# Apakah pencarian kita terlalu ketat? + Review dokumen Hybrid Musixmatch

> **KOREKSI PENTING (lihat §12).** Kolom "lolos gate di 5" pada tabel §1 **tidak sahih** — saya mensimulasikan `page_size=5` dengan memotong respons `page_size=20`, padahal Musixmatch mengembalikan **himpunan hasil yang berbeda** untuk `page_size` berbeda. §12 mengukur ulang dengan pemanggilan nyata dan mereproduksi kedua angka yang dilaporkan user. Baca §12 sebelum §1. **Root cause laporan user sudah terpecahkan di §14** — nama artist beda aksara (`Mosawo` vs `もさを。`); `track.search` ketat, matcher fuzzy.

**Tanggal:** 6 Nov 2026 · **HEAD ditinjau:** `8765c5d` (`adam-adrian/lyrics` @ `feat/musixmatch`)
**Dokumen yang direview:** `uploads/DOKUMENTASI-HYBRID-MUSIXMATCH-UNTUK-AI-AGENT.md`
**Bukti:** 27 request live dari sandbox (`tools/mxm-search-funnel.py`, `tools/mxm-dedup-probe.py`)

---

## 0. Jawaban singkat

**Sebagian ya, tapi bukan di tempat yang dokumen itu tunjuk.**

- Yang benar-benar terlalu sempit: **`SEARCH_PAGE_SIZE = 5`**. Menaikkannya ke 20 menambah kandidat di **4 dari 8** lagu uji, tanpa menambah satu pun request.
- Yang **tidak** boleh dilonggarkan: **gate durasi ±8 s**. Data live menunjukkan gate itu justru yang menyaring remix, versi live, dan cover. Melonggarkannya menurunkan kualitas, bukan menaikkannya.
- **"Cuma satu lagu yang return >1" tidak bisa direproduksi dari sisi API.** Untuk Blinding Lights dan Dynamite, API menghasilkan **3 lirik berbeda** yang lolos dedup. Jadi kehilangan kandidat terjadi di perangkat, bukan di filter. Perlu satu baris log untuk memastikan (§4).
- Dokumen itu memuat **2 bug yang membuat fix utamanya mustahil berhasil**, **1 klaim root-cause yang terbantah live**, dan **3 regresi desain**. Jangan dikerjakan apa adanya.

---

## 1. Corong pencarian — di tahap mana kandidat hilang

`track.search` dipanggil dengan parameter produksi persis (`q_track`, `q_artist`, `q_artists`, `f_has_lyrics=1`), tetapi `page_size=20` sebagai superset. Kolom "di 5" mensimulasikan produksi dengan mengambil 5 teratas.

| Lagu | mentah | lolos gate di 20 | lolos gate di 5 | kandidat produksi | jika page=20 |
|---|---:|---:|---:|---:|---:|
| Bohemian Rhapsody | 19 | 7 | 5 | **4** | 4 |
| アイドル / YOASOBI | 1 | 1 | 1 | **1** | 1 |
| Idol / YOASOBI | 2 | 2 | 2 | **2** | 2 |
| 夜に駆ける / YOASOBI | 2 | 1 | 1 | **1** | 1 |
| Levitating / Dua Lipa | 10 | 3 | 2 | **2** | **3** |
| Dynamite / BTS | 13 | 9 | 3 | **3** | **4** |
| Blinding Lights / The Weeknd | 20 | 7 | 3 | **3** | **4** |
| Shape of You / Ed Sheeran | 20 | 7 | 3 | **3** | **4** |

**Bacaan:** Musixmatch menyelipkan remix/live ke dalam 5 teratas, sehingga gate ±8 menyisakan hanya 2–3. Kolam yang lebih dalam memberi gate lebih banyak bahan. `cap 4` kemudian menjadi pembatas, bukan kolamnya.

**`header.available` untuk アイドル = 1.** Tidak ada filter, toleransi, atau page size yang bisa menambah kandidat di sana — memang cuma satu track. Kasus unggulan dokumen itu **tidak bisa** diperbaiki oleh multi-kandidat.

### 1.1 Apa yang sebenarnya dibuang gate ±8

| Lagu | contoh yang dibuang | delta |
|---|---|---:|
| Bohemian Rhapsody | `Bohemian Rhapsody (Live Aid)` | −206 s |
| Bohemian Rhapsody | `Bohemian Rhapsody - Live` (Queen Rock Montreal) | −25 s |
| Levitating | `Levitating - KUU Remix` | +48 s |
| Levitating | `Levitating (Live From Mexico)` | +55 s |
| Blinding Lights | `Blinding Lights` — album `Café Jazz 2025` | +29 s |
| Shape of You | `Shape of You (NOTD Remix)` | −43 s |
| Dynamite | `Dynamite (Poolside Remix)` | −17 s |

Ini bukan kandidat yang hilang secara tidak adil — ini **versi yang salah untuk diputar otomatis**. Gate ±8 adalah perlindungan kualitas utama kita.

Satu-satunya yang layak disayangkan: `夜に駆ける - From THE FIRST TAKE` (−11 s), pertunjukan berbeda yang sah. Itu argumen untuk **tier kedua khusus pencarian manual**, bukan untuk menaikkan toleransi global (§5 R3).

---

## 2. Dua hipotesis lain sudah diuji dan gugur

**Dedup app bukan penyebabnya.** `LyricsHelper` membuang hasil hanya bila teks lirik identik persis (`results.any { it.result == entry.result }`). Diuji dengan macro per kandidat:

| Lagu | kandidat | format dihasilkan | hash unik |
|---|---:|---|---:|
| Bohemian Rhapsody | 3 | subtitle, richsync, richsync | **3** |
| Blinding Lights | 3 | richsync, richsync, plain | **3** |
| Dynamite | 3 | richsync, subtitle, richsync | **3** |

`lyrics_id` dan `subtitle_id` berbeda antar-kandidat, jadi tidak ada penggabungan.

**Wiring app bukan penyebabnya.** `MusixmatchLyricsProvider` meng-override `getAllLyrics` dan meneruskan `callback` apa adanya. `LyricsMenuViewModel.searchLyrics` menambahkan setiap hasil tanpa dedup (`toUiModel(resultModels.size)`).

**Catatan penting:** richsync dan subtitle sering datang bersamaan. Ini menguatkan larangan membalik urutan format (§3.3).

---

## 3. Review dokumen Hybrid Musixmatch

Pengamatan inti dokumen itu **benar dan berhasil direproduksi**: macro-by-title gagal untuk `アイドル`, sementara `track.search` menemukannya. Tapi penjelasan dan fix-nya bermasalah.

### 3.1 Root-cause utama dokumen terbantah live

Dokumen §2: *"`namespace=lyrics_richsynched` hanya return track dengan richsync. Idol `has_richsync=0` → 404."*

Diuji 4 varian macro untuk `アイドル / YOASOBI`:

| Varian | `matcher.track.get` | `track.subtitles.get` | `track.lyrics.get` |
|---|---:|---:|---:|
| M1 produksi apa adanya | 404 | 404 | 404 |
| M2 **tanpa `namespace`** | **404** | 404 | 404 |
| M3 tanpa `q_artists` | 404 | 404 | 404 |
| M4 tanpa `q_duration`+`f_subtitle_length` | 404 | 404 | 404 |

Menghapus `namespace` **tidak mengubah apa pun**. Klaim itu juga bertentangan dengan bukti dokumen sendiri: `matcher.track.get` tidak bergantung pada `namespace`, jadi 404 di situ tidak mungkin disebabkan filter richsync.

Yang benar: **matcher pada `macro.subtitles.get` gagal untuk judul kana ini.** Penyebabnya di sisi Musixmatch, bukan parameter kita.

### 3.2 Dua bug yang membuat fix dokumen mustahil berhasil

**(a) `subtitle_format=lrc` lalu diparsing sebagai JSON → selalu `null`.**

Diuji pada `track_id=262827810`:

| `subtitle_format` | bentuk `subtitle_body` |
|---|---|
| `lrc` | **teks LRC siap pakai**, sudah bertimestamp |
| `mxm` | **JSON array**, 76 baris, kunci `['text','time']` |

Dokumen §3.4 meminta `lrc` lalu memanggil `formatSubtitlesToLrc(body)`. Fungsi itu melakukan `jsonFormat.decodeFromString<List<SubtitleLine>>(...)` di dalam `runCatching`. Teks LRC bukan JSON → exception → `getOrNull()` → **`null`**.

Akibatnya jalur yang dokumen klaim sebagai fix untuk Idol justru **selalu gagal**, lalu jatuh ke plain. Kriteria terima "success LRC" tidak akan pernah tercapai. Perbaikan: pakai `subtitle_format=mxm` (satu formatter), atau pakai `lrc` dan kembalikan body tanpa diparsing ulang.

**(b) Regex CJK melempar exception saat inisialisasi.**

Diuji di JVM:

```
[\p{Hiragana}\p{Katakana}\p{Han}]
  -> PatternSyntaxException: Unknown character property name {In/IsHiragana}
[\p{IsHiragana}\p{IsKatakana}\p{IsHan}]
  -> OK
```

Karena `CJK_ARTIST_HINT` adalah `private val` di dalam `object Musixmatch`, kegagalan ini terjadi saat inisialisasi objek — bukan hanya saat lagu Jepang diputar. Setelah diperbaiki ke `Is...`, polanya tetap **tidak mencakup Hangul**, padahal dokumen menyebut K-Pop sebagai target.

### 3.3 Tiga regresi desain

**(a) Urutan format dibalik.** Dokumen §3.5 mencoba **subtitle → richsync → plain**. Keputusan terkunci kita: **richsync → subtitle → plain**. Karena keduanya sering datang bersamaan (§2), urutan dokumen akan **menurunkan** lirik word-synced menjadi line-synced pada lagu yang sekarang sudah dapat TTML.

**(b) `catch (e: MusixmatchError) { null }` menelan `RateLimited` dan `TokenInvalid`.** Desain `SearchTokenState` kita justru menghentikan seluruh operasi saat 429 atau renewal gagal. Skeleton dokumen akan melanjutkan ke `track.search` setelah 429 — memperburuk throttle, bukan meredakannya.

**(c) `.ifEmpty { candidates }` membuat gate durasi tak terbatas.** Bila semua kandidat tersaring, dokumen memakai seluruh kolam tanpa gate durasi sama sekali. Untuk lagu Western dengan judul cocok tetapi versi live, ini memilih versi yang salah — bertentangan dengan tujuan dokumen sendiri ("tanpa mengorbankan akurasi Western").

### 3.4 `scoreTrackVivi` justru merusak akurasi CJK

Dokumen menyebut `cleanText` membuang kana lalu menganggapnya "bukan penyebab utama". Diukur dengan replikasi regex-nya persis:

| query | track | `textScore` |
|---|---|---:|
| アイドル | アイドル | **0** (exact) |
| アイドル | 夜に駆ける | **0** (exact) |
| アイドル | 怪物 | **0** (exact) |
| Idol | アイドル | **2** (terburuk) |

Dua lagu Jepang yang **berbeda** dinilai identik, sementara pasangan romaji↔kana yang **benar** dinilai paling buruk. Untuk CJK, scoring ini praktis hanya mengandalkan durasi; bila durasi tidak diketahui, semuanya seri dan pemenangnya adalah `track_list[0]`.

`normalizeString` kita mempertahankan kana dan sudah benar. Jangan diganti.

### 3.5 Dokumen salah membaca kode kita

Dokumen §2 menyebut validasi kita menuntut `titleOk && artistOk`. Kode sebenarnya:

```kotlin
if (!titleOk && !artistOk) throw MusixmatchError.Mismatch(...)
```

Artinya cukup **salah satu** cocok — sudah permisif, bukan keras. Premis "validasi keras menolak kandidat" tidak berlaku untuk `getAllLyrics`, yang bahkan tidak memanggil `validateMatch` sama sekali.

### 3.6 Sudah kedaluwarsa

- *"Tidak ada fallback search"* — `getAllLyrics` sudah punya search→macro-by-id sejak `f3677bb`/`8765c5d`. Yang belum punya hanya `getLyrics` (playback).
- *"Jangan ubah `getAllLyrics` dulu, multi-candidate phase 2"* — sudah selesai.
- *"existing 10+ tests"* — sekarang **68**.
- Token live ditulis di dokumen yang ditujukan untuk handed-off ke repo. **Jangan pernah di-commit.** Dokumen juga menyebut masa berlaku 5 menit di §5 dan ~10 menit di §6.

---

## 4. Kenapa di perangkat cuma muncul 1?

API menghasilkan 3 untuk lagu-lagu yang seharusnya mudah. Jadi kehilangan terjadi di perangkat. Tiga kemungkinan, berurut dari paling murah untuk dipastikan:

1. **Build yang diuji lebih tua dari multi-kandidat.** APK dari run `42237722810` (`fd866c0`) sudah berisi multi-kandidat. APK dari era PR #51 (`fa12aec`) belum — di sana Musixmatch selalu memberi 1 hasil.
2. **Timeout provider 8 s dengan token dingin.** `LyricsHelper` memakai `withTimeoutOrNull(8000)`; hasil yang sudah masuk channel tetap dipakai, sisanya hilang. Token dingin pernah terukur 3.451 ms; ditambah search dan dua gelombang macro, di jaringan seluler ini bisa melewati 8 s sehingga hanya 1–2 hasil tiba.
3. **Durasi perangkat ≠ durasi kanonik.** Bila durasi yang dikirim berasal dari versi video, gate ±8 bisa membuang entri studio yang benar.

**Yang membuat ini tidak bisa dijawab sekarang: modul tidak pernah melaporkan corongnya.** `logger` hanya menulis "candidate skipped" tanpa angka. Satu baris log di `getAllLyrics` akan menyelesaikan pertanyaan ini dari GlobalLog, tanpa perlu menebak:

```
Musixmatch search: returned=13 eligible=9 candidates=4
```

Tidak memuat token, judul, atau isi lirik. Ini perubahan paling murah dengan nilai diagnosis paling besar, dan layak masuk sebelum menaikkan page size.

### 4.1 Temuan operasional baru: `token.get` memicu captcha

Dua `token.get` berurutan dalam hitungan detik: yang pertama 200, yang kedua **401 dengan `hint=captcha`**. Tiga menit kemudian, satu `token.get` tunggal berhasil lagi.

Ini pengamatan pada volume dan lingkungan ini saja, bukan ambang yang diketahui. Tapi memperkuat dua hal yang sudah ada: **cache token + single-flight wajib**, dan setiap desain yang memanggil `token.get` lebih dari sekali per operasi berisiko. Sesuai kebijakan sebelumnya, **401 bukan bukti rate limit** — di seluruh 27 request ini tidak ada satu pun 429.

---

## 5. Rekomendasi, dari paling murah

**R0 — log corong (sebelum apa pun).** Satu baris di `getAllLyrics`: jumlah mentah, jumlah lolos gate, jumlah setelah cap. Uji: tidak perlu test baru untuk string log, tapi pastikan tidak memuat data sensitif.

**R1 — `SEARCH_PAGE_SIZE` 5 → 20.** Satu konstanta. Jumlah request tetap, `cap 4` tetap membatasi fanout. Bukti: 4 dari 8 lagu naik dari 2–3 menjadi 3–4 kandidat. Uji: parameter `page_size` bernilai 20; kandidat yang berada di indeks ke-7 tetap lolos; jumlah macro tidak melebihi 4.

**R2 — fallback search di `getLyrics`, memakai ulang yang sudah ada.** Ini mengganti seluruh §3.3–§3.6 dokumen dengan perubahan jauh lebih kecil.

Diuji live: macro **by `track_id`** untuk Idol yang macro-by-title-nya 404 →

```
matcher.track.get 200 · track.subtitles.get 200 · track.richsync.get 404 · track.lyrics.get 200
-> resolveMacroLyrics menghasilkan subtitle LRC
```

Jadi **tidak perlu endpoint `track.subtitle.get` baru, tidak perlu model baru, tidak perlu scoring baru.** Cukup: bila macro-by-title gagal `NoMatch`/`Mismatch`, panggil `executeTrackSearch` → ambil 1 kandidat teratas → `executeMacroByTrackId` → `validateMatch` → `resolveMacroLyrics`. Semua sudah ada dan sudah diuji untuk `getAllLyrics`.

Aturan yang wajib dijaga:
- **Jangan** fallback pada `RateLimited`, `TokenInvalid` yang gagal diperbarui, atau `Restricted`.
- Jalur sukses Western tetap **1 RTT**; fallback hanya di jalur gagal.
- `validateMatch` tetap dipakai di playback, termasuk gate teks. Untuk Idol, `normalizeString` mempertahankan kana sehingga lolos.
- Prioritas format tetap richsync → subtitle → plain, karena memakai `resolveMacroLyrics` yang sama.
- Uji: macro 404 → search → macro-by-id 200 → LRC; macro 429 → **tidak** ada search; macro `Restricted` → **tidak** ada search; macro sukses → **tidak** ada search.

**R3 — tier durasi kedua, hanya untuk pencarian manual (perlu keputusan).** Bila tier ketat menghasilkan < 3 kandidat, tambahkan dari pita ±20 s, **diletakkan setelah** kandidat tier ketat. Playback tetap ±8. Ini menangkap `THE FIRST TAKE` (−11 s) tanpa mengorbankan urutan presisi. Konsekuensi yang harus diterima: `Dynamite (Retro Remix)` (+9) dan `Shape of You (Acoustic)` (−9) juga akan muncul di pencarian manual. Untuk pencarian manual itu wajar — user yang memilih. Jangan naikkan concurrency.

**R4 — jangan dikerjakan.** `cleanText` Vivi; toleransi 30 s berbasis deteksi script; `.ifEmpty { candidates }`; membalik urutan format; endpoint `track.subtitle.get` terpisah; `apic.musixmatch.com` (403, butuh signing).

**Catatan recall, bukan tugas:** `q_track=Idol` mengembalikan 2 track (termasuk versi Inggris di `E-SIDE 3` yang punya richsync), sedangkan `q_track=アイドル` mengembalikan 1. Jadi arah perbaikan recall CJK adalah **memperluas query**, bukan melonggarkan filter. Transliterasi tetap di luar scope MVP.

---

## 6. Batas bukti

- 27 request, satu lingkungan (sandbox), tanpa 429, satu 401 captcha pada `token.get`. **Tidak mengalami throttle pada pengujian terbatas ini** — bukan pernyataan aman secara umum.
- Angka corong berlaku untuk 8 lagu ini pada saat ini. Urutan hasil Musixmatch bisa berubah; `page_size=20` mensimulasikan produksi dengan mengambil 5 teratas, bukan memanggil `page_size=5` terpisah.
- Format yang dilaporkan berasal dari isi body macro, **bukti kelayakan input** — konverter Kotlin, `TTMLParser`, dan renderer tidak dijalankan di sini.
- Tidak ada perangkat Android. Penyebab pasti "cuma 1 hasil" **belum dipastikan**; §4 adalah hipotesis berurut, dan R0 ada untuk memutusnya.

---

## 7. Artefak

- `tools/mxm-search-funnel.py` — corong + 4 varian macro + bentuk `subtitle_body`
- `tools/mxm-dedup-probe.py` — macro-by-id untuk Idol + kesamaan lirik antar-kandidat
- `tools/funnel-result.json`, `tools/dedup-result.json` — hasil mentah, tanpa token dan tanpa isi lirik

---

# Addendum — setelah build dipastikan

User menguji dari `adam-adrian/ArchiveTune` commit **`c76edf420`** ("fix(lyrics): include reviewed Musixmatch search corrections", 6 Nov 2026 14:51 UTC).

## 8. Build itu memang berisi multi-kandidat — hipotesis "APK lama" gugur

Commit tersebut hanya mengubah satu file: gitlink `lyrics`.

```
-Subproject commit b1697f441be2fcf0a47c4f95804a3005e47a04b7
+Subproject commit fd866c0b77e4457fb6bd1e5fade5bea20dc993c2
```

Diverifikasi di clone:
- `fd866c0` memuat `SEARCH_MAX_CANDIDATES` → multi-kandidat ada.
- `git diff fd866c0 8765c5d` = **1 file, 4 baris, `implementer-notes.md` saja**. Jadi kode di HEAD identik dengan kode yang di-build.
- Gitlink **sebelumnya** (`b1697f44`, "test(musixmatch): avoid callback cancellation test deadlock") juga sudah punya multi-kandidat.

**Kesimpulan: APK dari commit ini maupun satu commit sebelumnya sama-sama punya multi-kandidat.** §4 hipotesis 1 tidak berlaku. Masalahnya nyata dan layak didiagnosis.

## 9. Pemeriksaan jalur render app

Karena `LyricsHelper` membuang hasil yang `lyricsOrNotFound()`-nya `LYRICS_NOT_FOUND`, dan itu bergantung pada `displayLyricsText()` → untuk TTML bergantung pada `parseTtml()`, jalur ini pantas dicurigai: **hasil richsync bisa hilang diam-diam bila preview-nya kosong.**

### 9.1 Satu kecurigaan gugur: format waktu kita valid

Converter kita menulis `begin="7.748s"`. `TTMLParser.parseTime` menangani suffix metrik melalui `OFFSET_TIME_REGEX` dengan cabang `"s" -> amount`. Jadi format offset-time kita **didukung**. Bukan penyebab.

### 9.2 Satu bug nyata, tapi sempit: `isEscapedTtml` salah positif

`LyricsUtils.isEscapedTtml` (`LyricsUtils.kt:565`):

```kotlin
return trimmed.startsWith("&lt;tt", true) ||
    trimmed.contains("&lt;tt", true) ||
    trimmed.contains("http://www.w3.org/ns/ttml", true) &&
    trimmed.contains("&lt;", true)
```

TTML kita **selalu** memuat `http://www.w3.org/ns/ttml`, jadi klausa ketiga praktis berarti "mengandung `&lt;`". `escapeXml` kita mengubah `<` dalam teks lirik menjadi `&lt;`. Akibatnya lirik yang memuat karakter `<` literal membuat dokumen dianggap TTML ter-escape, lalu `normalizeLyricsText` meng-unescape `&lt;`/`&gt;`/`&quot;`/`&apos;` **di seluruh dokumen** → XML rusak.

Diuji dengan replikasi persis logika string Kotlin (`tools/ttml-escape-probe.py`):

| isi lirik | `isEscapedTtml` | hasil parse setelah `normalize` |
|---|---:|---|
| normal | false | OK |
| apostrof (`Don't`) | false | OK |
| ampersand (`Rock & Roll`) | false | OK |
| **`<3`** | **true** | **GAGAL: not well-formed** |

**Batas klaim:** yang direplikasi persis adalah transformasi string Kotlin; parser XML yang dipakai adalah proxy, `parseDocument` Kotlin belum dieksekusi. Dan pemicunya hanya `<` literal — **sempit**, jadi ini **bukan** penjelasan untuk "hampir semua lagu cuma 1". Tetap layak dilaporkan ke upstream sebagai bug terpisah.

## 10. Eksperimen penentu, bukan tebakan lanjutan

Dua langkah, keduanya murah dan tidak menebak.

### 10.1 Uji round-trip lintas modul di CI lyrics

Sampai sekarang konverter TTML kita dan parser yang akan membacanya **belum pernah diuji bersama**. `MusixmatchTtmlTest` hanya memeriksa bentuk keluaran converter.

`betterlyrics` adalah modul **Kotlin JVM murni** (`kotlin.jvm`, `jvmToolchain(21)`, dependensi hanya ktor + junit) dan sudah ada di `settings.gradle.kts` sebagai `:betterlyrics`. Jadi tidak ada siklus dan tidak perlu entri catalog baru:

```kotlin
// musixmatch/build.gradle.kts
testImplementation(project(":betterlyrics"))
```

Uji baru di `musixmatch/src/test/.../MusixmatchTtmlRoundTripTest.kt`:

1. Fixture richsync → `MusixmatchTtml.convertRichSyncToTtml(lines)` → `TTMLParser.parseTTML(ttml)`.
2. Assert: hasil tidak kosong; jumlah baris sama dengan jumlah `<p>`; setiap `ParsedLine.text` tidak blank.
3. Assert: `words` tidak kosong dan `endTime > startTime` — memastikan word-sync selamat, bukan hanya line-sync.
4. Kasus tepi: lirik dengan spasi antar-kata, apostrof, ampersand, dan `<3`.

Bila uji ini gagal, penyebab "cuma 1 hasil" ditemukan tanpa perlu perangkat. Bila lolos, jalur render tersingkir dan tersisa timeout/durasi.

### 10.2 Dua baris log yang memutus pertanyaan

Yang membuat pertanyaan ini tidak terjawab sekarang adalah tidak adanya angka di kedua sisi batas.

**Di modul** (`getAllLyrics`, setelah `candidates`):

```
Musixmatch search: returned=13 eligible=9 candidates=4
```

**Di app** (`LyricsHelper`, saat `normalized == LYRICS_NOT_FOUND`):

```
Musixmatch result dropped: empty preview
```

Tidak memuat token, judul, atau isi lirik. Dengan dua baris ini, GlobalLog langsung membedakan tiga kasus yang sekarang tidak bisa dipisahkan: modul hanya mengirim 1 · modul mengirim 3 tetapi 2 dibuang app · modul mengirim 3 tetapi 2 tidak sempat tiba sebelum timeout 8 s.

**Urutan yang disarankan: 10.2 → 10.1 → R1 (page size) → R2 (fallback playback).** Instrumentasi dulu, supaya perubahan berikutnya diukur, bukan diasumsikan.

## 11. Kemungkinan yang belum dipertimbangkan: ini mungkin bukan bug

Data §1 menunjukkan pola katalog yang tajam:

| kategori | kolam mentah | kandidat |
|---|---:|---:|
| アイドル (kana) | **1** (`header.available = 1`) | 1 |
| 夜に駆ける | 2 | 1 |
| Idol (romaji) | 2 | 2 |
| Blinding Lights | 20 | 3 |
| Shape of You | 20 | 3 |
| Dynamite | 13 | 3 |

Katalog Musixmatch untuk J-Pop punya **sedikit rekaman duplikat**; katalog Western penuh kompilasi. Kalau lagu yang diuji sebagian besar J-Pop/CJK, maka "cuma satu lagu yang return 2" adalah **perilaku yang benar**, bukan pencarian yang terlalu ketat — dan menaikkan `page_size` tidak akan mengubahnya sama sekali.

**Yang perlu dipastikan sebelum mengubah apa pun:** daftar lagu yang diuji. Bila semuanya CJK, R1 tidak akan menolong dan prioritasnya pindah ke R2 (fallback playback) plus perluasan query. Bila ada lagu Western populer yang tetap hanya memberi 1, itu anomali dan 10.2 akan menunjukkan di mana hilangnya.

---

## 12. Pengukuran ulang dengan `page_size` nyata — kedua laporan user reproduksi

Lagu dari user: `J7p4bzqLvCw` = **Blinding Lights / The Weeknd** (lapor: 1 hasil), `HaEYUJ2aRHs` = **Dynamite / BTS** (lapor: 3 hasil).

Dipanggil dengan parameter produksi persis, `page_size=5`:

| lagu | hasil mentah | lolos gate | kandidat | laporan user |
|---|---:|---:|---:|---|
| Blinding Lights | 5 | 1 | **1** | **1** ✓ |
| Dynamite | 5 | 3 | **3** | **3** ✓ |

**Keduanya cocok persis.** Jadi tidak perlu bug app, timeout, atau kegagalan TTML untuk menjelaskan laporan ini. Modul berperilaku sesuai masukan yang diterimanya.

### 12.1 Kesalahan metode saya: `page_size` mengubah himpunan, bukan memotong

Blinding Lights, dua pemanggilan nyata:

| `page_size=5` (produksi) | `page_size=20` |
|---|---|
| #1 200 s · After Hours · rating 90 | #1 200 s · After Hours |
| #2 253 s · Live At SoFi Stadium | #2 221 s · Blinding Lights |
| #3 202 s · **Instrumental** | #3 196 s · Blinding Lights |
| #4 61 s · blinding's lights | #4 216 s · Blinding Lights |
| #5 216 s · Halloween 2023 | #5 202 s · Official Covers, Vol.1 |

Daftar `page_size=5` **bukan prefix** dari daftar `page_size=20`. Posisi 2–5 pada `page_size=5` setara dengan posisi 12–15 pada daftar 20. Dengan `page_size=5`, Musixmatch justru menyajikan versi live, instrumental, potongan 61 detik, dan remix — sehingga hanya After Hours yang lolos.

**Karena itu kolom "lolos gate di 5" di §1 saya tarik.** Angka §1 untuk kolom `page_size=20` tetap sahih (pemanggilan nyata); kolom simulasinya tidak.

### 12.2 R1 naik prioritas dan sekarang tervalidasi pada lagu yang dilaporkan

| lagu | `page_size=5` | `page_size=20` |
|---|---:|---:|
| Blinding Lights | **1** | **3** |
| Dynamite | **3** | **4** |

Ini bukan lagi inferensi dari kolam yang lebih dalam — ini pengukuran langsung pada dua lagu yang user keluhkan. **`SEARCH_PAGE_SIZE` 5 → 20 adalah perubahan tunggal dengan dampak terbesar**, tetap satu request, `cap 4` tetap membatasi fanout.

### 12.3 Masalah baru yang terlihat: peringkat kandidat buruk

Dynamite, `page_size=5`, yang lolos gate:

| track_name | album | rating |
|---|---|---:|
| Dynamite | Dynamite (Extended) | **90** |
| Dynamite (Live) | PERMISSION TO DANCE ON STAGE - LIVE | 51 |
| Dynamite (Bedroom Remix) | Dynamite (Bedroom Remix) | 42 |

Hasil pertama benar, dua sisanya versi salah. Pada `page_size=20` tambahannya Midnight/EDM/Tropical/Acoustic Remix dan `Dynamite - Cover` (rating 29). Jadi memperbesar kolam **perlu disertai peringkat**, bukan berdiri sendiri.

**`track_rating` adalah sinyal yang kuat dan sudah tersedia di respons** — rekaman kanonik 90, remix 42–53, cover 29, instrumental 38. Usulan:

1. Buang kandidat dengan penanda versi pada `track_name` bila judul input tidak memuatnya: `remix`, `live`, `cover`, `instrumental`, `acoustic`, `arrangement`. Bila judul input sendiri memuat penanda itu, jangan dibuang.
2. Urutkan sisanya by `track_rating` menurun.
3. Baru `take(4)`.

Ini perlu field baru `track_rating` di model `Track` dan satu fungsi peringkat murni yang mudah diuji. **Jangan** mengubah gate durasi.

### 12.4 Tiga variabel diuji dan ditolak

| variabel | hasil | keputusan |
|---|---|---|
| `s_track_rating=desc` (server-side) | Dynamite top-5 jadi Tropical/EDM/Acoustic Remix — **lebih buruk** | **jangan pakai**; peringkat dilakukan di klien setelah gate |
| hapus `f_has_lyrics=1` | muncul entri 128 s dengan `has_lyrics=0`, `has_subtitles=0` | **pertahankan** filternya |
| hapus `q_artists` | hasil **identik** dengan `q_artist` saja | redundan; boleh dibersihkan, bukan perbaikan |

### 12.5 Yang jadi turun prioritas

Karena laporan user sudah terjelaskan tanpa bug app: §9.2 (`isEscapedTtml` + `<` literal) tetap bug nyata tapi **tidak berhubungan** dengan laporan ini. §10.1 (uji round-trip TTML) dan §10.2 (dua baris log) tetap layak dikerjakan sebagai jaring pengaman, **bukan** sebagai langkah pertama lagi.

Urutan yang direvisi: **R1 (`page_size`) → peringkat kandidat (§12.3) → R2 (fallback playback) → §10.1/§10.2 → R3.**

### 12.6 Item ketiga masih perlu diperjelas

Link ketiga sama dengan link kedua (`HaEYUJ2aRHs`), dengan catatan "di search tidak ada, tapi dari menu refetch ada". Dua jalur itu memang memakai pencocokan berbeda:

- **search** → `LyricsHelper.getAllLyrics` → `Musixmatch.getAllLyrics` → `track.search` + gate kita
- **refetch** → `LyricsMenuViewModel:226` → `LyricsHelper.getLyrics(forceRefresh=true)` → `Musixmatch.getLyrics` → `macro.subtitles.get` matcher, yang menerima `q_album`, `q_duration`, `f_subtitle_length`

Karena pencocoknya berbeda, **rekaman yang dipilih bisa berbeda**. Itu penjelasan struktural yang masuk akal, tapi saya belum bisa memastikan tanpa tahu *apa* yang tidak muncul: provider Musixmatch seluruhnya, atau versi tertentu. Perlu diperjelas sebelum dijadikan dasar perubahan.

---

## 13. Item ketiga: asimetri search vs refetch

Klarifikasi user: yang hilang **hanya di jalur search**; dari menu refetch ada.

### 13.1 Yang diuji dan gugur

| hipotesis | hasil | status |
|---|---|---|
| Matcher refetch memilih track yang tidak dikembalikan `track.search` | Dynamite → `201712948`, Blinding Lights → `194169545`; **keduanya ada di hasil search, peringkat #1** | **gugur untuk dua lagu ini** |
| `macro.subtitles.get?track_id=X` mengembalikan id berbeda sehingga `getAllLyrics` menolak (`matcherTrack.trackId != candidate.trackId`) | diuji 3 kandidat Dynamite: id dikembalikan **sama**, `track_length` juga sama | **gugur** |
| `f_has_lyrics=1` membuang track yang punya subtitle tapi `has_lyrics=0` | pada Dynamite: **0 entri** semacam itu | **gugur untuk lagu ini**, tetap mungkin di lagu lain |

Jadi untuk dua lagu yang linknya diberikan, asimetri ini **tidak dapat direproduksi**. Link ketiga sama dengan link kedua, sehingga lagu ketiganya belum diketahui.

### 13.2 Satu asimetri kode yang nyata dan terverifikasi: `fetchRichSyncSeparately` tidak ada di jalur search

Ini ditemukan saat membandingkan kedua jalur, dan **tidak bergantung pada lagu ketiga**.

`getLyrics` (refetch), `Musixmatch.kt:536-543`:

```kotlin
val additionalRichSync = if (macroRichSync == null && track?.isRichsyncAvailable == true) {
    fetchRichSyncSeparately(track.commonTrackId, track.trackLength, currentToken)
} else null
val lyrics = resolveMacroLyrics(calls, additionalRichSync)
```

`getAllLyrics` (search), `Musixmatch.kt:610`:

```kotlin
resolveMacroLyrics(calls)   // tidak ada argumen kedua
```

**Akibatnya:** untuk track dengan `has_richsync=1` tetapi richsync **tidak inline** di macro, dan yang **tidak punya subtitle maupun plain lyrics**:

- **refetch** → `track.richsync.get?commontrack_id=` → TTML → **hasil muncul**
- **search** → inline kosong → subtitle kosong → plain kosong → `NoMatch` → **kandidat dibuang diam-diam**

Ini persis pola "di search tidak ada, dari refetch ada". Keberadaan `fetchRichSyncSeparately` di `getLyrics` adalah bukti jalur itu memang dibutuhkan di produksi.

**Ini juga celah uji MVP #1 yang sudah lama terbuka**: `fetchRichSyncSeparately` sampai `8765c5d` hanya punya assertion negatif (`assertFalse(...richsync.get)` di `MusixmatchSearchSelectionTest:338`), nol test positif.

**Perbaikan yang diusulkan:** beri `getAllLyrics` fallback yang sama, memakai ulang fungsi yang sudah ada. Uji positif yang harus ditambahkan:

1. macro-by-id: `track.richsync.get` inline **404**, matcher `has_richsync=1`, `track.subtitles.get` **404**, `track.lyrics.get` **404** → assert `track.richsync.get?commontrack_id=` dipanggil dan callback menerima **TTML**.
2. Sama tetapi `has_richsync=0` → assert endpoint terpisah **tidak** dipanggil, kandidat dilewati tanpa error.
3. Endpoint terpisah gagal → kandidat dilewati, kandidat lain tetap jalan (tidak menjadi kegagalan terminal).
4. Playback dan search memakai urutan format yang sama setelah fallback.

### 13.3 Asimetri lain yang tersisa, belum diuji

- **Search tidak mengirim `q_album`.** Refetch mengirimnya, sehingga matcher bisa membedakan rilis yang judulnya ambigu. Bila lagu ketiga punya judul umum, ini kandidat penyebab. Perlu diuji apakah `track.search` menerima `q_album` — **belum diverifikasi, jangan diasumsikan.**
- **`take(4)` setelah `distinctBy`.** Bila kandidat lolos gate lebih dari 4 dan pemenang refetch berada di peringkat >4, ia terpotong. Peringkat berbasis `track_rating` (§12.3) mengurangi risiko ini.
- **`page_size=5` menyajikan himpunan yang buruk** (§12.1) — sudah terbukti pada Blinding Lights, dan bisa membuat lagu lain nol kandidat.

### 13.4 Urutan kerja yang direvisi lagi

1. **`SEARCH_PAGE_SIZE` 5 → 20** — terbukti pada lagu yang dilaporkan (1→3).
2. **Fallback `fetchRichSyncSeparately` di `getAllLyrics`** (§13.2) — asimetri kode terverifikasi, sekaligus menutup celah uji MVP #1.
3. **Peringkat kandidat** berbasis penanda versi + `track_rating` (§12.3).
4. **Fallback search di `getLyrics`** (R2) untuk kasus macro-by-title 404 seperti アイドル.
5. Uji round-trip TTML (§10.1) dan dua baris log (§10.2) sebagai jaring pengaman.

**Yang masih dibutuhkan dari user:** link lagu ketiga yang benar. Tanpa itu, §13.2 adalah perbaikan yang benar terlepas dari lagunya, tetapi belum bisa dipastikan sebagai penyebab kasus yang dilaporkan.

---

## 14. ROOT CAUSE item ketiga — terpecahkan, terukur

Lagu yang benar: `eP1gxOuHLmA` = **Koiiro / Mosawo** (Mosawo - Topic). Gejala: **0 hasil Musixmatch di search**, tetapi refetch berhasil.

Tiga pemanggilan, token sama:

| jalur | query | hasil |
|---|---|---|
| **search** `track.search` | `q_track=Koiiro` · `q_artist=Mosawo` · `page_size=5` | **0 hasil, `available=0`** |
| **search** `track.search` | sama, `page_size=20` | **0 hasil, `available=0`** |
| **refetch** `macro.subtitles.get` matcher | `q_track=Koiiro` · `q_artist=Mosawo` | **200** → `track_id=250999168`, `sub=200`, `lyr=200` |
| **search** `track.search` | `q_track=Koiiro` · **`q_artist=もさを。`** | **1 hasil** → id sama `250999168` |

Track yang ditemukan matcher:

```
track_name  = "Koiiro"        <- judul disimpan dalam romaji
artist_name = "もさを。"        <- ARTIST disimpan dalam aksara Jepang
album_name  = "Koinouta"
track_length = 282 · has_subtitles = 1 · has_richsync = 0
```

**Penyebabnya: nama artist beda aksara.** YouTube Music memberi `Mosawo` (romaji); Musixmatch menyimpan `もさを。`.

- `track.search` menuntut kecocokan artist yang ketat → `Mosawo` tidak cocok `もさを。` → **nol hasil**. `available=0` membuktikan bukan gate kita yang membuang: query-nya sendiri tidak cocok apa pun.
- `matcher.track.get` melakukan pencocokan fuzzy/transliterasi → `Mosawo` → `もさを。` → **berhasil**.

Judulnya justru tidak masalah — "Koiiro" cocok begitu artist-nya benar.

### 14.1 Kedua endpoint punya titik buta yang saling melengkapi

| lagu | `track.search` | `matcher.track.get` | sebab |
|---|---|---|---|
| アイドル / YOASOBI | **berhasil** (1 track) | **404** | judul kana; artist Latin di kedua sisi |
| Koiiro / Mosawo | **0 hasil** | **berhasil** | artist beda aksara; judul romaji di kedua sisi |

Ini menjelaskan seluruh laporan user dengan satu mekanisme, dan **membatalkan kesimpulan §13.1** bahwa asimetri tidak dapat direproduksi — asimetrinya nyata, hanya lagunya yang tadi salah.

Dampaknya luas: setiap artist yang namanya di Musixmatch memakai aksara non-Latin sementara metadata pemutar memakai romaji/Latin — J-Pop, K-Pop, C-Pop, dan seterusnya. Ini bukan kasus tepi satu lagu.

### 14.2 Perbaikan: kedua jalur harus saling menjadi fallback

Sekarang jelas bahwa perbaikan yang benar bersifat **simetris**, dan keduanya memakai ulang kode yang sudah ada serta sudah diuji.

**A. `getAllLyrics` → fallback ke matcher** (baru; memperbaiki Koiiro)

Bila `track.search` menghasilkan **nol kandidat**, panggil `executeMacro(title, artist, album, durationSeconds, token)` — fungsi jalur playback yang sudah ada — lalu `validateMatch` → `resolveMacroLyrics` → emit lewat `callback`.

- Hanya di jalur kosong, jadi tidak menambah biaya untuk kasus normal.
- Tidak perlu endpoint baru, model baru, atau scoring baru.
- `validateMatch` tetap menjaga presisi (gate durasi + teks).
- Untuk Koiiro: `normalizeString("もさを。")` vs `normalizeString("Mosawo")` tidak cocok, tetapi `titleOk` benar ("koiiro" == "koiiro"), dan gate kita `!titleOk && !artistOk` hanya menolak bila **keduanya** gagal → **lolos**. Sudah diverifikasi terhadap kode.

**B. `getLyrics` → fallback ke search** (R2; memperbaiki アイドル)

Seperti §5 R2: bila macro-by-title gagal `NoMatch`/`Mismatch`, pakai `executeTrackSearch` → `executeMacroByTrackId` → `resolveMacroLyrics`.

**C. Opsional, nanti: pemulihan multi-kandidat.** Setelah matcher menemukan track, artist aslinya (`もさを。`) diketahui. `track.search` dapat diulang dengan artist itu untuk memulihkan kolam multi-kandidat. Untuk Koiiro tidak menambah apa pun (`available=1`), jadi tunda sampai ada kasus yang membutuhkannya.

### 14.3 Uji yang harus ada

Untuk A (`getAllLyrics`):
1. `track.search` → `track_list: []` → assert `macro.subtitles.get` dengan `q_track`/`q_artist` dipanggil, dan callback menerima lirik.
2. `track.search` kosong **dan** matcher 404 → tidak ada callback, tidak ada exception keluar.
3. `track.search` kosong, matcher 200, tetapi durasi selisih >8 s → `Mismatch`, tidak ada callback.
4. `track.search` **tidak** kosong → assert matcher by-title **tidak** dipanggil (tidak ada biaya tambahan di jalur normal).
5. `track.search` kosong, matcher 429 → kegagalan terminal, tidak ada retry.
6. Judul cocok tetapi artist beda aksara → **lolos** (mengunci perilaku `!titleOk && !artistOk`).

Untuk B: seperti §5 R2.

### 14.4 Risiko sekunder yang tercatat tapi bukan penyebab di sini

`PROVIDER_TIMEOUT_MS = 8_000` dipakai di **kedua** jalur (`LyricsHelper:227` untuk search, `:345` untuk playback). Tetapi dalam anggaran yang sama:

- **search** harus menyelesaikan token + `track.search` + hingga 4 macro (2 gelombang, concurrency 2)
- **refetch** hanya token + 1 macro

Jadi search mengerjakan kira-kira tiga kali lipat pekerjaan dalam batas waktu yang sama. Dengan token dingin ini bisa menghasilkan hasil sebagian atau nol. `FETCH_TIMEOUT_MS = 12_000` adalah anggaran keseluruhan playback, bukan per provider. **Ini bukan penyebab Koiiro** (di sana `available=0`, terjadi sebelum macro apa pun), tapi tetap risiko nyata untuk lagu dengan banyak kandidat.

### 14.5 Urutan kerja final

1. **`getAllLyrics` fallback ke matcher** (§14.2 A) — memperbaiki kasus yang dilaporkan,memakai ulang kode teruji.
2. **`SEARCH_PAGE_SIZE` 5 → 20** — terbukti Blinding Lights 1→3.
3. **`getLyrics` fallback ke search** (§14.2 B) — memperbaiki アイドル.
4. **Peringkat kandidat** (penanda versi + `track_rating`, §12.3).
5. **`fetchRichSyncSeparately` di `getAllLyrics`** (§13.2) — asimetri terpisah, tetap nyata.
6. Uji round-trip TTML (§10.1) + dua baris log (§10.2).

Nomor 1 dan 3 sebaiknya dikerjakan bersama sebagai satu perubahan "kedua jalur saling fallback", dengan uji 1–6 di atas. Itu satu paket koheren yang menutup kedua titik buta sekaligus.
