# Musixmatch multi-candidate search implementation plan

> **For agentic workers:** Gunakan `executing-plans` untuk menjalankan pekerjaan berurutan. Checkbox mencatat hasil yang sudah terbukti. User meminta plan; dokumen ini belum mengotorisasi implementasi, commit, atau push baru.

**Goal:** Search Lyrics mengirim lirik dari maksimal empat kandidat Musixmatch yang lolos filter, dengan maksimal dua pengambilan kandidat aktif dan callback segera setelah masing-masing berhasil.

**Architecture:** Pertahankan entry point publik `getLyrics()` dan `getAllLyrics()`. Pisahkan pemilihan track otomatis dari pencarian kandidat, lalu gunakan logika konversi format yang sama. App tetap menangani toggle, pencarian lintas provider, timeout, cache hasil, dan dedup.

**Tech Stack:** Kotlin/JVM 21, Ktor, kotlinx.serialization, kotlinx.coroutines, JUnit 4, Ktor MockEngine; GitHub Actions untuk build dan tests.

**Spec:** Percakapan desain yang disetujui user, `docs/musixmatch-pr/Lyrics-Search-Wiring.md`, dan `docs/musixmatch-pr/Review-Probe-Search.md` §8–9. Keputusan terbaru user mengganti cap lama menjadi **empat** kandidat dan concurrency **dua**. Bagian lama yang menyebut tiga/lima kandidat tidak mengesampingkan keputusan ini.

## Batas scope

- Module tetap JVM-only. Tidak menambah Android, DataStore, SharedPreferences, atau dependensi module lain.
- Tidak menjalankan Gradle lokal. Gunakan workflow GitHub Actions yang sudah ada.
- Jangan mencetak token, URL request lengkap, response body, isi lirik, atau pesan exception jaringan mentah ke log/diagnostik.
- `CancellationException` selalu diteruskan. Tidak membungkus suspending work dengan `runCatching` yang menelan cancellation.
- File Kotlin baru memakai GPL header ArchiveTune yang sama dengan file module sekarang.
- Tidak mengubah spec milik advisor sepihak. Catat keputusan dan bukti implementasi di implementer notes.
- Warm-up token dan persistence **di luar implementasi ini**. Keduanya tidak menjadi prasyarat multi-kandidat.
- Tidak memperbaiki atau memperluas script pengukuran advisor sebagai bagian fitur ini. Angka dari script itu bukan acceptance criterion.
- Tidak menambah UI, metadata versi ke callback, atau mengeluarkan beberapa format untuk track yang sama. Kontrak hasil tetap `String`.
- Sebelum mengubah kode pada tahap eksekusi, cek working tree kedua repo dan pertahankan perubahan user/advisor. Jangan otomatis membuat branch terpisah yang memutus alur integrasi `feat/musixmatch` yang sudah dipakai.

## Keputusan implementasi

### Daftar kandidat

Minta satu halaman `track.search` dengan `page_size=5`, `f_has_lyrics=1`, `q_track`, `q_artist`, `q_artists`, `app_id`, dan token. Lima hasil merupakan pool kecil agar filter masih punya pilihan; **maksimal empat** ID diproses. Tidak ada pagination atau pengisian ulang kandidat yang gagal.

Urutan pemilihan: ambil track non-null dengan ID positif, buang instrumental, buang selisih durasi lebih dari delapan detik jika input dan durasi track sama-sama positif, buang duplikat ID dengan mempertahankan urutan API, lalu `take(4)`. Input durasi `-1` atau durasi track yang tidak diketahui melewati filter durasi. Halaman kosong atau semua kandidat gugur menghasilkan nol callback dan selesai normal.

Input judul/artis di-trim. Jika salah satunya kosong, selesai tanpa request atau callback, mempertahankan perilaku search sekarang. `album` tetap ada di signature publik tetapi tidak digunakan untuk mempersempit pencarian versi atau macro berbasis ID.

### Fetch dan validasi

