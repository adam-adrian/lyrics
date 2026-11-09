# Rencana refactoring Musixmatch — pemisahan identitas dan isi

**Tanggal:** 7 Nov 2026 · **Basis:** submodule `445dfff`
**Pendekatan:** dirancang oleh user (goals-oriented / responsibility separation). Dokumen ini mencatat kesepakatan dan langkah eksekusinya.
**Referensi fakta:** `Musixmatch-Execution-Trace.md` (§1–§17) — semua nomor baris di sini merujuk ke sana.

---

## §0. Keputusan urutan

**Refactoring dulu, fitur kemudian.** Alasan terukur: `lyrics/docs/CONTRIBUTING.md` menetapkan
*"Keep files under 500 lines where possible"*. `Musixmatch.kt` sekarang **461 baris**; fitur terjemahan
menambah ±45 baris → melewati batas. Bila fitur dulu, aturan dilanggar lalu tetap harus refactor.

Fitur (terjemahan, metadata bahasa) **di luar scope dokumen ini** — lihat §10.

### §0.1 KEPUTUSAN: desain refactor lengkap dulu, baru implementasi (8 Nov 2026)

**User: "aku ingin selesaikan dulu refactor ini secara komplit, baru mulai implementasi."**

Artinya **tidak ada handoff ke harness sampai rancangan refactor selesai utuh** — bukan hanya langkah
1–2 yang sudah bebas keputusan. Saran sebelumnya ("langkah 1 dan 2 sudah bisa dieksekusi") **ditarik**.

Yang harus tertutup sebelum implementasi dimulai:
- `resolveRequest()` — **tertutup**: satu sasaran → satu lirik atau lempar; contained (§2.3, §2.4)
- bentuk loop kandidat — **tertutup**: TIDAK ada `resolveCandidates()` dan TIDAK ada wrapper
  `searchCandidates()`. Shared hanya `selectSearchCandidates` (murni) + `resolveRequest`; loop tetap
  di masing-masing orkestrator (§1.4.4, §1.5, §2.6, §4.2)
- logging — **tertutup**: Opsi N disetujui; standarisasi direncanakan sebagai Langkah 8 (§7.1)
- penilai mutu `getLyrics()` — **tertutup**: Jalan 2 dikunci; resolver lapor tingkat mutu, `getLyrics()`
  tidak menebak dari teks (§1.6, §2.3)
- nama/tipe internal — **tertutup**: `ResolvedLyrics` + `SearchCandidate` (§2.5)
- bentuk loop playback — **tertutup**: concurrency 2; early-stop aman berarti membatalkan yang masih
  in-flight, bukan berhenti meluncurkan (§1.4.4, §2.6)
- early-stop playback — **tertutup**: pakai early-stop aman; berhenti hanya bila pemenang sudah pasti
  menurut `(demerit, quality, rating, peringkat)` (§1.4.4)
- penanganan error paralel — **diperbarui**: tiap worker menampung outcome sendiri; `CancellationException`
  eksternal diteruskan, sedangkan `TokenInvalid`/`RateLimited` diputuskan orkestrator setelah outcome
  in-flight terkumpul. Playback mengembalikan sukses terbaik bila ada; fan-out menghentikan antrean lalu
  melempar terminal setelah worker aktif selesai (§1.4.4, §6.1, §6.2)
- nasib duplikasi validasi fan-out setelah §7.2 — tetap mengikuti perilaku lama; perapian opsional,
  bukan blocker desain

Urutan di dalam satu PR tetap: **refactor dulu, fitur kemudian** (§0).

---

## §1. Model yang disepakati

### §1.1 Dua level, sudah nyata di kode

Hanya **dua** fungsi yang terlihat dari luar module:

```kotlin
suspend fun getLyrics(...)      // Musixmatch.kt:342
suspend fun getAllLyrics(...)   // Musixmatch.kt:377
```

Semua sisanya `internal` atau `private`. Jadi batas "highest level" sudah benar dan tidak perlu diubah.

### §1.2 MVP yang jujur

```
getLyrics(title, artist, album, duration)
  1. token.get                 → usertoken   (prasyarat, bukan fitur tambahan)
  2. macro.subtitles.get       → JSON bersarang berisi 4 kotak
  3. buka kotak → teks
```

### §1.3 Penajaman penting: bukan dua pengambil lirik

`macro.subtitles.get` dipanggil dari **dua** tempat dengan **tujuh parameter identik**.
Yang berbeda hanya cara menyebut lagunya:

| mode | menyebut lagu dengan |
|---|---|
| by-name | `q_track`, `q_artist`, `q_artists`, `q_album`, `q_duration`, `f_subtitle_length` |
| by-id | `track_id` |

Maka dekomposisi yang tepat:

```
IDENTITAS : deskriptor (judul, artis, album, durasi) → track id
ISI       : track id → payload lirik
```

- **"Metode 1"** menggabungkan keduanya dalam satu request — server mencari identitas di dalam macro,
  lalu langsung mengirim isi. Hemat satu perjalanan.
- **"Metode 2"** memisahkannya — `track.search` mengurus identitas, macro-by-id mengurus isi.

Bukti pengambilnya sama: respons by-id **tetap memuat kotak `matcher.track.get`**. Matcher berjalan
di kedua mode; di mode by-id ia mengonfirmasi id yang kita berikan.

---

### §1.4 Ekspektasi keluaran — dirancang dari belakang (8 Nov 2026)

**User: "mulai dari belakang — apa ekspektasi keluarannya?"** Batas terluar adalah kontrak app dan
**tidak bisa diubah provider**. Semua bentuk fungsi di dalam diturunkan dari sini.

#### Playback — `getLyrics(): Result<String>`

App menjalankan **semua provider aktif paralel** (`LyricsHelper.kt:284` `fetchPriorityLyrics`), masing-masing
dibungkus `withTimeoutOrNull(8000)`. Lalu:

| app melakukan | anchor |
|---|---|
| menilai mutu tiap hasil: word-sync **2**, TTML/LRC **1**, polos **0** | `:374` |
| memilih mutu tertinggi; seri → indeks provider lebih kecil (urutan prioritas) | `:316-318` |
| memberi **masa tunggu tambahan** setelah hasil pertama, untuk menunggu mutu lebih baik | `:321-326` |
| berhenti lebih awal bila sudah word-sync **dan** semua provider prioritas lebih tinggi selesai | `:329-331` |
| normalisasi + bisa menolak sebagai `LYRICS_NOT_FOUND` | `:357` |

**Ekspektasi: tepat SATU String, mutu setinggi mungkin, secepat mungkin.** Mengembalikan teks polos saat
TTML mungkin didapat = **kalah dari provider lain**.

#### Search — `getAllLyrics(..., callback: (String) -> Unit)`

| app melakukan | anchor |
|---|---|
| menampung tiap callback ke Channel, meneruskan **satu per satu** ke UI | `:231`, `:251-259` |
| melewati hasil yang dinormalisasi jadi `LYRICS_NOT_FOUND` | `:255` |
| **dedup berdasarkan pasangan (nama provider, isi lirik) yang sama persis** | `:257` |
| menyimpan hasil ke cache dan memutarnya ulang di permintaan berikutnya | `:211-214` |
| membungkus seluruh panggilan dengan `withTimeoutOrNull(8000)` — **tapi yang sudah terkirim tetap dipakai** | `:227`, `:251` |

**Ekspektasi: NOL atau LEBIH String, mengalir satu per satu.** Karena timeout memotong di tengah dan hasil
yang sudah masuk channel tetap selamat, **mengirim secara bertahap itu wajib** — bukan pilihan gaya.

#### Yang dipaksa oleh dua ekspektasi itu

1. **Fungsi kandidat harus mengalir (emit per hasil), bukan mengembalikan `List` di akhir.** Kalau
   menunggu semua selesai, timeout 8 detik membuang semua yang sudah dijemput. Ini **dipaksa kontrak app**.
2. ~~**Playback butuh berhenti di sukses pertama**~~ → **dicabut** oleh keputusan "coba keduanya" di bawah;
   playback mencoba semua kandidat, `getLyrics()` yang memilih.
3. **Search butuh membedakan "saringan kosong" dari "ada kandidat tapi semua gagal"** — hanya yang pertama
   memicu fallback by-name (`M:395`). Fakta itu diketahui **sebelum** ada request kandidat, jadi paling
   murah bila penyaringan tetap terlihat pemanggil.
4. **`resolveRequest` tetap satu hasil layak atau lempar** (§2.3). Kontrak app tetap `String`; sejak
   Jalan 2, keluaran internal resolver membawa tingkat mutu, bukan ditebak ulang oleh pemanggil.

#### ⚠️ Satu pertanyaan yang muncul dari belakang — cakupannya SEMPIT

"Kualitas tertinggi" berlaku **di dalam satu rekaman** (tangga richsync → subtitle → plain). Pertanyaan
apakah ia juga berlaku **antar kandidat** ternyata **hanya relevan di satu tempat**:

| jalur | ada pilihan antar kandidat? |
|---|---|
| playback utama (`resolveRequest` by-name) | tidak — satu hasil matcher |
| **playback fallback** (`resolveSearchFallback`) | **ya** — sampai 2 kandidat, `return` di sukses pertama (`S:130`) |
| search fan-out | tidak — **semua** kandidat di-emit, app menampilkan semuanya |
| search title-fallback | tidak — satu hasil matcher |

**Dikonfirmasi dari sisi app:** penilaian mutu `lyricsQuality` **hanya dipakai di jalur playback**
(`LyricsHelper:291`, di dalam `fetchPriorityLyrics`). Konsumen search (`:251-259`) hanya normalisasi +
dedup + teruskan — **tidak menilai mutu sama sekali**. Jadi di search tidak ada yang perlu dipilih;
user yang memilih di UI.

**Radius keputusan ini karena itu kecil:** maksimal 2 kandidat, dan hanya di jalur yang sudah terdegradasi
(fallback hanya jalan bila matcher utama gagal). Biaya "coba keduanya, pilih terbaik" paling banyak
**1 request tambahan** di jalur itu.

#### KEPUTUSAN USER (8 Nov 2026): coba keduanya, pemilihan milik `getLyrics()`

**User: "kita buat coba keduanya, dan ini tugasnya kita serahkan langsung ke getLyrics() itu sendiri."**

Jadi fungsi kandidat **tidak memilih** — ia mencoba semua kandidat yang lolos saringan dan meng-emit tiap
hasil. Yang memilih adalah pemanggil:

| pemanggil | melakukan apa terhadap hasil yang di-emit |
|---|---|
| `getLyrics()` | kumpulkan → **pilih mutu tertinggi**, seri → peringkat kandidat lebih awal |
| `getAllLyrics()` | teruskan semuanya ke callback app |

**Ini menyederhanakan fungsi kandidat:** args "berhenti di sukses pertama" **hilang**. Kedua pemanggil
kini minta semua kandidat dicoba. Yang masih berbeda hanya **jumlah kandidat** (2 vs 4) dan args ke resolver.

**Concurrency playback DIKUNCI 2.** Kekhawatiran sebelumnya (§2.6) adalah paralel membuat yang
menang "paling cepat datang", bukan "paling cocok". Karena sekarang pemilihan berdasarkan **mutu** dengan
seri dipecah oleh **peringkat**, hasilnya deterministik apa pun urutan datangnya. Jadi playback memakai
concurrency 2 — dan itu lebih cepat daripada berurutan, karena keduanya memang dicoba.

**⚠️ Satu kebutuhan baru yang timbul:** `getLyrics()` harus bisa menilai mutu, sementara module **tidak
boleh** mengimpor `LyricsUtils` app (JVM-only, dependency lintas module dilarang). Penilai mutu app
(`lyricsQuality`, `LyricsHelper:374`) karena itu tidak bisa dipakai.

**KEPUTUSAN USER (8 Nov 2026): Jalan 2 — single source of truth.** Resolver ikut melaporkan tingkat
mutu anak tangga yang menang. `getLyrics()` memakai tingkat itu; tidak ada classifier yang menebak
format dari teks. Kontrak app tetap `String`; yang berubah hanya keluaran internal resolver (§2.3).
Jalan 1 (fungsi penilai kecil yang mengenali bentuk teks) **tidak dipakai**.

**Peluang hemat request (belum diputuskan):** bila hasil pertama sudah TTML, kandidat berikutnya tidak
bisa mengalahkannya (seri dimenangkan peringkat lebih awal). Jadi `getLyrics()` boleh menghentikan
 fungsi kandidat lebih awal saat mutu maksimum sudah di tangan. Itu keputusan pemanggil, bukan kebijakan fungsi kandidat.

**Ini perubahan perilaku**, bukan refactor murni: playback fallback bisa mengembalikan hasil berbeda
(mutu lebih baik) dengan biaya sampai 1 request tambahan. Karena itu ia masuk langkah migrasi yang
memang mengubah perilaku, **bukan** langkah 1–2.

