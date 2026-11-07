# Implementer Notes: Musixmatch Provider Smoke Test & Payload Findings

Dokumen ini mencatat temuan teknis riil hasil *smoke test* langsung ke endpoint Musixmatch (`apic-appmobile.musixmatch.com`) pada 5 November 2026 sebelum implementasi Phase 1–5 dimulai.

---

## 1. Verifikasi Endpoint & Autentikasi

* **`token.get`**:
  * Request dengan `app_id=mac-ios-v2.0` dan header `X-User-Agent: Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0` berhasil mengembalikan HTTP 200 dengan `status_code: 200`.
  * `user_token` berhasil didapatkan (panjang 54 karakter alfanumerik).
* **`macro.subtitles.get`**:
  * Parameter `optional_calls=track.richsync` dan `richsync_compact_type=words` berhasil mengembalikan richsync inline dalam satu network call.
  * Sub-call yang dikembalikan mencakup: `matcher.track.get`, `track.richsync.get`, `track.subtitles.get`, `track.lyrics.get`, `track.snippet.get`, dan `userblob.get`.

---

## 2. Temuan Kritis Payload & Implikasi Serialisasi Kotlin

### A. Flag Boolean Berupa Integer `0` dan `1` (Bukan `true`/`false`)
* **Fakta:** Di API Musixmatch, seluruh field status/boolean pada track dan lyrics dikembalikan sebagai integer:
  * `instrumental`: `0` atau `1`
  * `has_richsync`: `0` atau `1`
  * `has_subtitles`: `0` atau `1`
  * `has_lyrics`: `0` atau `1`
  * `restricted`: `0` atau `1`
