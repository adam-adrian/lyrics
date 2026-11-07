# Review Probe Search — `f3677bb`

**Commit:** `docs(musixmatch): add search wiring analysis and automated API probe`
**Isi:** `probe-search.mjs` (149 baris) + temuan di `implementer-notes.md` §4
**CI:** success
**Tanggal:** 6 Nov 2026

---

## 1. Pertanyaan terbuka terjawab

**`macro.subtitles.get` menerima `track_id` sebagai pengganti `q_track`/`q_artist`.** Terkonfirmasi live: kelima kandidat menghasilkan `matcher.track.get` dengan `track_id` yang **sama dengan yang diminta**.

Ini membuka jalan multi-kandidat tanpa perlu tiga endpoint terpisah per kandidat. Anggaran request tetap **1 search + N macro**, bukan **1 + 3N**.

Probe juga memvalidasi ulang semantik yang sudah kita pakai: flag `instrumental` sebagai integer, `richsync_body` sebagai JSON string, dan sub-call yang bisa `404` sementara sub-call lain `200` (kasus Live From Mexico: richsync `404`, subtitle tersedia).

---

## 2. Temuan paling penting: token memakan 43% anggaran

Angka dari run live (Levitating / Dua Lipa, tanpa filter durasi):

| Tahap | Waktu | Porsi anggaran 8.000 ms |
|---|---|---|
| `token.get` | **3.451 ms** | **43%** |
| `track.search` | 770 ms | 10% |
| **Overhead tetap** | **4.221 ms** | **53%** |
| 5 kandidat (concurrency 2) | 2.136 ms | 27% |
| **Total** | **6.357 ms** | 80% |
| Hasil pertama | 4.746 ms | — |
| Sisa margin | 1.643 ms | 20% |

**Yang jadi bottleneck bukan fetch kandidat, tapi overhead tetap.** Lima kandidat hanya butuh 2,1 detik; token sendirian butuh 3,5 detik.

### Kalau token sudah hangat

| | Dingin | Hangat |
|---|---|---|
| Hasil pertama | 4.746 ms | **~1.295 ms** |
| Total | 6.357 ms | **~2.906 ms** |

**3,7× lebih cepat ke hasil pertama.** Ini perbaikan tunggal terbesar yang tersedia.

### Kenapa kasus dingin adalah kasus UMUM, bukan pinggiran

Ini yang membuat masalahnya lebih serius dari sekadar "kadang lambat":

1. Token hanya di-cache **di memori**, hilang setiap app ditutup
2. Bagi user lama, `MUSIXMATCH` berada di **ujung** urutan provider
3. Saat pemutaran biasa, provider dicoba berurutan dan **berhenti di yang pertama sukses**
4. Jadi Musixmatch sering **tidak pernah dipanggil** saat pemutaran → token tidak pernah terambil
5. Ketika user membuka pencarian manual, token hampir pasti **dingin** → bayar 3,5 detik

Dua keputusan desain yang masing-masing masuk akal, tapi bergabung menghasilkan penalti 3,5 detik tepat di saat user sedang menunggu.

### Jaringan mobile memperburuknya

Probe dijalankan dari satu mesin dengan satu kondisi jaringan. Kalau latensi 2× lebih lambat — wajar untuk data seluler:

```
6.357 ms × 2 = 12.714 ms  →  MELEWATI anggaran 8.000 ms
```

Dengan emisi inkremental, hasil yang sudah masuk tetap tampil. Tapi kalau token+search saja sudah melewati 8 detik, **nol hasil** yang tampil. Itu kegagalan yang terlihat user.

---

## 3. Rekomendasi, berurut berdasarkan nilai

### (a) Hangatkan token di luar jalur kritis — nilai tertinggi

Jangan ambil token saat user menekan search. Ambil lebih awal, saat tidak ada yang menunggu:

- saat provider di-enable di Settings, atau
- saat layar lirik dibuka, atau
- sekali di latar belakang saat app start (kalau provider aktif)

Ini tidak butuh persistence dan tidak melanggar JVM-only. Cukup satu fungsi `suspend fun warmUp()` di module yang dipanggil wrapper pada momen yang tepat, dibungkus `runCatching` supaya kegagalannya tidak mengganggu apa pun.

### (b) Persistence token via hook — nilai berikutnya

Sudah dirancang di spec §8 sebagai `tokenProvider`/`tokenSink`. App layer memegang DataStore, module tetap JVM-only. Menghilangkan penalti token dingin bahkan setelah app restart.