#### §1.4.1 Scoring yang ADA sekarang di jalur fallback (8 Nov 2026)

**Sebelum menjemput — ada peringkat, 2 kunci** (`selectSearchCandidates`, `S:22-32`):

```
saring (id>0, bukan instrumental, durasi ±8)  →  distinctBy trackId
→ demerit naik (0 atau 1)  →  track_rating turun  →  take(limit)
```

`demerit` **biner** (`versionDemerit:107-113`): bandingkan penanda versi (remix/live/cover/acoustic/
arrangement/tribute/karaoke) pada judul+album kandidat terhadap penanda pada judul input; **beda → 1**,
sama → 0. Jadi kunci pertama = **kecocokan versi**, kunci kedua = popularitas.

**Setelah menjemput — TIDAK ada scoring sama sekali.** `resolveSearchFallback:130` langsung `return`
pada sukses pertama. Mutu format tidak pernah dibandingkan antar kandidat.

Jadi ada **dua pengertian "skor"** yang hidup di tempat berbeda:

| skor | menilai apa | di mana | kapan |
|---|---|---|---|
| demerit + rating | **rekaman mana yang benar** | provider (`S:29`) | sebelum jemput |
| `lyricsQuality` | **format mana yang lebih kaya** | app (`LyricsHelper:374`) | setelah jemput, antar provider |

#### §1.4.2 ⚠️ Bahaya: mutu tidak boleh mengalahkan kecocokan versi

Kandidat peringkat 2 sering **versi berbeda** (demerit 1 = penanda versinya tidak cocok dengan input).

Contoh ilustratif — input `Dynamite` (tanpa penanda versi), durasi 200 s:

| kandidat | demerit | rating | format tersedia |
|---|---|---|---|
| `Dynamite` (studio) | 0 | 95 | **polos** saja |
| `Dynamite - Live` | 1 | 88 | **richsync** |

- **Mutu dulu** → pilih Live TTML → **salah versi**; kata dan timing tidak sesuai yang diputar.
- **Versi dulu** → pilih studio polos → versi benar, format lebih rendah.

**Aturan yang aman: `demerit` naik → mutu turun → `rating` turun.** Mutu hanya memecah seri **di dalam
kecocokan versi yang sama**, tidak pernah melompatinya.

**Yang dibutuhkan untuk itu:** nilai `demerit` harus **tetap ada sampai waktu pemilihan**. Sekarang ia
dibuang di `S:31` (`.map { it.track }`) — fungsi hanya mengembalikan `Track` berurutan, dan urutan saja
tidak memberi tahu apakah dua kandidat berbeda demerit. Jadi penyaring perlu mengembalikan peringkat
beserta demerit-nya, bukan hanya daftar `Track`.

#### §1.4.3 DITOLAK: scoring di dalam fungsi loop (8 Nov 2026)

**User: "terlalu banyak tanggung jawab & kompleksitas kalau ditaruh di strategi."** Alternatif di bawah
ini **tidak dipakai**; dicatat sebagai bahan pertimbangan saja. Prinsip yang berlaku tetap §1.4:
**loop melaporkan semua hasil apa adanya, `getLyrics()` yang memilih** (loop itu sendiri tetap di
orkestrator, bukan fungsi bersama — lihat §1.4.4).

**Rincian alternatif yang ditolak:**

Fungsi loop tetap mengalir, tapi punya satu args yang menentukan **apa yang di-emit**:

```
resolveCandidates(judul, artis, durasi, tokenState, limit, hanyaTerbaik, emit)   // bentuk historis, ditolak

  track.search → saring + peringkat (demerit, rating)
  untuk tiap kandidat (concurrency 2):
      hasil = resolveRequest(by-id, args sesuai pemanggil)
      ├─ hanyaTerbaik = false → emit(hasil) segera            ← dipakai getAllLyrics
      └─ hanyaTerbaik = true  → simpan, bandingkan            ← dipakai getLyrics
  bila hanyaTerbaik: emit(pemenang) sekali di akhir
```

| | `hanyaTerbaik = true` (playback) | `false` (search) |
|---|---|---|
| yang di-emit | satu, pemenang | semua, segera |
| kapan | setelah semua kandidat dicoba | begitu tiap hasil datang |
| penilai mutu | di dalam fungsi loop | tidak perlu |

**Konsekuensi latensi yang harus diterima:** dengan `hanyaTerbaik`, playback menunggu **kedua** kandidat
sebelum mengembalikan apa pun. Bila anggaran 8 detik habis di tengah, app mendapat **null** — padahal
kandidat 1 tadi sudah punya lirik layak. Dua peredam: **concurrency 2** (kedua macro jalan bersamaan,
jadi wall-clock ≈ satu macro) dan **early-stop aman** (§1.4.4).

Provider **tidak diberi tahu** batas 8 detik itu (`Execution-Trace` §1.4), jadi ia tidak bisa menghitung
sisa anggaran sendiri.


#### §1.4.4 Bentuk final — loop di orkestrator, tanpa `resolveCandidates` / wrapper

> **STATUS: TERTUTUP (8 Nov 2026).** Setelah menimbang Bentuk A/B/C (§1.5), user memutuskan bentuk
> paling sederhana: **tidak ada fungsi `resolveCandidates()`** dan **tidak ada wrapper
> `searchCandidates()`** (pemanggil cuma dua). Yang shared hanya **`selectSearchCandidates` (murni)**
> dan **`resolveRequest`**. Loop kandidat tetap di masing-masing orkestrator, karena di situlah
> streaming, scoring, dan sinyal "saringan kosong" berada.

```
getLyrics():
  resolveRequest(by-name) → sukses → Result.success(lirik)
  NoMatch / Mismatch ↓
  body       = tokenState.execute { executeTrackSearch(judul, artis) }   // langsung, tanpa wrapper
  candidates = selectSearchCandidates(body, judul, durasi, limit = 2)
  jalankan kandidat paralel (concurrency 2); tiap worker mengembalikan outcome:
      sukses → Success(candidate, ResolvedLyrics)
      TokenInvalid / RateLimited → TerminalFailure(candidate, error)   // bukan throw dari child
      Exception lokal → LocalFailure(candidate, error)
      CancellationException eksternal → teruskan
  bila early-stop aman → orkestrator membatalkan hanya child yang kalah
  tunggu worker in-flight, kecuali child kalah yang dibatalkan oleh early-stop aman
  bila ada sukses → kembalikan sukses terbaik (demerit, quality, rating, peringkat)
  bila tak ada sukses dan ada terminal → lempar terminal berperingkat terbaik
  selain itu → lempar error lokal berperingkat terbaik, atau NoMatch

getAllLyrics():
  body       = tokenState.execute { executeTrackSearch(judul, artis) }   // langsung, tanpa wrapper
  candidates = selectSearchCandidates(body, judul, durasi, limit = 4)
  bila candidates kosong → resolveRequest(by-name) → callback
  luncurkan maksimal 2 worker; tiap worker mengembalikan Success / LocalFailure / TerminalFailure
  sukses → callback(hasil.lyrics); local failure → isi slot dengan kandidat antre berikutnya
  saat terminal muncul → jangan luncurkan kandidat antre; biarkan worker aktif selesai
  setelah outcome aktif terkumpul → bila ada terminal, lempar terminal berperingkat terbaik
```

**Tidak ada fungsi loop bersama yang tahu apa itu mutu.** `getLyrics()` yang membandingkan
`(demerit, tingkat, rating)`, dengan seri dipecah oleh peringkat; `getAllLyrics()` hanya meneruskan tiap sukses ke callback. Penyaring
`selectSearchCandidates` mengembalikan `demerit` (tidak membuangnya di `S:31`) supaya `getLyrics` bisa
memakainya; `getAllLyrics` mengabaikannya.

**Tingkat mutu ikut dilaporkan resolver** (Jalan 2, §1.6) — bukan classifier yang menebak dari teks.
`getLyrics` memakai tingkat itu untuk membandingkan kandidat.

**KEPUTUSAN USER (8 Nov 2026): early-stop aman dipakai.** Dengan limit 2 dan concurrency 2, kedua
kandidat sudah ditembakkan bersamaan sejak awal — early-stop tidak menghemat request, hanya berhenti
menunggu / membatalkan yang kalah. Syaratnya: pemenang sudah pasti menurut aturan
`(demerit, quality, rating, peringkat)`. Contoh aman: peringkat 1 sukses dengan `quality = 2`; atau peringkat 1
sukses dan semua kandidat sisa ber-`demerit` lebih tinggi; atau semua kandidat lain sudah gagal.
Jangan berhenti hanya karena hasil pertama datang. Ini berbeda dari `TokenInvalid`/`RateLimited`:
terminal menghentikan peluncuran berikutnya, tetapi worker lain yang sudah in-flight dibiarkan memberi
outcome. Jika `getLyrics()` sudah punya sukses, sukses terbaik yang dikembalikan; jika tidak, terminal
yang dilempar.

---

### §1.5 Bentuk & tanggung jawab loop kandidat — riwayat pertimbangan (SUPERSEDED)

> **STATUS: SUPERSEDED (8 Nov 2026).** Bagian ini mencatat pertimbangan Bentuk A/B/C untuk sebuah
> fungsi `resolveCandidates()`. **Keputusan final: fungsi itu TIDAK dibuat** — loop tetap di
> orkestrator, tanpa wrapper `searchCandidates`. Bentuk final ada di **§1.4.4**. Isi di bawah disimpan
> sebagai riwayat alasan, bukan kontrak yang berlaku.

| nama | status final |
|---|---|
| `resolveRequest()` | **terkunci**: satu sasaran (by-name / by-id) → (lirik, tingkat) atau lempar (§2.3) |
| `selectSearchCandidates()` | **terkunci, murni**: search body + args → daftar kandidat ber-demerit |
| `resolveCandidates()` | **tidak dibuat** — loop di orkestrator (§1.4.4) |
| `searchCandidates()` | **tidak dibuat** — wrapper, pemanggil cuma dua (§4.2) |

Dua kebutuhan pemanggil yang dulu mendorong eksplorasi ini — dan bagaimana bentuk final menjawabnya:

- `getAllLyrics` perlu tahu **daftar saringan kosong** (pemicu fallback by-name, `M:395`) → karena
  `selectSearchCandidates` dipanggil langsung oleh orkestrator, daftar kosong terlihat sendiri.
- `getLyrics` perlu nilai **`demerit` tiap kandidat** untuk aturan versi-dulu (§1.4.2) → `demerit`
  dikembalikan oleh `selectSearchCandidates` dan dipegang orkestrator.

#### Bentuk A — calon: pemanggil memegang daftar

```
getLyrics() fallback:
  body       = track.search(judul, artis)
  candidates = selectSearchCandidates(body, judul, durasi, limit = 2)   ← sudah ada, murni
  resolveCandidates(candidates, args lengkap, tokenState) { peringkat, lirik → bandingkan }

getAllLyrics():
  body       = track.search(judul, artis)
  candidates = selectSearchCandidates(body, judul, durasi, limit = 4)
  if (candidates.isEmpty()) → resolveRequest(by-name); selesai
  resolveCandidates(candidates, args id+durasi, tokenState) { _, lirik → callback(lirik) }
```

| untung | rugi |
|---|---|
| "saringan kosong" terlihat sendiri, tanpa mekanisme laporan baru | `track.search` + `selectSearchCandidates` muncul di **dua** orkestrator (2 baris masing-masing) |
| `demerit` sudah di tangan pemanggil, tidak perlu dikirim lewat emit | `resolveCandidates()` tidak memiliki alur ujung-ke-ujung |
| nol tipe baru; `selectSearchCandidates` sudah ada dan sudah diuji | |

#### Bentuk B — calon: `resolveCandidates()` pegang search+filter+loop

```
resolveCandidates(judul, artis, durasi, tokenState, limit, args, emit) → laporan(jumlahKandidat, errorTerakhir)
```

| untung | rugi |
|---|---|
| satu panggilan per orkestrator; sesuai ucapan awal user "satu fungsi sendiri" | perlu **tipe laporan** baru |
| alur search terkurung di satu tempat | `demerit` harus ikut di `emit` atau di laporan → emit makin lebar |

Alasan awal condong ke A: menyelesaikan **dua** kebutuhan sekaligus (daftar kosong dan `demerit`) tanpa
tipe baru, sedangkan B harus menambah mekanisme laporan untuk keduanya. Ini juga konsisten dengan
keputusan user menolak scoring di fungsi loop karena menambah tanggung jawab. **Status: dibuka kembali**
— user menimbang ulang setelah menyadari kendala streaming + bentuk keluaran simetris.