Macro kandidat menggunakan `track_id`, tanpa `q_track`, `q_artist`, `q_artists`, `q_album`, atau parameter durasi pemilihan otomatis. Parameter format macro lain mengikuti request produksi yang ada.

Sesudah macro: matcher harus berstatus 200 dan mengembalikan ID yang diminta. Periksa kembali instrumental dan durasi pada metadata matcher untuk mencegah perubahan metadata melewati filter awal. Restricted diperiksa sebelum resolusi format. Tidak menjalankan guard kemiripan judul/artis pada search.

Gunakan richsync yang layak dari macro, lalu subtitle, lalu plain. Body sub-call bisa objek, `[]`, null, atau tidak ada. Sub-call non-200 tidak boleh dipakai sebagai sumber lirik; metadata restricted yang berhasil didekode tetap ditolak sebelum pemilihan format. Satu sub-call gagal tidak menggugurkan format lain yang valid.

**Batas request:** jalur normal memakai satu search dan maksimal empat macro, di luar token. Setiap request yang menerima 401 boleh diulang maksimal sekali; beberapa macro yang serentak ditolak bisa masing-masing retry setelah **satu renewal bersama**. Jadi satu renewal tidak berarti hanya satu request retry untuk seluruh operasi. Tidak menambah request richsync terpisah di jalur search. Jalur playback tetap mempertahankan fallback richsync terpisah yang sudah ada. Keduanya berbagi konverter; sumber richsync tambahan tetap menjadi tanggung jawab jalur playback.

### Concurrency, callback, dan cancellation

Gunakan `coroutineScope`, coroutine per kandidat terpilih, dan `Semaphore(2).withPermit`. Permit mencakup seluruh pengambilan dan resolusi kandidat termasuk retry, sehingga maksimal dua request kandidat aktif. Lepaskan permit sebelum menunggu mutex callback. Jangan `awaitAll()` hasil sebelum callback.

Setiap kandidat yang berhasil langsung mengirim satu hasil. Callback diserialkan memakai mutex lokal agar pemanggil tidak harus menyediakan koleksi thread-safe. Urutan hasil mengikuti penyelesaian, bukan urutan API. Penantian callback tidak menghalangi konversi kandidat lain.

Catch kegagalan pengambilan/resolusi kandidat sebelum callback. Jangan menangkap exception callback sebagai kegagalan API dan melanjutkan diam-diam. Periksa `ensureActive()` sebelum request, setelah pemrosesan format, dan sebelum callback. Parent cancellation membatalkan request aktif serta kandidat yang menunggu permit. App tetap menjadi pemilik timeout delapan detik; module tidak menambah deadline terpisah.

### Token dan kegagalan

Gunakan `getToken()` dan `renewToken(previous)` yang ada. Tambahkan koordinasi privat berumur satu pemanggilan `getAllLyrics()` untuk menyimpan token operasi, mutex renewal, dan apakah kesempatan renewal sudah dipakai. State ini tidak global.

Kebijakan berikut berlaku untuk search dan macro dalam operasi yang sama:

1. Request membaca snapshot token operasi dan berjalan di luar mutex renewal.
2. Pada HTTP/payload 401, masuk mutex. Jika token operasi sudah berbeda dari token yang ditolak, gunakan token terbaru tanpa mengambil token lagi.
3. Jika token belum berubah dan jatah belum dipakai, tandai jatah terpakai **sebelum** memanggil `renewToken(previous)`, kemudian simpan hasilnya. Jika renewal gagal, simpan kegagalan dan hentikan operasi; waiter tidak mencoba renewal baru.
4. Retry request tersebut sekali dengan token terbaru. Tidak ada loop retry.
5. Jika token baru juga ditolak, hentikan operasi. Secara keseluruhan maksimal satu pemanggilan renewal dari search ini, termasuk apabila `track.search` yang lebih dahulu membutuhkan renewal.

`renewToken(previous)` tetap bisa memakai cache yang diperbarui operasi lain tanpa request token tambahan. Jangan menahan mutex renewal selama macro/search normal atau callback.

Penanganan kegagalan:

| Keadaan | Perilaku |
|---|---|
| Kandidat restricted, mismatch, tidak punya format, malformed, 5xx, atau network failure | Catat pesan tetap tanpa data sensitif, lewati kandidat, lanjutkan yang lain |
| Search awal/token gagal | Tidak ada fan-out; teruskan error dengan pesan yang disanitasi |
| 429 HTTP/payload | Hentikan operasi dan batalkan sibling; tidak retry atau renew |
| Renewal gagal atau token baru tetap 401 | Hentikan operasi dan batalkan sibling |
| Cancellation parent/timeout | Teruskan cancellation; tidak ada callback lanjutan |
| Callback melempar | Teruskan exception; hentikan sibling |

Hasil yang sudah dikirim tidak ditarik ketika operasi berhenti. Jika seluruh kandidat gagal secara individual, selesai dengan nol hasil dan log tetap. Sanitasi exception jaringan pada batas operasi sebelum diteruskan ke app yang mungkin melaporkannya. Tidak mengubah klasifikasi 401 milik playback/token lama dalam pekerjaan ini.

## Peta file

Path module relatif terhadap root repo `lyrics`:

| File | Tanggung jawab perubahan |
|---|---|
| `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/Musixmatch.kt` | Request search/macro ID, koordinasi token operasi, filter, fan-out, dan ekstraksi resolver format bersama |
| `musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/models/SearchModels.kt` | File baru untuk `SearchBody` dan pembungkus track search; gunakan `Track` yang sudah ada |
| `musixmatch/src/test/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchApiTest.kt` | Regresi playback dan fixture macro dengan ID/status yang dapat diatur |
| `musixmatch/src/test/kotlin/moe/rukamori/archivetune/musixmatch/MusixmatchSearchTest.kt` | File baru untuk acceptance perilaku multi-kandidat |
| `docs/musixmatch-pr/implementer-notes.md` | Perilaku akhir, keputusan empat kandidat, bukti CI dan smoke app |

Tidak perlu mengubah `MusixmatchTtml.kt`, model `Track`, atau signature publik. Hindari memecah helper generik ke file baru jika hanya dipakai object ini.

Repo app terpisah: `/home/neikami/gitclone/Archivetune/Archivetune`. Implementasi wrapper `MusixmatchLyricsProvider.getAllLyrics()` sudah meneruskan callback. `LyricsHelper` sudah memiliki channel, dedup, cache dua menit, dan timeout delapan detik. Perubahan app yang diperlukan hanya gitlink submodule setelah commit module tersedia.

## Task 1: Pisahkan resolusi format dari pemilihan otomatis

**Files:** `Musixmatch.kt`, `MusixmatchApiTest.kt`.

**Interfaces:** Public API tetap. Resolver privat menerima macro calls dan opsional richsync tambahan yang sudah diperoleh playback; mengembalikan `String` atau melempar error module ketika tidak ada format. Resolver tidak memilih track atau meminta token.

- [ ] Tambahkan kasus perilaku sub-call non-200 yang memiliki body teks tetapi tidak boleh dipakai; pastikan fallback masih memakai sub-call lain yang valid. Pertahankan regresi restricted, instrumental marker, matcher teks/durasi, dan empty TTML fallback.
- [ ] Ekstrak konversi richsync, subtitle, dan plain dari `getLyrics()` ke resolver privat. Playback tetap melakukan validasi otomatis dan pengambilan richsync terpisah sebelum memakai resolver. Search kelak hanya memasok format dari macro.
- [ ] Pastikan restricted tetap diperiksa sebelum konversi atau request tambahan. Cancellation dari fallback suspending tidak tertangkap sebagai kegagalan format.
- [ ] Saat push implementasi diotorisasi, jalankan tests melalui Actions, simpan kesimpulan hasil task bersama feature batch. Tidak menjalankan Gradle di laptop.

## Task 2: Implementasikan kandidat dan operasi search

**Files:** `SearchModels.kt`, `Musixmatch.kt`, `MusixmatchSearchTest.kt`; perluasan kecil fixture yang sudah ada jika diperlukan, tanpa membuat framework test umum.