Lebih banyak kerja daripada (a), tapi menyelesaikan akar masalah. (a) dan (b) saling melengkapi.

### (c) Turunkan kandidat dari 5 ke 3

Menghemat ~0,7 detik dan mengurangi tekanan rate limit. Untuk pencarian manual, 3 kandidat ditambah hasil provider lain biasanya sudah cukup. Bisa dibuat 5 kalau token sudah hangat.

### (d) Jangan naikkan concurrency dulu

Probe pakai 2 dan berhasil. Menaikkan ke 3 menghemat ~0,7 detik tapi menambah risiko rate limit Musixmatch — yang justru menyebabkan kegagalan total. Tunda sampai (a) selesai; setelah overhead hilang, margin sudah lega.

---

## 4. Catatan desain untuk mode search

### Guard teks JANGAN diterapkan di mode search

`validateMatch()` saat ini menolak bila judul **dan** artis beda jauh. Di mode search itu **salah** — user justru ingin melihat versi lain (live, remix, akustik). Probe dengan tepat tidak menerapkan guard teks.

Yang tetap diterapkan di mode search:

- filter durasi (toleransi 8 detik), dilewati kalau durasi `-1`
- buang `instrumental == 1`
- buang `restricted == 1`
- verifikasi `matcher.track_id` sama dengan yang diminta

Guard teks tetap berlaku di mode pemutaran biasa (`getLyrics`), di mana tujuannya memang satu kecocokan terbaik.

**Artinya perlu dua jalur kode, bukan satu fungsi dengan flag yang mengabaikan guard.** Lebih jelas dan lebih mudah diuji.

### Emisi inkremental itu wajib, bukan optimasi

Dengan hasil pertama di ~4,7 detik (dingin), mengumpulkan semua hasil dulu berarti user menatap layar kosong selama itu. Pancarkan tiap kandidat begitu selesai — `LyricsHelper` meneruskan ke UI lewat channel secara langsung, dan hasil yang sudah masuk **tetap dipakai** meski provider kemudian kena timeout.

### Kandidat search bisa berisi versi, bukan lagu berbeda

Probe mengembalikan "Live From Mexico" untuk Levitating. Itu **berguna** untuk pencarian manual. Tapi jangan heran kalau 5 kandidat tidak berarti 5 lagu berbeda — dengan filter durasi `204`, versi live/remix yang lebih panjang akan terbuang dan hasilnya bisa tinggal 1–2. Implementer sudah mencatat ini dengan tepat.

---

## 5. Kualitas probe

Beberapa hal yang dilakukan dengan benar dan layak dipertahankan:

| Hal | Kenapa penting |
|---|---|
| Error fetch tidak pernah dibocorkan | Pesan error `fetch` bisa memuat URL bertoken; probe menggantinya dengan `'request failed'` dan hanya meneruskan pesan yang dibangun sendiri |
| Body error HTTP dibuang, tidak diparse | `await response.body?.cancel()` — tidak meng-log payload arbitrer |
| Filter `richsyncLines` meniru guard konverter | `te - ts >= 0.002` dan cek kata non-blank — sama dengan `MIN_SPAN` di produksi, jadi angka yang dilaporkan mencerminkan apa yang benar-benar akan dirender |
| Verifikasi `matchedId === track_id` | Membuktikan `track_id` dihormati, bukan sekadar diterima |
| Satu token dipakai ulang, tanpa refresh | Mengisolasi variabel; membuktikan token bisa dipakai lintas request |
| `allEligibleSucceeded` + exit code | Bisa dipakai sebagai gate otomatis, bukan cuma dibaca manusia |

**Koreksi diri oleh implementer juga tepat:** penolakan `token.get` pada probe terdahulu terjadi karena meminta token **baru** antar-probe, bukan bukti token lama mati. Menyatakan penyebabnya belum terverifikasi lebih baik daripada menyimpulkan "token cepat kedaluwarsa".

---

## 6. Yang masih belum teruji

Implementer sudah menyebut batasannya sendiri. Ditambah dari sisiku:

- **Satu sampel, satu kondisi jaringan.** Bukan jaminan latency. Perlu beberapa lagu dan idealnya jaringan yang lebih lambat
- **Jalur renew token di mode search.** Probe memakai satu token tanpa refresh; perilaku `401` di tengah fan-out belum diuji
- **Konversi produksi.** Probe menghitung baris yang layak, tapi tidak menjalankan `MusixmatchTtml` yang sebenarnya
- **Pembatalan saat deadline lewat.** Probe pakai `AbortController`; Kotlin pakai `ensureActive()` — perlu dipastikan pembatalan benar-benar menghentikan fan-out
- **Rate limit saat banyak pencarian berurutan.** Belum diuji; ini yang akan menentukan apakah concurrency 2 aman dipertahankan