#### Bentuk C — calon terbaru: keluaran simetris "daftar lirik siap pakai"

**User (8 Nov 2026):** bayangan lebih high-level — `resolveCandidates()` memanggil `resolveRequest()`
sendiri dan mengembalikan **daftar lirik siap pakai**, sehingga dari luar keluarannya simetris dengan
`resolveRequest()`: yang satu single lirik, yang satu list lirik.

```
resolveRequest(...)    → satu lirik siap pakai
resolveCandidates(...) → daftar lirik siap pakai   (search+filter+loop di dalamnya)
```

Bentuk ini masih harus menjawab tiga kendala yang sama (lihat §1.4 "Yang dipaksa"):

1. **Streaming** — `getAllLyrics` wajib kirim hasil begitu tiba; `List` di akhir tidak cukup.
2. **Scoring** — `getLyrics` tetap memilih, jadi item list perlu bawa `demerit`+`rating`, bukan teks polos.
3. **Sinyal kosong** — fallback by-name hanya bila saringan kosong; `List` kosong tidak bisa membedakan
   "saringan kosong" dari "ada kandidat tapi semua gagal".

**Status: belum diputuskan.** User sedang menimbang ulang pemisahan task/responsibility (lihat diskusi
basis arsitektur: discovery / resolution / selection / delivery). `resolveRequest()` sudah dianggap
contained dan stabil; yang dibuka kembali hanya bentuk luar `resolveCandidates()`.

#### Nama yang sempat dipertimbangkan (riwayat — tidak dipakai)

| calon | catatan |
|---|---|
| `resolveCandidates()` | sempat jadi nama kerja; akhirnya **tidak dibuat** |
| `resolveCandidateLyrics()` | lebih eksplisit bahwa yang di-resolve adalah lirik, tapi lebih panjang dan mengulang domain |
| `resolveRankedCandidates()` | menekankan peringkat sudah ada di input; terlalu menonjolkan detail masukan |
| `resolveSearchCandidates()` | terlalu dekat dengan `selectSearchCandidates()` yang sudah ada (filter + peringkat) |
| `forEachCandidate()` / `fanOutCandidates()` | terlalu mekanis; tidak menyampaikan bahwa hasilnya lirik |

**Nama yang berlaku sekarang** cukup dua yang sudah ada / terkunci: `selectSearchCandidates` (murni)
dan `resolveRequest`. Tidak ada nama fungsi loop bersama, karena loop tidak diabstraksikan. Kata
"strategy" tidak dipakai lagi.

### §1.6 Penilai mutu untuk `getLyrics()` — Jalan 2 DIKUNCI (8 Nov 2026)

**KEPUTUSAN USER:** seluruh scoring antar kandidat dilimpahkan ke `getLyrics()`. `resolveRequest()`
tidak memilih pemenang; ia hanya mengembalikan lirik final atau error. **Gagal parse payload tidak
sampai ke scoring**: tangga menanganinya di dalam resolver — turun anak tangga bila masih ada format
lain, atau lempar bila semua format gagal. Jadi `getLyrics()` hanya menilai String yang sudah berhasil
dikeluarkan resolver, bukan payload mentah.

**KEPUTUSAN USER (8 Nov 2026): Jalan 2 — single source of truth.** Resolver melaporkan tingkat mutu
anak tangga yang menang; `getLyrics()` tidak menebak mutu dari bentuk teks. Jalan 1 (classifier) tidak
dipakai. Ini revisi kontrak internal §2.3: keluaran resolver bukan `String` polos lagi, tapi lirik +
tingkat mutu. Kontrak app tetap `String`.

**Kenapa perlu:** sejak keputusan §1.4, `getLyrics()` membandingkan mutu antar kandidat. Penilai app
(`lyricsQuality`, `LyricsHelper:374`) **tidak bisa dipakai** — module JVM-only, dependency lintas module
dilarang (`CONTRIBUTING`).

**Bentuk keluaran kita sudah pasti** (terverifikasi di kode), jadi mengenalinya mudah:

| anak tangga | bentuk pasti | mutu |
|---|---|---|
| richsync → `convertRichSyncToTtml:117-130` | diawali `<?xml version="1.0" encoding="UTF-8"?>` lalu `<tt xmlns=…>`; setiap `<p>` berisi span kata | **2** |
| subtitle → `formatSubtitlesToLrc:79-88` | tiap baris `[mm:ss.mmm] teks` — 2 digit menit, 2 detik, **3 milidetik** (`:68-77`) | **1** |
| lyrics → `cleanPlainLyrics:91-98` | teks polos, tanpa timestamp, footer lisensi dibuang | **0** |

TTML kita **selalu** word-sync: bila semua baris ditolak penjaga `MIN_SPAN`, hasilnya `""` dan tangga
turun — jadi TTML tidak kosong selalu punya span.

#### Jalan 1 — fungsi penilai kecil di module (TIDAK dipakai)

```
mutu(lirik):  diawali "<?xml"        → 2
              baris pertama cocok [dd:dd.ddd] → 1
              selain itu             → 0
```

Murni, ~3 pemeriksaan, tanpa dependency. **Risiko kecil:** teks polos yang kebetulan dimulai seperti
`[00:12.345]` akan salah dinilai 1. Akibatnya hanya salah peringkat antar dua kandidat, bukan salah lirik.

#### Jalan 2 — resolver melaporkan anak tangga yang menang (DIKUNCI)

`resolveRequest` **sudah tahu** jawabannya dengan pasti — tangga berhenti di anak tangga tertentu. Jadi
ia bisa mengembalikan `(lirik, tingkat)` alih-alih menebak ulang dari teks.

| untung | rugi |
|---|---|
| nol penebakan, nol positif palsu | kontrak internal §2.3 berubah: bukan `String` polos lagi |
| satu sumber kebenaran (tangga), tidak diturunkan ulang | satu tipe kecil baru |

**Catatan penting:** kontrak yang dilihat **app tidak berubah** — `getLyrics(): Result<String>` dan
`callback(String)` tetap. Yang berubah hanya keluaran **internal** resolver. Keputusan §2.3 dulu dibuat
untuk menolak pengembalian `Track + payload`; menambah label tingkat adalah hal yang berbeda dan lebih kecil.

**DIPUTUSKAN: Jalan 2**, karena menghindari sumber kebenaran kedua — tangga sudah tahu jawabannya, jadi
tinggal dilaporkan, tidak diturunkan ulang dari teks. Ini revisi §2.3 (keluaran internal resolver =
lirik + tingkat mutu), sudah disetujui user. Kontrak app tetap `String`.

---

## §2. Kontrak wrapper — inti refactoring

Satu fungsi membungkus `macro.subtitles.get` **sekaligus** menutup celah payload lewat endpoint
berdiri sendiri.

```
masukan : sasaran (by-name ATAU by-id) + deskriptor asli (judul, artis, durasi) + tokenState
keluaran: ResolvedLyrics — lirik terbaik yang BISA DIPAKAI + label anak tangga yang menang
```

**KEPUTUSAN 7 Nov 2026 — keluaran String, bukan payload.** Lihat §3.0 untuk alasannya.
**Revisi 8 Nov 2026 (Jalan 2, §1.6):** keluaran internal ditambah tingkat mutu, jadi
`ResolvedLyrics(lyrics, quality)` (§2.5). Tetap bukan payload/`Track`. Kontrak app tetap `String` —
tingkat dilepas sebelum keluar ke app.

**Konsumen:**
1. `getLyrics()` — langsung, mode by-name
2. Jalur yang dulu disebut "metode 2" — `track.search` → pilih kandidat → wrapper mode by-id
3. `getAllLyrics()` — fan-out kandidat, wrapper mode by-id
4. Fallback terbalik di search — wrapper mode by-name

### §2.0 KEPUTUSAN: pengelolaan tiket — pilihan A (7 Nov 2026)

**Wrapper menerima `SearchTokenState` seperti kode sekarang.** Bukan fungsi, bukan objek baru.
Alasan: paling simpel, nol tipe baru untuk pengelolaan tiket, nol perubahan perilaku, dan 106 test yang
ada langsung jadi bukti. (Dua tipe internal di §2.5 adalah tipe hasil, bukan pengelola tiket.)

```
getLyrics:
   tokenState = SearchTokenState(...)
   resolved = resolveRequest(sasaran, deskriptor, tokenState)   // ResolvedLyrics; app terima lyrics saja
```

Manager tiket **tetap dibuat di orkestrator** (`getLyrics:355`, `getAllLyrics:388`), tidak di dalam wrapper.
Dua tingkat yang sudah ada dan tidak boleh digabung:

| tingkat | menyimpan | lingkup |
|---|---|---|
| cache proses (`Musixmatch`) | `cachedToken`, `tokenMutex` | seumur proses app |
| kebijakan operasi (`SearchTokenState`) | `token`, `terminalFailure`, `renewalUsed`, `renewalMutex` | satu operasi |

**Alternatif yang dipertimbangkan dan ditunda:** mengoper fungsi "ambilkan payload ini" sehingga wrapper
tidak tahu ada tiket maupun HTTP. Manfaatnya: tangga §3 bisa dites tanpa server palsu. Harganya: perlu
tipe permintaan baru dan memindahkan penyusunan panggilan jaringan ke luar wrapper. **Bila penulisan test
tangga di langkah 3 terasa berat, pindah ke bentuk itu adalah perubahan mekanis.**

### §2.0.1 ⚠️ Jebakan urutan: validasi harus tetap sebelum jemputan

Urutan sekarang di kedua jalur:

```
playback utama : macro (:358) → matchedTrack (:360) → validateMatch (:361) → jemput (:362)
fallback       : macro (:126) → matchedTrack (:127) → validateMatch (:128) → cek instrumental (:129) → jemput (:130)
```

Bila wrapper menggabungkan macro + jemput lalu validasi dilakukan sesudahnya, kita akan menjemput
richsync untuk lagu yang ternyata salah — **satu request terbuang di jalur gagal**.

**Maka validasi harus terjadi sebelum jemputan.** Untuk jalur playback, validasi (`validateMatch`)
diletakkan di antara macro dan jemputan, sehingga wrapper butuh judul/artis/durasi asli juga di mode
by-id. **Pengecualian: fan-out search** — sesuai keputusan §7.2, fan-out mempertahankan validasi inline
ringan (cukup id + durasi + instrumental, tanpa judul/artis) dan tidak boleh dipaksa lewat `validateMatch`.
Jadi yang dibagi-pakai adalah **inti tangga konversi**; kebijakan validasi di depan tetap milik pemanggil.

Perubahan ini **tidak akan tertangkap** oleh "106 test hijau" — hasilnya sama, yang berubah hanya jumlah
request. Perlu test penghitung request bila ingin dikunci.

### §2.2 USULAN USER: filtering sebatas args yang diberikan caller (8 Nov 2026)

**User:** wrapper tidak menerapkan kebijakan penyaringan sendiri; ia hanya memeriksa **sebatas apa yang
caller berikan sebagai args**. Penyaring/loop kandidat jadi fungsi terpisah yang memanggil
`wrapper(by-id)` per kandidat.

**Penilaian: cocok, dan kode sekarang sudah separuh berbentuk begitu.** Dua dari tiga pemeriksaan sudah
"sebatas args":

| pemeriksaan | bentuk sekarang | sudah sebatas args? |
|---|---|---|
| id | `matchedTrack(calls, expectedTrackId: Long? = null)` — null = lewati | **ya** |
| durasi | `validateMatch`: `if (inputDuration > 0 && track.trackLength > 0)` | **ya** |
| judul/artis | `validateMatch`: input kosong → `titleOk=false`, `artistOk=false` → **selalu Mismatch** | **belum** |

**Empat jalur jadi cukup dibedakan oleh args, bukan oleh enum kebijakan:**

| jalur | args yang diberikan | pemeriksaan yang aktif |
|---|---|---|
| playback utama | judul, artis, durasi (by-name) | durasi + judul/artis |
| playback fallback | id + judul, artis, durasi | id + durasi + judul/artis |
| search title-fallback | judul, artis, durasi (by-name) | durasi + judul/artis |
| **search fan-out** | **id + durasi saja** | id + durasi (tanpa judul/artis) |

Ini **persis** perilaku sekarang termasuk keputusan §7.2, tanpa tipe kebijakan baru.

**Satu tweak diperlukan:** judul/artis kosong harus berarti **"tidak diminta, lewati"**, bukan
"tidak cocok". Aman: kedua orkestrator sudah menolak judul/artis kosong di depan (`M:351`, `M:386`),
jadi `validateMatch` hari ini tidak pernah menerima input kosong — menambah aturan lewati tidak
mengubah jalur mana pun yang ada.

**Yang belum tertutup: instrumental.** Ia bukan args — ia sifat lagu yang baru diketahui **setelah**
macro datang. Jadi "sebatas args" tidak bisa mengungkapkannya.