* **Risiko:** Jika dideklarasikan sebagai `val instrumental: Boolean = false` pada Kotlinx Serialization, akan terjadi `SerializationException: Expected boolean, found 1`.
* **Solusi Implementasi:**
  Definisikan tipe field sebagai `Int = 0` di data class model, lalu sediakan extension/computed property boolean untuk kenyamanan kode:
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
      @SerialName("instrumental") val instrumental: Int = 0,
  ) {
      val isInstrumental: Boolean get() = instrumental == 1
      val isRichsyncAvailable: Boolean get() = hasRichsync == 1
      val isSubtitlesAvailable: Boolean get() = hasSubtitles == 1
  }
  ```

### B. Polimorfisme `body` pada Macro Sub-Calls (`[]` vs `{}` vs `null`)
* **Fakta:** Saat suatu macro call tidak memiliki data (misalnya `track.subtitles.get` ketika lirik sync tidak tersedia, atau `userblob.get`), Musixmatch mengembalikan `body: []` (array JSON kosong), bukan `{}` (object) atau `null`.
* **Risiko:** `MacroCall<T>` generik yang mengekspektasikan `body: T` bertipe object akan gagal (*fail to parse*) jika menerima JSON array `[]`.
* **Solusi Implementasi:**
  Tangani `body` sebagai `JsonElement?` atau gunakan custom deserializer / model terpisah yang toleran terhadap array kosong saat sub-call 404 / kosong.

---

## 3. Verifikasi Logika Bisnis

1. **Richsync → TTML:**
   * Diuji pada lagu *Levitating - Dua Lipa* (74 baris).
   * Spasi yang dipancarkan sebagai text node (bukan `<span>`) menghasilkan format XML yang valid dan dipastikan kompatibel dengan `TTMLParser.appendInterSpanText()`.
   * Perhitungan durasi kata terakhir (`te - (ts + offset_terakhir)`) menghasilkan angka presisi tanpa overlapping.
2. **Instrumental Guard:**
   * Diuji pada lagu *YYZ - Rush* dan *Cliffs of Dover - Eric Johnson*.
   * Mengembalikan `instrumental = 1`. Guard langsung menghasilkan `"♪ Instrumental ♪"` tanpa memanggil richsync.
3. **Matcher Guard:**
   * Durasi beda > 8 detik tertolak sebagai `MusixmatchError.Mismatch`.
   * Title & artist beda jauh tertolak sebagai `MusixmatchError.Mismatch`.
   * Lagu tidak ada di Musixmatch menghasilkan `matcher.track.get` status `404` → `MusixmatchError.NoMatch`.

## 4. Probe search otomatis, 6 Nov 2026

Script [probe-search.mjs](./probe-search.mjs) menjalankan token → `track.search` → fetch macro tiap kandidat memakai `track_id`. Satu token dipakai ulang untuk seluruh request. Script membatasi lima kandidat dan dua fetch paralel, dengan deadline total 8 detik termasuk pengambilan token.

Perintah dari root repo:

```sh
bun docs/musixmatch-pr/probe-search.mjs
```

Run live untuk Levitating, Dua Lipa, tanpa filter durasi berhasil mengambil kelima kandidat dalam 6.357 ms, dengan hasil pertama pada 4.746 ms. Pengambilan token memakan 3.451 ms dan search 770 ms. Semua matcher cocok dengan ID yang diminta. Empat kandidat punya richsync; Live From Mexico punya subtitle meskipun sub-call richsync `404`. Script keluar dengan kode `0` dan `allEligibleSucceeded=true`.

Ini mengonfirmasi kelayakan API multi-kandidat pada satu sampel, bukan jaminan latency atau pengujian integrasi Kotlin/app. Script belum menguji konversi TTML/LRC produksi, callback UI, atau pembatalan saat deadline terlewati. Dengan input durasi `204`, filter akan membuang versi live/remix yang jauh lebih panjang; banyak kandidat search tidak selalu berarti banyak hasil yang layak ditampilkan. Token, URL lengkap, response body, dan lirik tidak ditulis ke output/file.

Koreksi catatan sebelumnya: penolakan `token.get` pada probe terdahulu terjadi saat meminta token baru antar-probe, sehingga tidak membuktikan token sebelumnya tidak bisa dipakai lagi. Penyebab pasti payload `401` tersebut tidak terverifikasi.

## 5. Implementasi multi-kandidat Search Lyrics (sebelum hybrid)

`getAllLyrics()` sekarang memakai `track.search` dengan pool lima track, lalu memfilter ID tidak valid/duplikat, instrumental, dan selisih durasi lebih dari delapan detik jika durasi diketahui. Maksimal empat kandidat diproses dengan dua pekerjaan kandidat aktif. Macro menggunakan `track_id`; metadata matcher diperiksa kembali tanpa guard kemiripan teks playback.

Hasil dikirim melalui callback segera setelah tiap kandidat selesai. Callback diserialkan; channel, dedup, cache hasil dua menit, dan timeout delapan detik tetap milik app. Format dipilih melalui konverter produksi bersama: richsync menjadi TTML, subtitle menjadi LRC, lalu plain. Search tidak menambah request richsync terpisah. Warm-up dan persistence belum ditambahkan.

Renewal token dibatasi sekali per operasi search. Request yang menerima 401 boleh retry sekali menggunakan token baru bersama. Renewal gagal, token baru ditolak, dan 429 menghentikan operasi; kegagalan kandidat biasa tidak menghapus hasil kandidat lain. Exception transport search disanitasi sebelum keluar ke app. Cancellation dari parent maupun callback menghentikan seluruh pencarian.

Review independen menemukan regresi early-exit playback, batas permit yang terlalu pendek, dan kegagalan renewal yang sempat diperlakukan sebagai kegagalan kandidat. Regresi playback dibuktikan gagal pada [run 42235279927](https://github.com/adam-adrian/lyrics/actions/runs/42235279927); kegagalan renewal dengan sibling tertahan dibuktikan pada [run 42236050352](https://github.com/adam-adrian/lyrics/actions/runs/42236050352). Setelah perbaikan, [run 42236664625](https://github.com/adam-adrian/lyrics/actions/runs/42236664625) lulus. Review juga menemukan callback `CancellationException` yang hanya membatalkan satu child; regression test gagal pada [run 42236955505](https://github.com/adam-adrian/lyrics/actions/runs/42236955505) sebelum perbaikannya.

Build app awal dengan gitlink `b1697f4` lulus pada [run 42232663132](https://github.com/adam-adrian/ArchiveTune/actions/runs/42232663132). Itu belum membuktikan koreksi setelah review masuk APK. Verifikasi final dicatat setelah CI terbaru selesai.

Tidak ada perangkat Android terhubung menurut `adb devices -l`, sehingga tampilan hasil, pemilihan kandidat, dan render di app belum diverifikasi untuk fitur multi-kandidat ini. CI dan smoke JVM tidak menggantikan pemeriksaan permukaan app tersebut.

Verifikasi produksi pada [run 42237306045](https://github.com/adam-adrian/lyrics/actions/runs/42237306045), commit `741bc0a`, berhasil melalui implementasi Kotlin sebenarnya dan API live. Empat callback diterima: TTML pada 1.730 ms, TTML pada 1.805 ms, LRC pada 1.841 ms, dan TTML pada 1.991 ms sejak mulai operasi. TTML diparse sebagai XML dalam smoke sementara; tidak ada token, URL, atau teks lirik dicetak. Ini satu sampel runner GitHub, bukan janji latency perangkat. Smoke jaringan dan pengaturan stdout sementara dihapus setelah bukti diperoleh agar suite permanen tetap offline/deterministik.

Reviewer error-handling memeriksa ulang koreksi cancellation dan menyatakan tidak ada temuan penting tersisa pada bagian tersebut. Ini review source, terpisah dari bukti runtime CI.

Verifikasi final setelah smoke sementara dihapus: [CI module 42237676322](https://github.com/adam-adrian/lyrics/actions/runs/42237676322) lulus pada `fd866c0`. Repo app menunjuk module tersebut melalui gitlink commit `c76edf420`; [CI app 42237722810](https://github.com/adam-adrian/ArchiveTune/actions/runs/42237722810) berhasil membangun Mobile Arm64 Debug APK dan menyelesaikan pengiriman melalui MTProto. Build dapat dikenali melalui commit app `c76edf420`. Perubahan notes setelah itu hanya dokumentasi dan tidak memerlukan gitlink kode baru.

Smoke perangkat yang masih perlu dilakukan: install APK tersebut, aktifkan hanya Musixmatch sementara untuk membedakan sumber hasil, jalankan Search Lyrics pada lagu yang memiliki beberapa versi dalam toleransi durasi, lalu pilih hasil yang muncul dan periksa render/sinkronisasi. Ulangi dengan force refresh atau setelah cache dua menit habis. Periksa toggle off dan playback biasa. Hasil boleh kurang dari empat karena filter, error, atau dedup; empat adalah batas kandidat, bukan janji jumlah hasil UI.

## 6. Implementasi hybrid Musixmatch, 7 Nov 2026

Commit kode: `143184b30738174019c8dc2d68654afd6ce4bfdb` pada branch `feat/musixmatch`. CI module [42314679553](https://github.com/adam-adrian/lyrics/actions/runs/42314679553) berhasil menjalankan `gradle --console=plain build test --warning-mode summary`, termasuk `:musixmatch:test` dan test converter/parser TTML setelah import package diperbaiki. Suite source berisi 105 deklarasi `@Test`; log CI tidak menerbitkan laporan jumlah test saat berhasil.

Search meminta pool 20, mempertahankan gate durasi delapan detik, lalu dedup, stable ranking berdasarkan demerit versi dan rating, dan cap empat kandidat dengan concurrency dua. Seleksi kosong, termasuk pool yang seluruhnya terfilter, mencoba matcher by-title. Playback hanya fallback setelah `NoMatch`/`Mismatch`, mencoba maksimal dua kandidat berurutan.

RichSync terpisah tersedia pada search dan playback melalui resolver bersama; prioritas tetap RichSync, subtitle, lalu plain. Satu budget renewal token mencakup seluruh operasi dan transisi endpoint. Cancellation, 429, dan kegagalan renewal tetap terminal. Log funnel hanya memuat jumlah returned, eligible, dan candidates.

Verifikasi ini membuktikan regression suite offline dengan MockEngine dan eksekusi converter/parser sebenarnya, bukan smoke API live atau render perangkat. Smoke live dan APK pada bagian 5 mendahului hybrid; gitlink dan APK app belum diperbarui untuk commit hybrid. Tidak ada subagent atau Gradle lokal yang digunakan dalam implementasi hybrid.

### Bukti regression-first

| Tahap | CI sebelum implementasi | Perbaikan dan CI sesudahnya |
|---|---|---|
| Ekstraksi mekanis | `210ba02`: import `SearchBody` hilang | `b51cb2f`, run `42310694745` lulus; baseline 68 test |
| Fallback dua arah | `18b9dfe`, run `42311004934`: 84 test, 14 gagal | `9616ec8`, run `42311373116` lulus |
| Separate RichSync search | `9c050b6`, run `42312233356`: 93 test, 6 gagal | `da6ed94`, run `42312480753` lulus |
| Pool 20 | `d87ec52`, run `42313340861`: 94 test, 1 gagal | `bca963f`; test non-ranking lulus pada run `42313578177`, seluruh suite kemudian lulus pada `42313932757` |
| Stable ranking | `de64e59`, run `42313578177`: 104 test, 6 gagal, seluruhnya ranking | `4635fa5`, run `42313932757` lulus |
| Round-trip dan log funnel | `a255f51`, run `42314361257`: compile test gagal karena package parser salah | Import diperbaiki pada `143184b`; run `42314679553` lulus |

`TTMLParser.kt` masih berada di direktori lama `moe/koiverse/rukamori`, tetapi deklarasi package sebenarnya `moe.rukamori.archivetune.betterlyrics`. Test mengikuti deklarasi tersebut; dependency `:betterlyrics` hanya `testImplementation`.

### Smoke JVM hybrid

Runner sementara pada `6eca335091c5d6a61178dc5da94df7bd24a3cbf6` berhasil di CI [42317352849](https://github.com/adam-adrian/lyrics/actions/runs/42317352849). Entry point JVM terpisah menjalankan provider produksi dengan transport MockEngine, kemudian memparse TTML melalui parser produksi.

| Jalur | Request | Renewal | Hasil |
|---|---:|---:|---|
| Search kosong → title matcher → separate RichSync → parser | 3 | 0 | PASS; satu baris TTML, teks dan timing terjaga |
| Playback 401 → renewal → matcher 404 → ranked search → kandidat pertama gagal → kandidat kedua → separate RichSync → parser | 7 | 1 | PASS; satu baris TTML, teks dan timing terjaga |

Smoke juga memeriksa urutan endpoint, ranking sebelum cap dua kandidat playback, dan log funnel search kosong. Output hanya memuat hitungan dan status; tidak mencetak token, URL, atau isi lirik. Runner dan wiring Gradle sementara dihapus setelah bukti diperoleh. Ini smoke offline, bukan pengukuran API live atau perangkat.

### Verifikasi final setelah cleanup

Commit `f86fbc77d9cfd7cfc825b1dea556551e080752f0` menghapus runner sementara tanpa mengubah kode hybrid. CI [42317619028](https://github.com/adam-adrian/lyrics/actions/runs/42317619028) lulus setelah cleanup. Regression suite permanen dan test round-trip tetap dipertahankan.

Pemeriksaan source: tidak ada import Android atau perubahan version catalog; dependency tambahan hanya untuk test; seluruh file Kotlin baru memiliki header GPL ArchiveTune. `Musixmatch.kt` berisi 461 baris dan helper produksi lainnya di bawah 500 baris. Integrasi gitlink app, APK hybrid, API live hybrid, dan render perangkat tidak dikerjakan dalam paket ini.

### Koreksi dependency saat integrasi app

CI app `42319573238` gagal saat konfigurasi Gradle: test dependency absolut `:betterlyrics` tidak tersedia karena app memasang modul sebagai `:lyrics:betterlyrics`. Dependency test Musixmatch sekarang mengikuti parent project path, sehingga standalone memakai `:betterlyrics` dan integrasi app memakai `:lyrics:betterlyrics`. Dependency tetap khusus test; kode provider tidak berubah. Verifikasi build berikutnya dilakukan melalui CI GitHub, tanpa Gradle lokal.