---

## 7. Kesimpulan

Probe menjawab pertanyaan yang kutandai perlu verifikasi, dan hasilnya positif: **multi-kandidat layak secara API**.

Tapi angka waktunya menunjukkan bahwa **urutan pekerjaan perlu diubah**. Menulis fitur multi-kandidat sebelum token dihangatkan akan menghasilkan fitur yang lambat di kasus paling umum. Saranku:

1. **Hangatkan token** (§3a) — kecil, langsung memperbaiki UX
2. **Baru implementasi multi-kandidat** dengan 3 kandidat, concurrency 2, emisi inkremental, tanpa guard teks
3. **Persistence token** (§3b) menyusul
4. Uji dengan beberapa lagu dan kondisi jaringan berbeda sebelum mengklaim siap

---

## 8. Koreksi terhadap review ini (setelah tanggapan implementer)

Implementer menolak empat klaim. **Keempatnya benar.** Bagian di atas perlu dibaca dengan koreksi berikut.

### 8.1 Angka "hangat" adalah estimasi, bukan pengukuran

Tabel di §2 menyajikan kolom "Hangat" (~1.295 ms / ~2.906 ms) seolah setara dengan kolom "Dingin". Tidak setara. Kolom dingin adalah **hasil run live**; kolom hangat adalah **hasil pengurangan** waktu token dari run itu.

Estimasi itu mengasumsikan waktu search dan fan-out tidak berubah ketika token sudah ada di memori. Masuk akal, tapi belum diukur. **Perlakukan sebagai hipotesis yang harus dibuktikan dengan run hangat**, bukan sebagai angka yang bisa dikutip di deskripsi PR.

### 8.2 "Hampir pasti dingin" — klaim berlebihan, dan ini yang paling penting

Cache token hilang saat **proses app mati**, bukan saat user menutup layar atau memindahkan app ke background. Proses Android umumnya bertahan melewati keduanya. Objek `Musixmatch` hidup selama proses hidup.

Konsekuensinya: penalti token dingin dibayar **sekali per proses**, bukan sekali per pencarian. Setelah pencarian pertama (atau setelah pemutaran yang kebetulan mencapai Musixmatch), token hangat untuk sisa sesi.

Rantai alasan di §2 — provider di ujung urutan → jarang dipanggil saat pemutaran → dingin saat search — masih mungkin benar untuk **pencarian pertama** di sebuah proses. Tapi itu tidak mendukung kata "hampir pasti", dan tidak mendukung gambaran bahwa setiap search lambat.

**Ini menurunkan urgensi warm-up.** Masalahnya adalah satu tunggu ~3,5 detik sekali per sesi, bukan hambatan berulang. Warm-up tetap berguna, tapi bukan perbaikan mendesak seperti yang kusiratkan. Urutan kerja di §7 sebaiknya dibaca sebagai "layak diuji", bukan "harus dulu sebelum multi-kandidat".

### 8.3 Warm-up tidak menjamin apa pun kalau dimulai terlambat

Karena `getToken()` single-flight, pencarian yang dimulai saat warm-up masih berjalan akan **menunggu mutex yang sama**. Warm-up tidak menghapus latensi; ia hanya memindahkan sebagian waktunya ke sebelum user menunggu.

Manfaatnya proporsional dengan jarak waktu: warm-up mulai 1 detik sebelum search menghemat ~1 detik, bukan 3,5 detik. Kalau dimulai bersamaan, manfaatnya nol.

Jadi pemilihan pemicu bukan detail implementasi — **pemicu adalah inti dari apakah warm-up ada gunanya.** Implementer tepat menuntut keputusan itu diambil, bukan diserahkan sebagai daftar opsi.

### 8.4 `runCatching` menelan `CancellationException`

Saranku "dibungkus `runCatching`" bertentangan dengan aturanku sendiri di spec (§17.3 dan konvensi repo): `CancellationException` harus diteruskan. `runCatching` menangkap `Throwable`, termasuk itu.

Perbaikannya struktural, bukan sekadar mengingatkan pemanggil: **tangani di dalam `warmUp()`** supaya pemanggil tidak punya kesempatan salah. Lihat §9.

---

## 9. Keputusan: pemicu warm-up

Kuterima bahwa tiga opsi tanpa pilihan bukan rekomendasi. Ini keputannya, beserta alasan dan yang kutolak.