Kuncinya: **error bisa dibedakan pemanggil, String tidak.** `Restricted` aman karena dilempar —
playback menangkapnya sebagai gagal total, fan-out sebagai lewati kandidat. Instrumental sekarang
**dikembalikan sebagai String** `"♪ Instrumental ♪"` oleh resolver. Fan-out hari ini sudah skip lewat
pre-check flag instrumental (`M:427-428`, lihat §7.2), jadi ia tidak bergantung pada pembedaan teks.
Yang rapuh adalah kontrak resolver: fakta instrumental tidak dilaporkan sebagai error, sehingga pemanggil
harus punya pre-check sendiri; kalau pre-check itu tidak ada, penanda String terlihat seperti lirik biasa.

**KEPUTUSAN USER (8 Nov 2026): pilihan B — instrumental DILEMPAR, bukan dikembalikan.**

Alasan yang diterima: melempar itu **melaporkan fakta**, bukan menyaring. Resolver bilang "lagu ini
tanpa vokal"; pemanggil yang memutuskan mau diapakan. Jadi resolver tetap nol kebijakan, dan pola ini
**sama dengan `Restricted`** yang sudah ada.

| pemanggil | menangkap `Instrumental` → | hasil |
|---|---|---|
| playback utama | kirim `"♪ Instrumental ♪"` | sama seperti sekarang |
| playback fallback | lewati kandidat | sama |
| search title-fallback | null | sama |
| search fan-out | lewati kandidat | sama |

**Semua perilaku sekarang terjaga persis.** Harga: satu jenis error baru di `MusixmatchError.kt`
(sebelumnya 7 kelas, jadi 8).

Dua jalan yang dipertimbangkan lalu **tidak dipilih**: (A) resolver selalu mengembalikan penanda — fan-out
ikut menampilkannya saat data search dan matcher tidak sepakat; (C) fan-out membandingkan teks dengan
konstanta penanda — menjadikan isi string sebagai alur kendali, rapuh.

### §2.3 Keluaran resolver — kontrak lengkap (8 Nov 2026, revisi Jalan 2)

**Berhasil: tepat satu lirik tidak kosong + tingkat mutu.** Sejak Jalan 2 (§1.6), keluaran **internal**
resolver bukan `String` polos lagi, tapi pasangan `(lirik, tingkat)` yang dikemas sebagai
`ResolvedLyrics` (§2.5). Tingkat mutu dilaporkan oleh tangga yang tahu pasti anak tangga mana yang
menang — bukan ditebak ulang dari teks:

| bentuk | sumber | tingkat |
|---|---|---|
| TTML (sinkron per kata) | richsync → `tryTtml` | 2 |
| LRC (sinkron per baris) | subtitle → `tryLrc` | 1 |
| teks polos | lyrics → `tryPlain` | 0 |

**Kontrak app tetap `String`.** `getLyrics(): Result<String>` dan `callback(String)` tidak berubah —
mereka melepas tingkat mutu dan hanya menyerahkan teks. Tingkat dipakai di dalam module oleh
`getLyrics()` untuk membandingkan kandidat (§1.4.2), lalu dibuang sebelum keluar ke app. App tetap
menilai sendiri dari isi bila perlu (`LyricsHelper.kt:374` `lyricsQuality`).

**`"♪ Instrumental ♪"` BUKAN lagi keluaran resolver** (keputusan §2.2). Jadi lirik yang keluar selalu
isi lirik sungguhan.

**Gagal: melempar.** Resolver tidak mengembalikan null dan tidak membungkus `Result` — pemanggil yang
memutuskan cara menangkap.

| yang dilempar | kapan |
|---|---|
| `NoMatch` | matcher tidak menjawab 200 / tidak ada track / tangga habis tanpa hasil layak |
| `Mismatch` | pemeriksaan args gagal: id beda, durasi di luar batas, atau judul **dan** artis dua-duanya tidak cocok |
| `Restricted` | lirik dibatasi pemegang hak |
| **`Instrumental`** (baru) | lagu tanpa vokal |
| `TokenInvalid` | tiket ditolak dan jatah perbarui sudah habis — terminal untuk request/peluncuran berikutnya; hasil paralel diadjudikasi orkestrator (§6.1) |
| `RateLimited` | kena batas server — terminal untuk request/peluncuran berikutnya; hasil paralel diadjudikasi orkestrator (§6.1) |
| `ServerError` | jawaban 5xx |
| `MalformedResponse` | amplop macro tidak bisa dibaca, atau HTTP bukan sukses |
| `CancellationException` | pembatalan — **diteruskan**, tidak pernah ditelan |

**Pembedaan penting:** payload **kotak** yang rusak ≠ `MalformedResponse`. Sesuai §9.3, kotak rusak
dianggap "tidak ada" → konverter mengembalikan null → tangga **turun**. Yang melempar `MalformedResponse`
hanya **amplop** macro yang gagal dibaca.

**Yang TIDAK keluar dari resolver:** `Track`, payload mentah, `tokenState`, skor mutu antar-kandidat.
Tidak ada yang lain — satu `ResolvedLyrics` atau satu exception. Tingkat mutu di sini adalah label
anak tangga yang menang (fakta tunggal dari tangga), bukan hasil scoring antar-kandidat; scoring tetap
milik `getLyrics()` (§1.4). Sebelum keluar ke app, tingkat dilepas dan yang tersisa hanya `String`.

### §2.4 Nama fungsi (8 Nov 2026)

User mengusulkan `lyricsReolver()`. Dua catatan:
1. **Typo:** `Reolver` → `Resolver`.
2. **Konvensi kode:** semua fungsi di module ini **kata kerja dulu** — `resolveMacroLyrics`,
   `resolveTrackLyrics`, `resolveSearchFallback`, `resolveTitleFallbackForSearch`, `fetchToken`,
   `fetchRichSyncSeparately`, `executeMacro`, `executeTrackSearch`, `getLyrics`, `getAllLyrics`,
   `matchedTrack`, `validateMatch`, `selectSearchCandidates`, `convertRichSyncToTtml`,
   `formatSubtitlesToLrc`, `cleanPlainLyrics`. `lyricsResolver()` akan jadi satu-satunya yang kata benda
   dulu.

**Rekomendasi: `resolveLyrics()`.** Sesuai konvensi, dan nama itu **jadi bebas** karena
`resolveTrackLyrics` dihapus (§4.1) dan `resolveMacroLyrics` dipecah (§4.1.1) — tidak ada lagi yang
mirip sehingga tidak ambigu.

`resolve` juga lebih tepat daripada `fetch` (nama sementara di dokumen ini): di module ini `fetch`
berarti ambil dari jaringan (`fetchToken`, `fetchRichSyncSeparately`), sedangkan fungsi ini mengambil
**lalu memilih yang terbaik** — persis makna `resolve` pada `resolveMacroLyrics` yang lama.

**Penajaman user (8 Nov 2026):** fungsi ini abstraction tingkat tinggi — masukannya cukup by-name/by-id,
dan lirik hanyalah keluaran di dalamnya. Jadi namanya sebaiknya menyebut **apa yang diselesaikan
(permintaan)**, bukan **apa yang dihasilkan (lirik)**. Usulan user: `resolveLyricRequest`.

| calon | catatan |
|---|---|
| `resolveRequest()` | paling sesuai alasan user; konteks sudah jelas karena hidup di `object Musixmatch` → `Musixmatch.resolveRequest(...)` |
| `resolveLyricsRequest()` | tetap menyebut domain; **jamak** `Lyrics` agar konsisten dengan `getLyrics`/`getAllLyrics`/`LyricsFormat` |
| `resolveLyricRequest()` | usulan asli user; satu-satunya yang memakai `Lyric` tunggal di seluruh module |
| `resolveLyrics()` | menyebut keluaran, bukan masukan — kurang sesuai alasan user |

**KEPUTUSAN USER (8 Nov 2026): nama dikunci `resolveRequest()`.** Seluruh penyebutan `fetchLyrics` /
"wrapper" di dokumen ini merujuk ke fungsi yang sama; nama resmi `resolveRequest`.

### §2.5 Nama/tipe internal — DIKUNCI (8 Nov 2026)

**KEPUTUSAN USER:** dua data class internal, tanpa enum/`Result` baru:

```kotlin
internal data class ResolvedLyrics(
    val lyrics: String,
    val quality: Int,   // 2 = TTML/richsync, 1 = LRC/subtitle, 0 = plain
)

internal data class SearchCandidate(
    val track: Track,
    val demerit: Int,
)
```

Aturan pakai:
- `resolveRequest(...): ResolvedLyrics`. Sukses selalu punya lirik tidak kosong + tingkat; gagal lewat
  throw, jadi tidak perlu `Result` di tipe ini. Kontrak app tetap `String` — `quality` dilepas di batas
  module.
- `selectSearchCandidates(...): List<SearchCandidate>`. Ini menggantikan keluaran `List<Track>` sekarang
  dan menaikkan/menggantikan `RankedTrack` private (`MusixmatchSearch.kt:85`). Baris `.map { it.track }`
  di `S:31` dihapus supaya `demerit` tidak dibuang.
- `rating` tidak diduplikasi ke `SearchCandidate`; pemanggil membaca `candidate.track.trackRating`.
- `getAllLyrics()` mengabaikan `demerit`/`quality`: ia memakai `candidate.track` untuk args by-id dan
  `resolved.lyrics` untuk callback. `getLyrics()` memakai `(demerit, quality, trackRating)`, dengan seri
  dipecah oleh peringkat, untuk memilih.

### §2.6 KOREKSI USER: dua fungsi high-level dipakai KEDUA orkestrator (8 Nov 2026)

**User:** `getLyrics()` saat `resolveRequest(by-name)` tidak match harus memanggil **fungsi
filter/kandidat yang sama** dengan yang dipakai `getAllLyrics()`. Jadi kedua orkestrator menyusun
**dua fungsi high-level yang sama**, hanya urutan awalnya berbeda.

**Bentuk final (lihat §1.4.4):** `search → filter` dipegang pemanggil lewat `executeTrackSearch` +
`selectSearchCandidates` (tanpa wrapper), lalu **loop tetap di masing-masing orkestrator**. Tidak ada
`resolveCandidates()`. Yang shared hanya `selectSearchCandidates` (murni) dan `resolveRequest`.

```
getLyrics()                          getAllLyrics()
│                                    │
├─ resolveRequest(by-name)           ├─ executeTrackSearch → selectSearchCandidates(limit 4)
│                                    │
├─ NoMatch / Mismatch                ├─ daftar kosong → resolveRequest(by-name)
│  └─ executeTrackSearch             │
│     selectSearchCandidates(2)      ├─ ada kandidat → loop paralel:
│     loop: resolveRequest(by-id)    │    resolveRequest(by-id) → callback(String)
│     → pilih mutu terbaik           │
▼                                    ▼
Result<String>                       callback(String) per hasil
```

Ini menjadikan simetri yang sudah teramati di `Execution-Trace` §14.7 sebagai **struktur kode**, bukan
kebetulan: `playback: matcher → gagal → search` dan `search: search → kosong → matcher`.

**Yang tetap terpisah (sengaja):** dua loop kandidat — `resolveSearchFallback` (`S:115-142`; kode
sekarang berurutan, final concurrency 2) dan fan-out di dalam `getAllLyrics` (`M:404-459`, paralel).
Keduanya tidak digabung jadi satu fungsi, karena streaming, scoring, dan sinyal kosong memang milik
pemanggil. Yang dipakai bersama adalah `selectSearchCandidates` + `resolveRequest`.

**Perbedaan antar dua loop itu** (semua terverifikasi di kode sekarang) tetap dipegang orkestrator:

| | playback fallback | search fan-out |
|---|---|---|
| jumlah kandidat | **2** (`SEARCH_FALLBACK_MAX_ATTEMPTS`, `S:15`) | **4** (`SEARCH_MAX_CANDIDATES`, `M:47`) |
| concurrency | **2 paralel** (final; kode sekarang berurutan) | **2 paralel** (`SEARCH_CONCURRENCY`, `M:48`) |
| penyerahan hasil | ~~berhenti di sukses pertama~~ → **dicabut 8 Nov: coba keduanya, `getLyrics()` yang memilih** (§1.4) | **callback per hasil**, semua kandidat dicoba |
| args ke resolver | id + judul + artis + durasi | **id + durasi saja** |
| terminal muncul | bila ada sukses → pilih sukses terbaik; tanpa sukses → lempar terminal berperingkat terbaik | hentikan antrean, tunggu worker aktif, callback sukses tetap; lalu lempar terminal berperingkat terbaik |
| semua gagal lokal | lempar error lokal berperingkat terbaik (`NoMatch` bila kosong) | diam, tidak ada hasil |