**Interfaces:**

```kotlin
@Serializable
internal data class SearchBody(
    @SerialName("track_list") val trackList: List<SearchTrackItem> = emptyList(),
)

@Serializable
internal data class SearchTrackItem(val track: Track? = null)
```

Kontrak publik tetap:

```kotlin
suspend fun getAllLyrics(
    title: String,
    artist: String,
    album: String? = null,
    durationSeconds: Int = -1,
    callback: (String) -> Unit,
)
```

- [ ] Tambahkan MockEngine cases dalam matriks acceptance di bawah. Gunakan respons JSON lokal dengan track ID dan isi lirik berbeda agar callback dapat dikenali.
- [ ] Implementasikan request `track.search` dengan pemeriksaan status HTTP dan envelope API seperti request macro. Keduanya dapat menghasilkan `TokenInvalid` pada 401 dan `RateLimited` pada 429 tanpa mencetak body.
- [ ] Pertahankan entry request playback. Tambahkan entry request macro ID; satukan parameter format dan decoding respons pada helper privat agar tidak ada dua implementasi status/format yang menyimpang. Jangan memakai flag untuk menonaktifkan validasi playback.
- [ ] Implementasikan state renewal lokal sesuai kebijakan di atas. Gunakan mutex operasi yang berbeda dari `tokenMutex` global dan hindari penguncian rekursif.
- [ ] Filter/dedup ID sebelum `take(4)`. Buat maksimal empat pekerjaan dengan semaphore dua. Request dan konversi dapat berjalan paralel, callback langsung dan serial.
- [ ] Pisahkan catch kegagalan kandidat dari invocation callback. Fatal auth/rate-limit dan cancellation membatalkan scope. Error kandidat lain tidak membatalkan sibling.

### Acceptance tests yang wajib

| Kasus | Bukti perilaku |
|---|---|
| Pool berisi lebih dari empat kandidat layak | Maksimal empat ID unik di-fetch, maksimal empat callback, satu search |
| Kandidat awal instrumental/durasi salah/ID invalid/duplikat | Tidak di-fetch, kandidat layak setelahnya dalam halaman tetap dapat dipilih |
| Selisih delapan dan sembilan detik; input `-1`; durasi kandidat tidak diketahui | Batas delapan lolos, sembilan gugur, durasi tidak diketahui dilewati |
| Judul dan artis matcher berbeda tetapi ID benar | Search tetap dapat emit; playback tetap menolak mismatch teks lewat suite lamanya |
| Matcher non-200/ID salah; metadata matcher berubah jadi instrumental/durasi salah | Tidak ada callback untuk kandidat itu |
| Restricted dengan richsync valid | Tidak ada hasil atau fallback request tambahan |
| Richsync rusak/kosong, subtitle valid; keduanya gagal, plain valid | Menghasilkan LRC atau plain melalui konverter produksi, bukan hasil tiruan resolver |
| Body sub-call `[]`, null, atau hilang | Tidak merusak kandidat lain dan tidak memakai body gagal sebagai format valid |
| Dua macro pertama ditahan barrier; kandidat ketiga menunggu | Dua request benar-benar overlap, request ketiga baru masuk setelah satu permit bebas, peak aktif tidak melebihi dua |
| Satu macro dilepas, yang lain masih tertahan | Callback pertama terjadi sebelum operasi selesai; hasilnya sesuai format produksi |
| Satu kandidat 5xx/malformed/network failure | Kandidat lain tetap emit |
| Parent dibatalkan setelah callback pertama | Request aktif dibatalkan, waiter tidak memulai request, tidak ada callback berikutnya |
| Callback melempar | Error callback keluar dan sibling berhenti, bukan dicatat sebagai kegagalan kandidat |
| Search 401 lalu sukses | Satu renewal dan satu retry; kandidat memakai token baru |
| Dua macro serentak 401 pada token lama | Satu renewal total; masing-masing maksimal satu retry dan menggunakan token baru |
| Search sudah renew, macro kemudian 401 pada token baru | Tidak ada renewal kedua; operasi berhenti |
| Renewal gagal atau retry tetap 401 | Tidak ada loop token/retry; sibling berhenti |
| 429 HTTP dan payload saat kandidat lain tertahan | Tidak renew/retry, sibling dibatalkan, callback terdahulu tetap tercatat |
| Input kosong, hasil search kosong, semua kandidat gugur | Selesai tanpa callback dan tanpa request kandidat |
| Log/error kandidat dengan secret sentinel pada respons/exception jaringan | Token, URL, body, lirik, dan pesan jaringan mentah tidak bocor |