### Dipilih: `LyricsMenuViewModel.init`

**Alasan:**

1. **Context sudah tersedia.** `LyricsMenuViewModel` sudah menyuntik `@ApplicationContext private val context: Context` (baris 115). Pemeriksaan toggle tidak butuh dependensi baru.
2. **Lifecycle jelas.** `viewModelScope` — batal otomatis saat ViewModel dibersihkan. Tidak ada scope menggantung, tidak ada pekerjaan yang lolos dari pembatalan.
3. **Menghormati toggle secara alami.** `isEnabled(context)` sudah ada di wrapper.
4. **Idempoten dan gratis setelah hangat.** `getToken()` punya fast-path `cachedToken?.let { return it }`. Membuka menu berulang kali tidak menghasilkan request tambahan. Ini yang membuat pemicu yang "sering" tidak berbiaya.
5. **Sinyal niat cukup kuat.** User membuka menu lirik berarti sedang berurusan dengan lirik; kemungkinan menekan Search jauh lebih tinggi daripada saat app start.

**Kelemahan yang kuakui:** jarak antara menu dibuka dan Search ditekan bisa pendek — kalau user sudah tahu apa yang mau, mungkin di bawah satu detik. Sesuai §8.3, manfaatnya secara proporsional kecil. Ini bukan solusi sempurna, tapi solusi termurah yang memberi manfaat nyata tanpa dependensi baru.

### Ditolak: app start

Untuk provider yang berada di **ujung** prioritas, memanggil jaringan setiap app start tidak sebanding. Menghabiskan kuota rate limit bagi user yang tidak pernah membuka lirik, dan menambah pekerjaan di jalur yang seharusnya ringan. Juga menuntut scope panjang (Application-level) yang lebih rumit dijaga.

### Ditolak: saat toggle di-enable di Settings

Hanya terjadi **sekali seumur hidup preferensi**. Tidak menolong proses berikutnya, padahal justru di situlah token hilang (§8.2). Bisa ditambahkan nanti sebagai bonus murah, tapi tidak bisa menjadi pemicu utama.

### Ditunda: `LyricsRenderViewModel.bind()`

Secara waktu ini **pemicu terbaik** — fires saat layar lirik muncul, lebih awal dari menu. Tapi `LyricsRenderViewModel` hanya menyuntik `PrepareLyricsUseCase`, **tanpa Context**. Memakai ini berarti menambah dependensi ke ViewModel yang ada di jalur render.

Tunda sampai ada pengukuran yang menunjukkan lead time dari menu tidak cukup. Kalau nanti diperlukan, suntikkan `LyricsProviderSettingsUseCase` (sudah ada, sudah tahu provider mana yang aktif) alih-alih `Context` mentah.

### Bentuk kode

**Module** — pembatalan ditangani di dalam, pemanggil tidak bisa salah:

```kotlin
suspend fun warmUp() {
    try {
        getToken()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Pesan tetap; e.message bisa memuat URL bertoken.
        logger?.invoke("Musixmatch warm-up failed")
    }
}
```

**Wrapper** — toggle diperiksa di sini supaya ViewModel tetap tipis:

```kotlin
suspend fun warmUpIfEnabled(context: Context) {
    if (!isEnabled(context)) return
    Musixmatch.warmUp()
}
```

**ViewModel:**

```kotlin
init {
    viewModelScope.launch(Dispatchers.IO) {
        MusixmatchLyricsProvider.warmUpIfEnabled(context)
    }
}
```

Tidak ada `runCatching` di sisi pemanggil. Tidak ada state baru. Tidak ada dependensi baru.

### Cara membuktikan warm-up berguna

Jangan klaim keberhasilan tanpa pengukuran. Yang perlu dibandingkan, pada lagu dan jaringan yang sama:

| Skenario | Yang diukur |
|---|---|
| Proses baru, tanpa warm-up, langsung Search | waktu ke hasil pertama |
| Proses baru, buka menu, jeda 2 detik, Search | waktu ke hasil pertama |
| Proses sama, Search kedua | waktu ke hasil pertama |

Skenario ketiga mengukur **token hangat yang sebenarnya** — sekaligus mengganti estimasi §8.1 dengan angka nyata. Kalau skenario 2 tidak lebih baik dari skenario 1, pemicunya terlalu lambat dan perlu pindah ke `bind()`.

Kalau ternyata selisihnya kecil, **warm-up layak dibuang** dan cukup andalkan emisi inkremental. Itu hasil yang sah, bukan kegagalan.