Jumlah kandidat dan args adalah **args**, sesuai prinsip §2.2 — bukan kebijakan di dalam fungsi.

**⚠️ Catatan concurrency — DIKUNCI 8 Nov 2026.** Kekhawatiran awalnya: playback fallback
berurutan berarti peringkat 1 dipakai bila berhasil, sedangkan paralel membuat yang menang "paling cepat
datang". **Itu tidak lagi berlaku** setelah keputusan §1.4 — playback kini mencoba keduanya dan memilih
berdasarkan **mutu**, seri dipecah oleh **peringkat**. Hasilnya deterministik apa pun urutan datang,
jadi playback **concurrency 2** dan justru lebih cepat. Karena keduanya sudah diluncurkan, early-stop
berarti **membatalkan yang masih in-flight**, bukan berhenti meluncurkan kandidat berikutnya.

**⚠️ Satu percabangan yang harus tetap bisa dibedakan:** `getAllLyrics` memicu fallback by-name
**hanya bila hasil saringan kosong** (`M:395` `if (candidates.isEmpty())`), **bukan** bila semua
kandidat gagal. Karena bentuk final membuat `selectSearchCandidates` dipanggil langsung oleh
orkestrator, daftar kosong terlihat sendiri — jadi percabangan ini aman tanpa mekanisme laporan baru.
Inilah salah satu alasan loop tidak diabstraksikan.

### §2.7 Kenapa bentuk ini menyelesaikan kendala "fused"

Kendala yang sempat mengganjal: metode 1 mengembalikan identitas **dan** isi sekaligus, jadi tidak muat
dalam rantai kaku `identitas() → isi()`. Memaksanya jadi dua panggilan akan merusak Koiiro
(search tidak menemukannya, matcher menemukannya).

Wrapper menerima **kedua mode pengalamatan**, jadi kendala itu hilang dengan sendirinya:
mode by-name menghasilkan identitas+isi sekaligus, mode by-id menghasilkan isi untuk identitas
yang sudah kita pilih. Tidak ada jalur yang dipaksa lewat `track.search`.

---

## §3. Tangga — berhenti di hasil yang BISA DIPAKAI

### §3.0 Kenapa kriteria berhenti bukan "dapat payload"

**Masalah yang ditemukan:** "tersedia" tidak sama dengan "bisa dipakai". Payload bisa datang lengkap
tapi konversinya menghasilkan kosong — misalnya semua baris richsync ditolak penjaga `MIN_SPAN`
di `convertLine`, sehingga `convertRichSyncToTtml` mengembalikan `""`.

Skenario celah bila tangga berhenti di "dapat payload":

```
macro:      matcher 200 · richsync 404 · subtitle 404 · lyrics 200
flag Track: has_richsync=1 · has_subtitles=1

tangga:  richsync dijemput → BERHASIL → BERHENTI (subtitle tidak dijemput)
render:  richsync → konversi → "" → turun ke subtitle
         subtitle → kotak macro 404, tidak pernah dijemput → turun ke plain
hasil:   TEKS POLOS, padahal track.subtitle.get mungkin memberi 76 baris
```

**Celah ini sudah ada di kode sekarang** — bukan akibat rancangan ini. Tapi karena kita akan menambah
jemputan subtitle, aturan "berhenti di payload terbaik" justru akan menghalangi penutupannya.

**Keputusan user: pilihan (b) yang mengarah ke (d)** — tangga memanggil konverter untuk menguji
kelayakan, sehingga keluarannya langsung String dan dua keputusan "best" menyatu.

> **Pemutakhiran 7 Nov 2026:** pengukuran lanjutan (§9.2.1) menunjukkan **celah ini tidak ter
> reproduksi** pada 6 pengukuran / 4 lagu — macro mengirim semua yang dijanjikan flag, di kedua mode.
> Jadi skenario di atas **hipotetis**. Keputusan (d) **tetap berlaku**, tetapi alasannya bergeser:
> bukan menutup celah terbukti, melainkan **menyatukan dua keputusan "best" ke satu tempat** sehingga
> urutan prioritas tidak lagi tersebar. Itu tetap alasan yang kuat.

Ini boleh dilakukan karena **konverter adalah fungsi murni** — memanggilnya tidak menambah kerja
jaringan sedikit pun.

### §3.1 Bentuk tangga

```
resolveRequest(sasaran, deskriptor, tokenState) → ResolvedLyrics   // internal; app terima String saja

  1. macro (by-name atau by-id)
  2. matchedTrack  → periksa sebatas args yang diberikan caller (§2.2)
  3. fakta lagu: restricted → throw Restricted · instrumental → throw Instrumental

  4. anak tangga richsync:
       payload = inline ATAU (has_richsync=1 ? jemput track.richsync.get : null)
       hasil   = tryTtml(payload)            // murni
       hasil tidak kosong? → KELUAR (lirik, tingkat=2)

  5. anak tangga subtitle:
       payload = inline ATAU (has_subtitles=1 ? jemput track.subtitle.get : null)
       hasil   = tryLrc(payload)             // murni
       hasil tidak kosong? → KELUAR (lirik, tingkat=1)

  6. anak tangga plain:
       payload = inline
       hasil   = tryPlain(payload)           // murni
       hasil tidak kosong? → KELUAR (lirik, tingkat=0)

  7. NoMatch
```

**Kriteria berhenti: hasil konversi tidak kosong**, bukan payload berhasil dijemput.

**Catatan langkah 2–3 (lihat §2.2, §2.3, §7.2):** resolver tidak punya kebijakan sendiri. Langkah 2
memeriksa **sebatas args** — fan-out cukup memberi id + durasi sehingga judul/artis tidak diperiksa;
playback memberi judul/artis/durasi sehingga semuanya diperiksa. Langkah 3 hanya **melaporkan fakta**
lewat exception; pemanggil yang memutuskan (`Instrumental` → playback kirim penanda, fan-out lewati).

**Biaya:** 0 request tambahan bila bundel lengkap dan layak · 1 pada kasus umum · maksimal 2–3 pada
kasus terburuk. Tetap bukan "jemput semua".

**Yang tertutup:** celah §3.0, dan asimetri lama (dulu hanya richsync punya kesempatan kedua —
`isSubtitlesAvailable` dan `isLyricsAvailable` didefinisikan tapi tidak pernah dibaca, §16.10).

**Yang tetap berlaku:** payload rusak dianggap "tidak ada" dan dijemput ulang (keputusan §9.3).
Bila jemputan ulang juga rusak, `tryTtml` mengembalikan null dan tangga **turun ke anak berikutnya** —
tidak berhenti di payload yang tidak bisa dipakai.

---

## §4. Yang dihapus, yang tetap murni

### §4.1 `resolveTrackLyrics` dihapus, bukan dipecah

Isinya (`Musixmatch.kt:328-340`):

| yang dilakukan | sudah ada di tempat lain? |
|---|---|
| periksa `restricted` | ya — `LyricsFormat.kt:31` |
| periksa `instrumental` | ya — `LyricsFormat.kt:33` |
| baca richsync inline | ya — `LyricsFormat.kt:37-40` (empat baris identik) |
| jemput richsync terpisah | **tidak** — pindah ke wrapper (§2) |
| delegasi ke `resolveMacroLyrics` | — |

Setelah jemputan pindah, tidak ada yang unik tersisa. Fungsi ini bisa dihapus dan
`resolveMacroLyrics` dipanggil langsung.

### §4.1.1 `resolveMacroLyrics` dipecah jadi tiga konverter murni

Karena tangga (§3) kini memanggil konverter per anak tangga, urutan kaskade pindah ke tangga dan
`resolveMacroLyrics` sebagai satu fungsi besar tidak diperlukan lagi. Yang tersisa tiga fungsi murni:

```
tryTtml(richsyncBody: String?): String?     // decode + convertRichSyncToTtml, null bila kosong/gagal
tryLrc(subtitleBody: String?): String?      // decode + formatSubtitlesToLrc
tryPlain(lyricsBody: String?): String?      // cleanPlainLyrics
```

Masing-masing mengembalikan null bila tidak bisa dipakai. **Urutan prioritas hanya ada di satu tempat:
tangga.** Sekarang urutan itu tersirat di dalam `resolveMacroLyrics` dan sebagian di `resolveTrackLyrics`.

Pemeriksaan `restricted` dan `instrumental` pindah ke langkah 3 tangga — dilakukan **sekali**,
menghilangkan duplikat `M:330`/`F:31` dan `M:332`/`F:33`.

### §4.2 Logika keputusan sudah murni — pertahankan

Non-suspend, bisa dites tanpa server palsu:

```
validateMatch          selectSearchCandidates   isSearchCandidate
versionMarkers         versionDemerit           normalizeString
matchedTrack           resolveMacroLyrics       formatSubtitlesToLrc
formatLrcTimestamp     cleanPlainLyrics         convertRichSyncToTtml
convertLine            escapeXml                formatTtmlTime
```

Setelah refactoring, `resolveMacroLyrics` **tidak lagi menerima `tokenState`** — ia hanya memilih
dari payload yang sudah ada. Itu uji kelayakan batasnya: bila masih butuh tiket, batasnya salah tempat.

**KEPUTUSAN USER:** `selectSearchCandidates` dipertahankan sebagai **fungsi murni**. Ia hanya menerima
`SearchBody` + args (`inputTitle`, `durationSeconds`, `limit`) dan mengembalikan
`List<SearchCandidate>` (§2.5), bukan `List<Track>` — `demerit` ikut keluar, tidak dibuang di `S:31`.
Tidak ada jaringan, token, logging, atau pengetahuan playback/search di dalamnya. Kebijakan saring +
peringkat (`isSearchCandidate`, `versionMarkers`, `versionDemerit`) tetap satu kelompok murni yang
gampang dites.

**KEPUTUSAN USER (8 Nov 2026): wrapper `searchCandidates` TIDAK dibuat sekarang.** Alasannya: pemanggil
hanya dua (`getLyrics()` dan `getAllLyrics()`), jadi keduanya langsung melakukan:
`tokenState.execute { executeTrackSearch(...) }` → `selectSearchCandidates(body, ...)`.
Tidak ada lapisan bungkus tambahan di antara jaringan dan penyaring murni.

### §4.3 Yang tetap butuh jaringan

```
token: getToken / renewToken / fetchToken
endpoint: executeTrackSearch / macro (dua mode) / richsync / subtitle
orkestrator: getLyrics / getAllLyrics + dua fallback
penjaga tiket: SearchTokenState
```

---

## §5. Koreksi nama endpoint

| nama | status |
|---|---|
| `track.richsync.get` | benar, dipakai produksi |
| **`track.subtitle.get`** (tunggal) | berdiri sendiri, **teruji 200** |
| `track.subtitles.get` (jamak) | **nama kotak di dalam macro**, bukan endpoint |
| `track.lyrics.get` | ada sebagai kotak macro; **berdiri sendiri belum kita uji** |
| `track.plaintext.get` | tidak ada |

Bukti `track.subtitle.get` pada アイドル (`track_id=262827810`):

```
subtitle_format=lrc → 200, teks LRC siap pakai
subtitle_format=mxm → 200, JSON 76 baris, kunci ['text','time']
```

Endpoint berdiri sendiri **tidak bergantung pada matcher** — cukup id. Untuk アイドル, macro by-name
menghasilkan matcher 404 sehingga semua kotak 404, tapi `track.subtitle.get?track_id=` tetap 200.

---

## §6. Semantik error yang wajib dijaga

**Payload hilang ≠ gagal.** Error terminal menghentikan request/peluncuran baru. Di loop paralel,
worker aktif tetap mengembalikan outcome lalu orkestrator menerapkan §6.1; hasil sukses playback yang
sudah didapat tidak dibuang.

| kondisi | perlakuan |
|---|---|
| payload tidak ada / 404 | lanjut ke anak tangga berikutnya |
| gagal decode | lanjut (tapi lihat §9.3) |
| `Restricted` | lewati kandidat ini |
| `TokenInvalid` | perbarui tiket **sekali** per operasi, lalu ulang; setelah jatah habis jadi terminal outcome |
| `RateLimited` | poison tokenState dan hentikan request/peluncuran baru; orkestrator mengarbitrasikan outcome (§6.1) |
| `CancellationException` | **teruskan**, jangan ditelan |

Pembedaan ini sudah ada di `fetchRichSyncSeparately` (`Musixmatch.kt:295-313`) — tinggal dipertahankan
waktu digeneralisasi ke tangga §3.

### §6.1 Siapa melakukan apa — resolver vs pemanggil (8 Nov 2026)

**Yang resolver urus sendiri, tidak pernah sampai pemanggil:**