Untuk overlap dan cancellation, gunakan `CompletableDeferred`/channel sebagai barrier dan `AtomicInteger` untuk peak aktif. Jangan membuktikan concurrency hanya melalui `delay` dan elapsed time. Beri batas waktu pada barrier di test agar kegagalan tidak menggantung CI. Reset singleton client/token/logger setelah setiap test. Tidak perlu dependensi testing baru.

## Task 3: Verifikasi remote dan smoke di app

**Files:** notes module; gitlink `lyrics` di repo app.

- [ ] Setelah implementasi siap dan commit/push diotorisasi, gunakan workflow `.github/workflows/test_build_push.yml`, yang sekarang menjalankan `gradle --console=plain build test --warning-mode summary` pada JDK 21. Periksa hasil JUnit task Musixmatch, bukan hanya kesuksesan workflow umum. Jika butuh failing-before proof, gunakan commit test-only di branch fitur melalui Actions lalu implementasi; jangan menjalankan Gradle lokal.
- [ ] Perbaiki kegagalan yang terkait scope, lalu jalankan ulang CI hanya jika ada perubahan atau kegagalan yang belum selesai. Jangan mengandalkan script Python/JavaScript sebagai bukti konverter Kotlin.
- [ ] Setelah commit module tersedia, update checkout submodule app ke commit tersebut dan gitlink app. Pertahankan pengaturan remote/branch yang sudah dipakai, jangan reset perubahan user. Build APK melalui CI app/PR fork yang sudah ada, bukan membuat PR upstream otomatis.
- [ ] Jalankan app hasil CI pada perangkat yang tersedia. Jika perangkat hanya dapat diakses user, berikan APK dan langkah konkret, catat bahwa bukti perangkat masih menunggu user; jangan mengklaim smoke selesai.
- [ ] Smoke Search Lyrics dengan Musixmatch aktif dan provider lain dimatikan sementara agar asal hasil jelas. Pakai durasi tidak diketahui bila UI mendukungnya; jika tidak, gunakan track dengan beberapa versi dalam toleransi. Pastikan hasil muncul bertahap dan dapat dipilih/ditampilkan.
- [ ] Ulangi pencarian menggunakan force refresh yang sudah ada atau setelah cache dua menit berakhir. Jangan menambah UI bypass cache. Verifikasi toggle off menghilangkan partisipasi Musixmatch dan playback biasa tetap memakai jalur satu match.
- [ ] Catat jumlah hasil aktual, format yang dirender, perilaku pemilihan, link CI/commit/APK, dan batas verifikasi di implementer notes. Tidak menjanjikan selalu empat hasil atau target latency tertentu. Tidak perlu mengubah advisor docs.

## Kriteria selesai

- `getAllLyrics()` menggunakan search kandidat sungguhan, maksimal empat ID dan dua fetch aktif.
- Filter sebelum/sesudah fetch, fallback format, incremental callback, partial failure, fatal rate-limit/auth, serta cancellation terbukti lewat test perilaku.
- `getLyrics()` tetap menjalankan pemilihan otomatis dan regresi playback lulus.
- CI module dan build app lulus pada commit yang terhubung lewat gitlink.
- Smoke app membuktikan hasil dapat muncul dan dipilih, atau keterbatasan akses perangkat dicatat sebagai verifikasi yang belum terpenuhi.
- Notes sesuai bukti aktual. Tidak ada warm-up, persistence, perubahan UI, atau pekerjaan script pengukuran yang terselip.