| kejadian | aksi di dalam resolver |
|---|---|
| 401 pada sebuah request | minta perbarui tiket lewat `SearchTokenState` (**sekali per operasi**), lalu **ulang request yang sama** |
| kotak payload 404 / rusak / hasil konversi kosong | tangga **turun** ke anak berikutnya |
| semua anak tangga habis | lempar `NoMatch` |

Perbaruan tiket dibagi antar kandidat: `renewalMutex` + cek `token != previous` membuat kandidat kedua
memakai tiket baru **tanpa** minta perbarui lagi (`SearchTokenState:46-68`).

**Yang dilempar keluar — aksi tiap pemanggil** (matriks lengkap, sesuai `Error-Inventory` §5 + keputusan §2.2):

| error | playback utama | playback fallback (per kandidat) | fan-out search (per kandidat) | title-fallback |
|---|---|---|---|---|
| `NoMatch` | **picu fallback search** | catat, coba kandidat berikut | lewati | null |
| `Mismatch` | **picu fallback search** | catat, lanjut | lewati | null |
| **`Instrumental`** (baru) | **kirim `"♪ Instrumental ♪"`** | lewati kandidat | lewati kandidat | null |
| `Restricted` | **gagal total, tanpa fallback** | catat, lanjut | lewati | null |
| `ServerError` | gagal total | catat, lanjut | lewati | null |
| `MalformedResponse` | gagal total | catat, lanjut | lewati | null |
| `Exception` lain (non-Musixmatch) | gagal total | **catat, lanjut** (baru, §6.2) | lewati | lempar |
| `TokenInvalid` (jatah habis) | gagal total | worker mengembalikan terminal; jika ada sukses, pilih sukses terbaik; jika tidak, lempar terminal terbaik | hentikan peluncuran antrean, tunggu worker aktif; callback sukses tetap; lalu lempar terminal terbaik | **lempar** |
| `RateLimited` | gagal total | worker mengembalikan terminal; jika ada sukses, pilih sukses terbaik; jika tidak, lempar terminal terbaik | hentikan peluncuran antrean, tunggu worker aktif; callback sukses tetap; lalu lempar terminal terbaik | **lempar** |
| `CancellationException` | teruskan | teruskan | teruskan | teruskan |

Playback fallback yang kehabisan kandidat lokal melempar error milik kandidat berperingkat terbaik
(final; kode sekarang: error terakhir, `resolveSearchFallback:141`); bila tidak ada kandidat, `NoMatch`.

**KEPUTUSAN USER (9 Nov 2026): terminal diadjudikasi orkestrator di loop paralel.** Worker mengembalikan `TokenInvalid`/`RateLimited`
sebagai outcome, bukan melemparnya dari child. Pada playback fallback, sukses yang sudah didapat tetap
menang; jika tidak ada sukses, terminal berperingkat terbaik dilempar (dan mengalahkan error lokal).
Pada fan-out, terminal menghentikan peluncuran kandidat antre, worker yang sudah aktif dibiarkan selesai,
callback sukses tetap terkirim, lalu terminal berperingkat terbaik dilempar. Jika beberapa terminal
terkumpul, pilih yang berasal dari kandidat berperingkat terbaik. `RateLimited` tetap meracuni
`tokenState`, sehingga pemanggilan `execute` berikutnya gagal tanpa request jaringan.

**Asimetri `Restricted` itu disengaja**, bukan inkonsistensi (`Hybrid-Decisions.md:94`): dari **matcher
utama** ia terminal karena tidak boleh mencari versi lain untuk mengakali pembatasan pemegang hak. Di
**dalam loop** ia per-rekaman — kandidat lain adalah rekaman berbeda yang kebetulan cocok, bukan usaha
mengakali. Fallback hanya dipicu `NoMatch`/`Mismatch`, yaitu saat memang belum ada track yang cocok.

**`RateLimited` meracuni operasi:** `SearchTokenState` menyimpan `terminalFailure`, sehingga setiap
`execute` berikutnya langsung melempar **tanpa menyentuh jaringan** (`:38`). Kandidat lain berhenti murah.

### §6.2 Lebar catch loop — DIKUNCI `Exception` (8 Nov 2026)

**KEPUTUSAN USER:** Loop A (playback fallback) dan Loop B (search fan-out) sama-sama menangkap
`Exception` sebagai outcome lokal. Perubahan perilaku yang disengaja untuk playback fallback: exception
non-`MusixmatchError` (misalnya timeout/`IOException`) tidak lagi langsung menggagalkan operasi;
kandidat lain tetap dicoba.

Setiap worker menangkap error dan mengembalikan outcome bertanda kandidat:

```kotlin
catch (e: CancellationException) { throw e } // pembatalan eksternal tetap diteruskan
catch (e: MusixmatchError.TokenInvalid) { TerminalFailure(candidate, e) }
catch (e: MusixmatchError.RateLimited) { TerminalFailure(candidate, e) }
catch (e: Exception) { LocalFailure(candidate, e) }
```

`TokenInvalid`/`RateLimited` **tidak dilempar dari child worker**: orkestrator menampungnya, lalu
menerapkan aturan §6.1. Ini menjaga error tetap terminal tanpa membubarkan scope sebelum outcome
sibling tercatat. Tidak ada `lastFailure` bersama; setelah outcome terkumpul, pilihan error deterministik
berdasarkan peringkat kandidat, bukan urutan selesai.

Perilaku orkestrator:
- **Playback fallback:** jika ada sukses, pilih sukses terbaik walau kandidat lain menghasilkan terminal.
  Jika tidak ada sukses, lempar terminal berperingkat terbaik bila ada; jika tidak, lempar error lokal
  berperingkat terbaik. Early-stop aman tetap boleh membatalkan child yang kalah setelah pemenang pasti.
- **Fan-out:** terminal menghentikan peluncuran kandidat antre; worker yang sudah aktif dibiarkan selesai.
  Sukses yang sudah didapat tetap dikirim lewat callback, lalu terminal berperingkat terbaik dilempar.
- Pembatalan eksternal tetap diteruskan. Pembatalan internal karena early-stop adalah kontrol alur,
  bukan error kandidat.
- Aturan ini hanya untuk **loop kandidat**. Playback utama dan title-fallback di luar loop tetap
  meneruskan error terminal secara langsung.

**Kode baru yang dibutuhkan untuk matriks ini:** kelas `Instrumental` di `MusixmatchError.kt`
(7 → 8 kelas), satu blok `catch (e: Instrumental)` di `getLyrics` yang mengembalikan
`Result.success("♪ Instrumental ♪")`, dan penyeragaman catch lokal menjadi `Exception` di Loop A
(§6.2). Kolom fan-out untuk error lokal sudah sesuai kode sekarang; kolom playback fallback berubah
karena sekarang mencoba semua kandidat dan menangkap `Exception` lokal.

---

## §7. Urutan migrasi — tiap langkah CI hijau

Prinsip: **satu langkah = satu perubahan yang bisa dibuktikan tidak mengubah perilaku**, kecuali
langkah yang memang menambah perilaku (3, 7, dan 8). Langkah 6 wajib mempertahankan perilaku fan-out lama.

| # | langkah | mengubah perilaku? | bukti |
|---|---|---|---|
| **1** | Ekstrak wrapper (§2) dengan tangga **richsync saja** — persis perilaku sekarang | tidak | 106 test tetap hijau |
| **2** | Hapus `resolveTrackLyrics`; pecah `resolveMacroLyrics` jadi `tryTtml`/`tryLrc`/`tryPlain`; kaskade pindah ke tangga (§4.1.1) | tidak | 106 test tetap hijau |
| **3** | Tambah anak tangga subtitle dengan jemputan `track.subtitle.get` (§3.1 langkah 5) | **ya** | test baru + smoke live |
| **4** | Satukan dua fungsi macro jadi satu dengan mode pengalamatan | tidak | 106 test tetap hijau + parity logging (§7.1 N.7) |
| **5** | Hapus parameter mati: `part=`, `q_artists` | tidak | test + smoke live |
| **6** | Rapikan validasi inline fan-out **tanpa mengubah kebijakan lama**; jangan pakai `validateMatch` apa adanya | tidak (wajib parity) | test perbandingan kandidat/callback (§7.2) |
| **6a** | Micro-step parity: `selectSearchCandidates` mengembalikan `List<SearchCandidate>` (§2.5); filter/urutan tetap, call sites membaca `.track` | tidak | test lama hijau (penyesuaian tipe mechanical bila perlu) |
| **7** | Loop paralel: playback fallback concurrency 2 + coba semua kandidat + komparator + early-stop aman; Loop A/B menampung outcome terminal per worker, menghentikan peluncuran fan-out saat terminal, menunggu worker aktif, lalu mengarbitrase error; `Instrumental` jadi exception (§1.4.4, §2.2, §6.1–§6.2) | **ya** | test komparator + early-stop + terminal/sukses paralel + fan-out berhenti antrean (§11) + smoke live |
| **8** | Standarisasi logging kelas A–D sesuai Opsi N (§7.1) | **ya** | allowlist, invariant B, terminal/early-stop, no-leak (§7.1 N.6) |

**Langkah 1 dan 2 adalah inti nilai refactoring ini.** Setelah keduanya selesai, batas identitas/isi
sudah bersih dan `tokenState` tidak lagi bocor ke penyajian. Langkah 4–6 adalah perapian tambahan
yang bisa ditunda bila waktu terbatas. Langkah 6a + 7 adalah paket perubahan playback fallback:
6a menyiapkan tipe tanpa mengubah kebijakan, 7 memuat kebijakan barunya.

**Jangan gabung langkah.** Bila satu langkah membuat CI merah, penyebabnya langsung terlihat.

**Catatan perilaku baru:** playback fallback paralel + pilih mutu + early-stop, serta arbitrase outcome
`TokenInvalid`/`RateLimited` di Loop A/B (§1.4.4, §6.1–§6.2) **bukan parity** dan sudah menjadi
Langkah 7. Perubahan ini juga menjaga hasil sukses yang sudah didapat; jangan diselundupkan ke langkah
1/2/4/5/6.

### §7.1 Opsi N — logging mengikuti struktur “siapa melakukan apa”

> **KEPUTUSAN USER (9 Nov 2026): Opsi N diadopsi**, termasuk pilihan rinci di N.8. Logging ini
> menjadi Langkah 8 dalam satu PR; implementasi belum dimulai.

**Kondisi yang menjadi dasar:** `logger` adalah `var logger: ((String) -> Unit)?` (`M:60`) dan app
menyambungkannya ke `GlobalLog.append(Log.INFO, "Musixmatch", …)`. Itu layar log debug app dengan
ring buffer 500 entri bersama log lain — jadi log Musixmatch adalah perilaku yang terlihat.

Hari ini hanya macro **by-name** yang mencatat dua baris per attempt (`M:246`, `M:271`); by-id diam.
Call site by-name ada di playback utama (`M:358`) dan title fallback (`S:152`); by-id ada di playback
fallback (`S:126`) dan fan-out (`M:414`). Funnel fan-out dicatat helper `logSearchFunnel` (`S:39–44`),
dengan satu pemanggil sekarang (`M:393`). Logger test (`MusixmatchApiTest.kt:703`) hanya memeriksa
bahwa ada log dan tidak ada kebocoran; ia tidak mengunci pola atau jumlah baris.

#### N.1 Prinsip dan pemilik log

`resolveRequest()` bisu. Ia melaporkan hasil lewat kontraknya: sukses berupa `ResolvedLyrics`, atau
error bertipe. Pemanggil yang mencatat hasil. Empat kelas berikut berlaku dengan pemilik yang sama
untuk jalur playback utama, playback fallback, fan-out, dan title fallback:

| kelas | pemilik | apa yang dicatat |
|---|---|---|
| **A. Request jaringan** | fungsi yang benar-benar mengirim request | attempt dimulai; baris berulang bila request diulang setelah refresh token |
| **B. Outcome resolver** | worker pemanggil `resolveRequest`; orkestrator untuk early-stop | satu hasil per pemanggilan resolver yang sudah mulai |
| **C. Keputusan alur** | orkestrator | masuk fallback, pemenang, early-stop, penghentian antrean, hasil terminal |
| **D. Funnel pencarian** | orkestrator melalui helper penghitung | jumlah hasil, eligible, dan kandidat terpilih |

`selectSearchCandidates` tetap murni dan tidak log. Funnel dihitung terpisah oleh `logSearchFunnel`
dengan predicate `isSearchCandidate` yang sama.

**Batas korelasi yang disetujui:** korelasi dijamin di dalam satu operasi. Dua operasi sejenis masih
dapat tumpang tindih bila caller memulai operasi baru sebelum operasi lama selesai; timeout operasi
lama berjalan menurut batas waktunya sendiri, bukan karena operasi baru dimulai. Opsi N tidak
menambahkan ID operasi sekarang. Tambahkan ID bila ambiguitas lintas operasi benar-benar mengganggu,
khususnya saat funnel dipakai untuk keputusan tuning.

#### N.2 Format dan outcome B

Bentuk umum:

```text
Musixmatch <jalur/kandidat>: <status> [k=v ...]
```

Contoh:

```text
Musixmatch playback: success quality=2
Musixmatch playback: failed (NoMatch)
Musixmatch fallback ranked 2: success demerit=0 quality=2 rating=95
Musixmatch fan-out ranked 3: failed (OtherException)
Musixmatch fallback ranked 1: cancelled (early-stop)
```

Status B:

- `success` — `quality` dari `ResolvedLyrics`; log tidak menebak mutu dari teks.
- `instrumental` — resolver melempar `Instrumental`, lalu pemanggil menentukan tindakan.
- `failed (KelasError)` — nama kelas saja, tanpa `message`.
- `cancelled (early-stop)` — hanya untuk kandidat playback yang dibatalkan setelah pemenang pasti.

Label error tertutup: delapan subclass `MusixmatchError` ditambah label generik `OtherException`
untuk exception non-domain. Tidak mencatat judul, artis, token, lirik, body, atau pesan error.

**Invariant B:** setiap `resolveRequest` yang sudah mulai berjalan berakhir dengan tepat satu
baris B. “Mulai” berarti worker lolos semaphore dan mulai memanggil resolver, bukan coroutine yang
baru dibuat lalu menunggu. Kandidat antre yang tidak pernah mulai tidak mendapat baris B. Pembatalan
eksternal dikecualikan dari jaminan ini.

Worker mencatat outcome terminal/sukses/gagalnya sendiri. Untuk early-stop, orkestrator mencatat
`cancelled (early-stop)` atas nama child yang dibatalkan; worker yang dibatalkan tidak diandalkan untuk
sempat menulis log. Terminal `TokenInvalid`/`RateLimited` bukan pembatalan sibling: sesuai rancangan
error §6.1, worker aktif dibiarkan selesai.

#### N.3 Katalog baris A–D

**Kelas A — request jaringan, per attempt**

| baris A | status |
|---|---|
| token request `started` / `acquired` | pasangan yang ada dipertahankan; tanpa label jalur/kandidat |
| `fallback: track search request started` | **baru**; playback fallback hari ini belum mencatat start search |
| `fan-out: track search request started` | **baru**; fan-out memakai label jalurnya sendiri |
| `playback: macro request started (by-name)` | baris lama diberi label jalur |
| `title-fallback: macro request started (by-name)` | baris lama diberi label jalur |
| `fallback ranked k: macro by-id request started` | **baru** |
| `fan-out ranked k: macro by-id request started` | **baru** |
| `fallback/fan-out ranked k: separate richsync request started` | baris lama dipertahankan dengan jalur dan peringkat |
| `fallback/fan-out ranked k: separate subtitle request started` | **baru** bersama langkah 3 |

A kandidat tetap dicatat di fungsi transpor yang mengirim request; konteks jalur/peringkat diteruskan
sebagai label. Retry mempertahankan peringkat yang sama dan menghasilkan baris A baru. `ranked k`
adalah identitas kandidat dalam operasi, bukan nomor attempt.

**Kelas B — outcome satu pemanggilan resolver**

| jalur | contoh sukses | contoh lain |
|---|---|---|
| playback utama | `playback: success quality=2` | `playback: instrumental` / `playback: failed (NoMatch)` |
| title fallback | `title-fallback: success quality=1` | `title-fallback: instrumental` / `title-fallback: failed (Kelas)` |
| playback fallback | `fallback ranked 1: success demerit=0 quality=2 rating=95` | `fallback ranked 1: failed (ServerError)` |
| fan-out | `fan-out ranked 3: success demerit=0 quality=1 rating=88` | `fan-out ranked 3: failed (OtherException)` |
| playback loser | — | `fallback ranked 2: cancelled (early-stop)` |

B mencatat outcome resolver; C mencatat keputusan sesudahnya. Karena itu tidak ada B `cancelled`
untuk sibling akibat terminal: sibling tidak dibatalkan, melainkan menyelesaikan outcome sendiri.

**Kelas C — keputusan orkestrator**

| kejadian | baris C |
|---|---|
| masuk playback fallback | `fallback: ranked search` — ganti teks lama `S:121` |
| masuk title fallback | `title-fallback: title matcher` — ganti teks lama `S:151` |
| early-stop playback | `fallback: early-stop` |
| pemenang playback | `fallback: winner from ranked 1` |
| semua kandidat gagal secara lokal | `fallback: all candidates failed (ServerError) from ranked 2` |
| terminal menghentikan antrean fan-out | `fan-out: queue stopped — terminal (RateLimited) at ranked 3` |
| terminal fan-out selesai diadjudikasi | `fan-out: final terminal (RateLimited) from ranked 3` |
| playback tanpa sukses memilih terminal | `fallback: final terminal (RateLimited) from ranked 2` |

`queue stopped` hanya untuk fan-out dan hanya bila terminal benar-benar mencegah setidaknya satu kandidat
antre mulai. `final terminal` muncul setelah worker aktif selesai. Di playback fallback tidak ada antrean
(setiap dua kandidat diluncurkan sejak awal): jika ada sukses, C mencatat pemenang; jika tak ada sukses
dan terminal dipilih, C mencatat terminal akhir.

**Kelas D — funnel pencarian**

Format yang sama dengan label jalur:

```text
Musixmatch fallback: search funnel: returned=7 eligible=3 candidates=2
Musixmatch fan-out: search funnel: returned=7 eligible=3 candidates=4
```

Helper `logSearchFunnel` tetap terpisah dari selector. Ia dipakai fan-out seperti sekarang dan ditambah
ke playback fallback; title fallback tidak menyaring kandidat, jadi tidak memiliki funnel.

#### N.4 Nasib baris lama dan anchor — diputuskan

| anchor | nasib |
|---|---|
| `M:123/169` token | pasangan token tetap |
| `M:173–190` `track.search` | tambah A start dengan label fallback/fan-out |
| `M:246` macro by-name started | pertahankan, tambah label playback/title-fallback |
| `M:271` macro response received | hapus; B mencatat outcome resolver |
| `M:282` richsync started | pertahankan, tambah jalur/peringkat untuk worker kandidat |
| subtitle terpisah | tambah A start bersama langkah 3 |
| macro by-id | tambah A start dengan jalur/peringkat |
| `M:368` lyrics format resolved | hapus; B mencatat outcome resolver dan C winner mencatat keputusan fallback, sehingga tidak ada baris hasil duplikat |
| `M:393` pemanggilan funnel | pemanggilan tetap; teks log diubah dari `search:` menjadi label jalur + `search funnel:` |
| `M:442` candidate skipped | ganti dengan B per-kandidat |
| `S:121`, `S:151` | pertahankan keputusan, namai ulang sesuai kelas C di N.3 |

#### N.5 Perkiraan jumlah baris

Hitungan dasar berikut memakai token warm, tanpa retry, dan macro inline lengkap. Token cold atau refresh
di tengah operasi menambah **2** baris token (`started` dan `acquired`); token warm menambah **0**.

| jalur | perkiraan setelah standarisasi |
|---|---:|
| playback utama sukses | 2 — A by-name + B |
| playback fallback, dua kandidat dan ada pemenang | 10 — A/B by-name, C ranked-search, A track-search, D funnel, A/B dua kandidat, C winner |
| fan-out empat kandidat, macro inline lengkap | 10 — A track-search, D funnel, A/B empat kandidat |
| fan-out empat kandidat dengan semua jemputan terpisah | teoretis 18 saat warm — sampai 8 A tambahan |
| fan-out kosong lalu title fallback | **5** — A track-search, D funnel, C title-fallback, A by-name, B |

Early-stop dan terminal adalah jalur khusus, bukan hitungan dasar. Early-stop dapat menambah C early-stop;
B tetap satu per resolver yang mulai, dengan loser dicatat orkestrator. Setiap jemputan richsync/subtitle
terpisah menambah satu A per attempt aktual. Hitungan fan-out di atas memakai jalur warm; token cold/refresh
menambah dua baris sesuai catatan.

#### N.6 Test

1. Allowlist pola A/B/C/D; kelas error tertutup, `OtherException` generik, tanpa `message` atau data sensitif.
2. Tepat satu B untuk setiap resolver yang mulai; pembatalan eksternal dikecualikan, early-stop dicatat
   orkestrator.
3. Urutan baris kandidat boleh berubah karena concurrency; label jalur dan `ranked k` harus benar.
   Retry setelah refresh token menghasilkan A berulang dengan peringkat sama, bukan B tambahan.
4. Playback: satu kandidat sukses dan satu terminal → sukses terbaik dikembalikan; B memuat kedua outcome,
   C mencatat pemenang.
5. Playback tanpa sukses tetapi dengan terminal: terminal kandidat berperingkat terbaik yang dilempar;
   beberapa terminal juga dipilih deterministik.
6. Fan-out: terminal menghentikan antrean, worker aktif selesai, callback sukses tetap terkirim, lalu terminal
   terbaik dilempar.
7. Test terkontrol: ranked 3 terkena `RateLimited` ketika ranked 4 masih antre → tidak ada A/B ranked 4;
   ada C queue-stop dan C final-terminal. Baris B kandidat aktif yang lain tetap ada.
8. Early-stop: B `cancelled (early-stop)` ditulis orkestrator, bukan worker loser.
9. Komparator playback §11 dihubungkan ke C winner; no-leak test lama tetap mencakup semua baris baru.

#### N.7 Peta migrasi

- **Langkah 3:** tambah hanya A `separate subtitle request started` bersama endpoint subtitle baru.
- **Langkah 4:** logging persis seperti sekarang; langkah ini parity, bukan standarisasi.
- **Langkah 7:** ubah perilaku loop tanpa merombak format log: pertahankan `M:368` dan `M:442` sampai
  langkah logging terpisah.
- **Langkah 8:** standarisasi kelas A–D, label jalur/peringkat, dua C terminal, teks funnel, nasib baris
  lama, dan test N.6 dalam satu langkah.

#### N.8 Keputusan Opsi N yang disetujui

- Opsi N diadopsi; label A–D dan invariant B berlaku sebagai kontrak logging.
- `quality` dicatat sebagai angka saja, tanpa nama format.
- `M:271` dihapus; macro by-id mendapat baris A.
- Dua baris C terminal dipakai: queue-stop hanya bila ada kandidat antre yang tertahan, dan final-terminal
  setelah worker aktif selesai.
- Baris worker memakai label jalur + `ranked k`; A/D multi-jalur diberi label jalur.
- B `cancelled (early-stop)` ditulis orkestrator atas nama child yang dibatalkan.
- Baris lama `S:121`/`S:151` dinamai ulang agar seragam dengan label jalur.
- Korelasi dijamin di dalam satu operasi; ID operasi baru ditambahkan bila ambiguitas lintas operasi
  mengganggu keputusan tuning funnel.
- `M:368` dihapus agar B mencatat outcome resolver dan C mencatat keputusan tanpa baris hasil duplikat.

#### §7.1.1 Pembanding: kebiasaan provider lirik lain (diperiksa 8 Nov 2026)

Delapan module provider di submodule `lyrics` (7 upstream + musixmatch fork):

| module | `var logger`? | titik log | app sambungkan ke GlobalLog? |
|---|---|---:|---|
| betterlyrics | ya (`:70`) | 7 | ya, tag `BetterLyrics` |
| unison | ya (`:60`) | 16 | ya, tag `Unison` |
| youlyplus | ya (`:64`) | 4 | ya, tag `YouLyPlus` |
| kugou | tidak | 0 | tidak |
| lrclib | tidak | 0 | tidak |
| paxsenix | tidak | 0 | tidak |
| simpmusic | tidak | 0 | tidak |
| **musixmatch** (fork) | ya (`M:60`) | ~10 | ya, tag `Musixmatch` |

**Pola wiring Musixmatch sudah preseden**, bukan temuan sendiri: `var logger: ((String) -> Unit)? = null`
di object module JVM, app yang menyambung ke `GlobalLog.append(Log.INFO, "<Nama>", …)` di blok `init`
provider. Empat provider lain **tidak punya logging sama sekali**. Jadi repo ini terbelah: 4 bicara, 4 bisu.

**Isi pesan: Musixmatch paling ketat.**

| provider | yang ikut tercetak |
|---|---|
| betterlyrics | judul, artis, album, durasi (`buildRequestLog:204`), status, match score, panjang lirik, **stack trace penuh** saat error (`:142`) |
| unison | judul/artis (`:153`), videoId, **potongan body respons mentah 200 char** (`MAX_LOG_BODY_LENGTH:30`) — bisa memuat teks lirik |
| youlyplus | **URL endpoint lengkap** (`:144`), status, panjang lirik, pesan error |
| **musixmatch** | **string tetap semua**; satu-satunya yang dinamis = hitungan funnel (`returned/eligible/candidates`, `S:42`) |

**Log di dalam loop: ada preseden, tapi semuanya sekuensial dan bawa pembeda.**
- youlyplus: loop atas **5 base URL** (`:141`), log tiap percobaan — pembedanya URL yang berbeda.
- unison: loop atas hasil search memanggil `fetchByVideoId`/`fetchById` yang masing-masing log status+body;
  plus log per hasil **dengan indeks** (`"Unison search result ${index + 1}"`, `:228`).
- betterlyrics: `getAllLyrics` hanya memanggil `getLyrics` (satu endpoint) — **tidak ada fan-out**.
- **Tidak ada provider lain yang fan-out konkuren.** Musixmatch satu-satunya (`Semaphore(2)` + `launch`
  per kandidat), jadi masalah "pesan identik saling selang-seling" **belum ada presedennya**.

**Test logging: nol di 7 module upstream.** Musixmatch satu-satunya yang punya
(`testLogger_neverReceivesTokenUrlBodyLyrics`). Jangan turunkan standar ini agar mirip tetangga.

**Artinya untuk Opsi N §7.1:** log per-kandidat **tidak menyimpang dari kebiasaan repo**
(unison/youlyplus melakukannya), asalkan pesannya membawa pembeda jalur/peringkat. Ini tetap
**perubahan perilaku yang disengaja**, bukan bagian parity Langkah 4; standarisasinya menjadi Langkah 8.
Jangan ikut meniru cetak body mentah ala unison.

### §7.2 Keputusan langkah 6: perilaku fan-out DIPERTAHANKAN (8 Nov 2026)

**User: "biarkan saja seperti perilaku sebelumnya."** Maka kebijakan penerimaan fan-out **tidak berubah**:

| aspek | kebijakan fan-out lama (dipertahankan) |
|---|---|
| identitas | cukup `track_id` matcher == id kandidat; **TANPA** cek judul/artis |
| durasi | tetap dicek inline (`M:427-432`) |
| instrumental | **skip kandidat** (Mismatch), bukan kirim `"♪ Instrumental ♪"` |
| track null | tetap ditolak (jenis error NoMatch/Mismatch sama-sama di-skip fan-out) |

**Konsekuensi teknis:**
- Boleh ganti potongan status-matcher + cek-id (`M:418-426`) dengan `matchedTrack(calls, candidate.trackId)`
  — **observably identik** karena di fan-out kedua jenis error sama-sama ditangkap `catch (e: Exception)`
  → skip. Tapi potongan instrumental+durasi (`M:427-432`) **tetap inline**, dan `validateMatch`
  **TIDAK** introduced ke fan-out.
- **Jangan** samakan fan-out dengan playback fallback. Keduanya memang by-id, tapi playback fallback
  (`S:126-130`) memakai `validateMatch` (judul/artis) sedangkan fan-out tidak. Itu perbedaan yang
  **dipertahankan**, bukan kebetulan yang perlu dibereskan.

**Nuansa untuk wrapper (§2.0.1 / §3.1):** klaim "validasi ikut masuk wrapper, bahkan by-id" berlaku untuk
**playback**, bukan fan-out. Karena fan-out wajib tetap ringan, yang dipakai bersama adalah **inti tangga**
(langkah 4–7: konversi richsync/subtitle/plain + cek restricted). Bagian **depan validasi tetap milik
pemanggil**: playback/main + title-fallback pakai `validateMatch`; playback-fallback pakai `validateMatch`;
fan-out pakai cek inline ringan. Jadi wrapper tidak boleh memaksakan `validateMatch` ke semua pemanggil.

---

## §8. Batasan yang harus dipatuhi

Dari `lyrics/docs/CONTRIBUTING.md`:

- file di bawah **500 baris** bila memungkinkan
- `object` singleton untuk provider; kelas hanya bila menyimpan state
- **tanpa import Android** (module JVM)
- **tanpa dependency lintas module** — `testImplementation(project(":betterlyrics"))` melanggar ini
  dan sudah direncanakan jadi smoke sementara
- model dipisah ke file bila lebih dari 2–3 kelas

Dari kode yang ada dan layak dipertahankan:

- **module tidak pernah pindah dispatcher** — mewarisi `Dispatchers.IO` dari app. Jangan tambahkan
  `withContext`/`Dispatchers`.
- anggaran nyata **8000 ms** dari app, bukan 20000 ms dari konfigurasi klien

---

## §9. Yang belum diverifikasi — jangan diasumsikan

1. ~~**`track.lyrics.get` berdiri sendiri**~~ — **TERVERIFIKASI 7 Nov 2026: bisa.**
   `track.lyrics.get?format=json&track_id=X` → 200 pada dua lagu. Bentuk body `{ "lyrics": {...} }`,
   **identik** dengan kotak macro (hash isi sama, `lyrics_id` sama). Hanya butuh `track_id`.
2. ~~**Apakah macro by-id meninggalkan celah**~~ — **TERVERIFIKASI 7 Nov 2026: tidak ada celah**
   pada 6 pengukuran / 4 lagu, di **kedua** mode pengalamatan. Lihat §9.2.1.
3. ~~**`bodyAs` menelan kegagalan decode**~~ — **DIPUTUSKAN 7 Nov 2026: biarkan.**
   Payload rusak tetap terlihat sebagai "tidak ada" dan akan dijemput ulang. Diterima secara sadar:
   berpeluang memperbaiki hasil, biaya satu request pada kasus jarang. Tidak ada perubahan kode.
   Lihat `Musixmatch-Error-Inventory.md` §6.4.
### §9.2.1 Hasil pengukuran celah (7 Nov 2026, 9 request)

| lagu | mode | `has_richsync` | richsync inline | celah? |
|---|---|---:|---:|---|
| アイドル | by-id | 0 | tidak ada | tidak (flag memang 0) |
| Koiiro | by-id | 0 | tidak ada | tidak (flag memang 0) |
| Blinding Lights | by-id | 1 | 12.853 chars | **tidak** |
| Dynamite | by-id | 1 | 24.518 chars | **tidak** |
| Blinding Lights | by-name | 1 | 12.872 chars | **tidak** |
| Dynamite | by-name | 1 | 24.496 chars | **tidak** |

Pada semua kasus, kotak `track.lyrics.get` dan `track.subtitles.get` juga **200** — sesuai flag.

**Kesimpulan:** macro mengirim semua yang dijanjikan flag, di kedua mode. **Premis celah tidak
ter reproduksi.** Termasuk premis `fetchRichSyncSeparately` yang **sudah ada di produksi** — ia tidak
akan pernah menyala pada lagu-lagu ini.

**Batas sampel:** 6 pengukuran pada 4 lagu. Ini **tidak membuktikan celah tidak pernah terjadi**.
Jangan hapus jemputan yang sudah ada berdasarkan ini.

**Konsekuensi untuk rancangan:**
- Anak tangga jemputan (§3.1) adalah **asuransi, bukan perbaikan masalah terukur**. Biayanya nol
  request bila tidak ada celah — yaitu kasus yang teramati. Jadi tetap layak, tapi jangan dijual
  sebagai perbaikan bug.
- Skenario di §3.0 ternyata **hipotetis**, bukan teramati. Keputusan (d) tetap benar, tapi alasannya
  adalah **penyatuan dua keputusan "best" jadi satu tempat**, bukan menutup celah yang terbukti.

### §9.2.2 Temuan sampingan: isi richsync berbeda antara dua mode

Lagu yang **sama**, `track_id` sama, tapi panjang `richsync_body` berbeda:

```
Blinding Lights : by-id 12.853  vs  by-name 12.872   (selisih 19)
Dynamite        : by-id 24.518  vs  by-name 24.496   (selisih 22)
```

Satu-satunya perbedaan parameter yang relevan: **by-name mengirim `q_duration` + `f_subtitle_length`,
by-id tidak** (§16.8). Jadi petunjuk durasi **tampaknya berpengaruh** pada isi richsync.

Belum diselidiki apa persisnya yang berbeda. **Jangan diubah bersamaan dengan refactoring** — tapi
ini menaikkan priority §9.4 dari "belum tahu apakah berpengaruh" menjadi "teramati berpengaruh".

4. **Petunjuk durasi tidak konsisten**: macro by-name kirim, by-id **tidak**, richsync terpisah kirim.
   **Teramati berpengaruh** — lihat §9.2.2. Jangan diubah bersamaan dengan refactoring.
5. **Musixmatch membatasi `lyrics_body`** untuk akses tidak berlisensi — belum kita ukur.

---

## §10. Di luar scope

| item | kenapa ditunda |
|---|---|
| Terjemahan (`crowd.track.translations.get`) | fitur, bukan refactoring. Bahan sudah terverifikasi (74/76 baris untuk アイドル) |
| Metadata bahasa → `isRtl` | fitur. Field sudah diterima lalu dibuang |
| Romanisasi, agent/duet, background vocal | **tidak ada bahannya** di payload Musixmatch |
| Bug `isEscapedTtml` di `LyricsUtils` | bug **app**, bukan provider. Jangan dicampur |
| Konsolidasi jumlah test | diputuskan setelah fitur selesai |
| Warm-up token | eksperimen terpisah, belum diputuskan |

---

## §11. Checklist selesai

- [ ] Langkah 1–2 selesai, 106 test hijau di CI, nol perubahan perilaku
- [ ] `resolveTrackLyrics` sudah tidak ada
- [ ] `tryTtml`/`tryLrc`/`tryPlain` murni dan non-suspend; urutan prioritas hanya ada di tangga
- [ ] Tangga berhenti berdasarkan **hasil konversi**, bukan keberhasilan jemputan
- [ ] Test: richsync berhasil dijemput tapi konversi kosong → tangga **lanjut ke subtitle**, bukan berhenti
- [ ] Tidak ada lagi pembacaan richsync inline yang duplikat
- [ ] Tangga §3 punya test untuk setiap anak tangga, termasuk "berhenti setelah dapat yang terbaik"
- [ ] `TokenInvalid`/`RateLimited` menghentikan request/peluncuran baru; worker menangkapnya sebagai
      `TerminalFailure`, orkestrator yang memutuskan kapan melempar
- [ ] `CancellationException` eksternal tetap diteruskan; early-stop hanya membatalkan child loser
- [ ] Loop kandidat menangkap `Exception` lokal sebagai outcome per kandidat; tidak ada `lastFailure`
      bersama. Tanpa sukses/terminal, error lokal berperingkat terbaik yang dilempar (deterministik)
- [ ] Playback fallback: satu sukses + satu `TokenInvalid`/`RateLimited` → sukses terbaik dikembalikan
- [ ] Playback fallback tanpa sukses tetapi ada terminal → terminal berperingkat terbaik dilempar;
      seri beberapa terminal juga dipilih deterministik
- [ ] Fan-out: terminal menghentikan kandidat antre, worker aktif selesai, callback sukses tetap terkirim,
      lalu terminal berperingkat terbaik dilempar
- [ ] Early-stop playback hanya berhenti bila pemenang sudah pasti menurut `(demerit, quality, rating, peringkat)`
- [ ] Test komparator versi-dulu: kandidat demerit-0 polos vs demerit-1 richsync → **polos menang**
      (§1.4.2)
- [ ] Test early-stop negatif: peringkat 1 sukses `quality=1` dan masih ada kandidat demerit-sama →
      **tidak boleh** berhenti menunggu
- [ ] Test seri ujung komparator: demerit sama + quality sama + rating sama → **peringkat lebih awal
      menang**; bila rating berbeda, rating lebih tinggi menang
- [ ] Test semua kandidat gagal paralel: error dari kandidat berperingkat terbaik yang dilempar,
      terlepas dari urutan selesai; tidak ada state `lastFailure` bersama
- [ ] Logging A–D lulus allowlist dan no-leak; label jalur/peringkat membedakan kandidat
- [ ] Tepat satu baris B per resolver yang mulai; early-stop dicatat orkestrator, pembatalan eksternal
      tetap di luar invariant
- [ ] Retry token menghasilkan A berulang dengan peringkat sama, bukan B tambahan
- [ ] Fan-out terminal: antrean berhenti, worker aktif selesai, callback sukses tetap, lalu terminal dilempar;
      kandidat antre tidak menghasilkan A/B
- [ ] Smoke live jalur 7 (playback fallback baru) dijalankan, run ID dicatat
- [ ] Semua file produksi < 500 baris
- [ ] Tidak ada `withContext`/`Dispatchers` baru
- [ ] Tidak ada dependency lintas module
- [ ] Smoke live dijalankan sekali untuk jalur subtitle baru, run ID dicatat
