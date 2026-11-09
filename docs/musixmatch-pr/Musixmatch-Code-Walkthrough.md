# Peta kode Musixmatch — alur dari awal sampai akhir

**Untuk dibaca tanpa latar belakang pemrograman.** Basis: `445dfff`.
Semua nomor baris di dokumen ini bisa langsung kamu buka dan cocok.

---

## §0. Tiga istilah saja dulu

| istilah | artinya |
|---|---|
| **fungsi** (function) | satu pekerjaan yang diberi nama. Seperti resep: ada nama, ada bahan masuk, ada hasil keluar. Ditulis `fun namaFungsi(...)` |
| **file** | tempat menyimpan beberapa fungsi yang sejenis. Seperti satu lemari berisi resep-resep yang berhubungan |
| **memanggil** (call) | satu fungsi menyuruh fungsi lain bekerja, lalu menunggu hasilnya. "A memanggil B" = A butuh hasil kerja B |

Kalau kamu pegang tiga ini, sisa dokumen ini bisa diikuti.

Satu lagi yang membantu: **`→`** di dokumen ini artinya "memanggil". Jadi `A → B → C` berarti A menyuruh B, B menyuruh C.

---

## §1. Peta file — siapa mengerjakan apa

Kode produksi kita **11 file, 1.163 baris**. Semuanya di satu folder:
`musixmatch/src/main/kotlin/moe/rukamori/archivetune/musixmatch/`

Bayangkan sebuah kantor kecil yang tugasnya "carikan lirik lagu":

| file | baris | perannya |
|---|---:|---|
| **`Musixmatch.kt`** | 461 | **manajer**. Punya dua pintu masuk, mengatur urutan kerja, dan bicara ke internet |
| **`MusixmatchSearch.kt`** | 164 | **penyaring**. Memilih lagu mana yang layak dicoba, dan mengurutkannya |
| **`SearchTokenState.kt`** | 81 | **penjaga tiket**. Memegang izin akses, memperbaruinya kalau ditolak |
| **`LyricsFormat.kt`** | 100 | **pemilih hasil**. Dari beberapa versi lirik yang datang, pilih yang paling bagus |
| **`MusixmatchTtml.kt`** | 132 | **penerjemah format**. Mengubah data Musixmatch jadi format yang bisa dibaca app |
| **`MusixmatchError.kt`** | 17 | **daftar alasan gagal**. Tujuh jenis, supaya bisa dibedakan |
| `models/MacroModels.kt` | 122 | **kamus bentuk data**. Memberi nama pada bagian-bagian jawaban server |
| `models/CommonModels.kt` | 27 |sama seperti di atas, bagian yang dipakai bersama |
| `models/RichSyncModels.kt` | 24 | sama seperti di atas, khusus lirik per-kata |
| `models/SearchModels.kt` | 20 | sama seperti di atas, khusus hasil pencarian |
| `models/TokenModels.kt` | 15 | sama seperti di atas, khusus tiket akses |

**Lima file pertama adalah otaknya.** Enam sisanya cuma "kamus" dan "daftar error" — tidak ada logika di sana, jadi tidak perlu kamu pahami dulu.

Ada satu file lagi di **app**, bukan di module kita:

| file | baris | perannya |
|---|---:|---|
| `lyrics/MusixmatchLyricsProvider.kt` | 59 | **colokan**. Hanya menyambungkan app ke module kita, tidak punya logika sendiri |

---

## §2. Dua pintu masuk — ini kunci untuk memahami semuanya

Ini bagian yang paling penting. Kalau kamu hanya ingat satu hal dari dokumen ini, ingat yang ini.

Module kita punya **dua** cara dipakai, karena app punya dua kebutuhan berbeda:

| | **playback** | **search** |
|---|---|---|
| fungsi | `getLyrics` | `getAllLyrics` |
| di mana | `Musixmatch.kt:342` | `Musixmatch.kt:377` |
| kapan dipakai | lagu sedang diputar | user mengetik di kotak pencarian lirik |
| yang app mau | **satu** jawaban terbaik | **banyak** jawaban, untuk dipilih user |
| cara kerja | coba satu jalur, kalau gagal pindah jalur lain | cari daftar kandidat, coba beberapa sekaligus |
| hasil keluar | satu teks, atau keterangan gagal | teks dikirim satu per satu lewat "callback" |

**Kenapa harus dua?** Karena saat lagu diputar, app sudah tahu lagu apa itu — jadi kita cukup mencari satu yang paling cocok. Saat user mencari manual, app justru ingin melihat semua kemungkinan, termasuk versi lain.

**"Callback"** = cara mengirim hasil berkali-kali. Alih-alih menunggu semua selesai lalu menyerahkan satu paket, kita menyerahkan setiap hasil begitu siap. Seperti pelayan mengantarkan tiap hidangan begitu matang, tidak menunggu semua pesanan selesai.

---

## §3. Alur playback — `getLyrics`, langkah demi langkah

Konteks nyata: kamu memutar **アイドル / YOASOBI**. App ingin liriknya.

### Langkah 0 — app memanggil colokan

```
LyricsHelper  →  MusixmatchLyricsProvider.getLyrics  →  Musixmatch.getLyrics
   (app)              (colokan, 59 baris)                (mulai kerja)
```

Colokan itu cuma meneruskan. Ia membuang `id` (Musixmatch tidak pakai id Spotify/YouTube), meneruskan judul, artis, album, durasi.

### Langkah 1 — bersihkan masukan

`Musixmatch.kt:342-352`. Spasi di ujung judul/artis dibuang. Kalau judul atau artis kosong → langsung berhenti, tidak perlu ke internet.

### Langkah 2 — siapkan tiket

`Musixmatch.kt:354` → `SearchTokenState(...)` → `getToken()` (`Musixmatch.kt:105`)

Musixmatch tidak mau melayani tanpa "tiket" bernama **token**. Jadi:

- kalau sudah punya tiket yang disimpan → pakai itu, **nol request**
- kalau belum → `fetchToken()` (`Musixmatch.kt:122`) meminta ke alamat `token.get`, lalu menyimpannya

Tiket disimpan di memori selama app hidup. Jadi request pertama agak lambat, berikutnya tidak.

### Langkah 3 — minta lirik lewat jalur utama

`executeMacro(...)` (`Musixmatch.kt:239`) → alamat **`macro.subtitles.get`**

Ini satu request yang meminta banyak hal sekaligus: "carikan lagu yang cocok dengan judul+artis+album+durasi ini, **dan** sekalian kirim liriknya".

Server menjawab dengan beberapa kotak sekaligus:

| kotak | isinya |
|---|---|
| `matcher.track.get` | lagu apa yang server anggap cocok |
| `track.lyrics.get` | lirik polos (tanpa waktu) |
| `track.subtitles.get` | lirik per-baris (ada waktunya) |
| `track.richsync.get` | lirik per-kata (paling bagus) |

### Langkah 4 — periksa: lagunya benar tidak?

Dua pemeriksaan berurutan:

1. **`matchedTrack(calls)`** (`Musixmatch.kt:316`) — apakah server benar-benar menemukan lagu? Kalau kotaknya kosong atau statusnya bukan 200 → gagal `NoMatch`.
2. **`validateMatch(...)`** (`MusixmatchSearch.kt:49`) — apakah lagu yang ditemukan itu **memang lagu yang kita minta**? Diperiksa: durasi harus dalam ±8 detik, dan judul **atau** artis harus nyambung. Kalau judul dan artis **dua-duanya** tidak nyambung → gagal `Mismatch`.

Kenapa perlu? Karena server kadang memberi lagu yang salah — versi live, cover, atau lagu lain sama sekali.

### Langkah 5 — ambil liriknya

`resolveTrackLyrics(...)` (`Musixmatch.kt:328`). Urutannya:

1. Kalau lirik ditandai **restricted** (dibatasi) → berhenti, jangan pakai
2. Kalau lagunya **instrumental** → langsung keluarkan `♪ Instrumental ♪`
3. Kalau lirik per-kata sudah ikut datang di langkah 3 → pakai itu
4. Kalau belum ikut, **tapi** lagu itu punya lirik per-kata → minta terpisah lewat `fetchRichSyncSeparately()` (`Musixmatch.kt:275`), alamat `track.richsync.get`
5. Serahkan semuanya ke `LyricsFormat.resolveMacroLyrics()` untuk dipilih (§5.3)

### Langkah 6 — kalau langkah 4 gagal, pindah jalur

Ini bagian yang bikin kode terlihat rumit, tapi alasannya nyata.

`Musixmatch.kt:361-366`: kalau gagal `NoMatch` atau `Mismatch`, jangan menyerah — panggil **`resolveSearchFallback(...)`** (`MusixmatchSearch.kt:115`).

Jalur itu: cari lewat `track.search` → ambil **2** kandidat teratas → coba satu per satu → yang pertama berhasil, pakai.

**Kenapa perlu jalur kedua?** Karena dua alamat Musixmatch punya titik buta yang berbeda, dan ini sudah kita buktikan langsung:

| lagu | `macro` (jalur 1) | `track.search` (jalur 2) |
|---|---|---|
| Koiiro / もさを。 | **ketemu** | tidak ketemu |
| アイドル / YOASOBI | tidak ketemu | **ketemu** |

Jadi keduanya saling menutupi. Kalau hanya satu, salah satu lagu itu tidak akan pernah dapat lirik.

### Ringkasan alur playback

```
getLyrics
 ├─ bersihkan judul & artis
 ├─ siapkan tiket ──────────────► token.get (hanya bila perlu)
 ├─ executeMacro ───────────────► macro.subtitles.get
 ├─ matchedTrack + validateMatch      ← periksa lagunya benar
 ├─ resolveTrackLyrics
 │    ├─ fetchRichSyncSeparately ─► track.richsync.get (bila perlu)
 │    └─ LyricsFormat.resolveMacroLyrics   ← pilih format terbaik
 └─ kalau periksa gagal:
      resolveSearchFallback ────► track.search, lalu macro per kandidat
```

---

## §4. Alur search — `getAllLyrics`, langkah demi langkah

Konteks nyata: kamu mengetik "Koiiro" di kotak pencarian lirik.

### Langkah 1 — siapkan tiket

Sama seperti playback.

### Langkah 2 — cari daftar lagu

`executeTrackSearch(...)` (`Musixmatch.kt:173`) → alamat **`track.search`**

Minta **20** hasil (`SEARCH_PAGE_SIZE = 20`), hanya yang punya lirik (`f_has_lyrics=1`).

### Langkah 3 — saring dan urutkan

`selectSearchCandidates(...)` (`MusixmatchSearch.kt:17`). Ini fungsi yang paling padat logikanya, tapi urutannya sederhana:

1. buang hasil yang kosong
2. **saring** (`isSearchCandidate`, baris 34): harus punya id, bukan instrumental, durasi dalam ±8 detik
3. buang id yang dobel
4. **beri nilai penalti** (`versionDemerit`, baris 107): kalau judul lagu menandai "Remix"/"Live"/"Cover" tapi yang kamu cari tidak menandainya → penalti 1. Kalau sama-sama menandai, atau sama-sama tidak → penalti 0
5. **urutkan**: penalti terkecil dulu, lalu rating tertinggi
6. ambil **4** teratas (`SEARCH_MAX_CANDIDATES = 4`)

Tujuannya: versi asli/rekaman studio naik ke atas, versi live dan cover turun.

Lalu `logSearchFunnel` (baris 39) mencatat: "datang 20, lolos saring 7, diambil 4" — supaya kalau ada masalah, kita tahu di tahap mana hilang.

### Langkah 4a — kalau tidak ada kandidat sama sekali

`Musixmatch.kt:400-408` → `resolveTitleFallbackForSearch(...)` (`MusixmatchSearch.kt:144`)

Coba jalur `macro` — persis kebalikan dari playback. Inilah kasus Koiiro. Kalau dapat, kirim lewat callback. Kalau tidak, selesai tanpa hasil.

### Langkah 4b — kalau ada kandidat: coba beberapa sekaligus

`Musixmatch.kt:410-459`. Untuk setiap kandidat, satu pekerjaan dijalankan:

```
launch {                          ← jalankan bersamaan, jangan tunggu satu-satu
  semaphore.withPermit {          ← tapi maksimal 2 berjalan sekaligus
     executeMacroByTrackId ──────► macro.subtitles.get?track_id=...
     periksa matcher + id + durasi
     resolveTrackLyrics
  }
  kalau dapat:
     callbackMutex.withLock { callback(lirik) }   ← kirim satu per satu, bergiliran
}
```

Tiga istilah di situ:

| istilah | gunanya | analogi |
|---|---|---|
| `launch` | menjalankan beberapa pekerjaan bersamaan | beberapa pelayan bekerja paralel |
| `Semaphore(2)` | membatasi maksimal 2 berjalan sekaligus | hanya 2 loket yang buka |
| `callbackMutex` | memastikan pengiriman hasil tidak bertabrakan | satu pintu keluar, bergiliran |

**Kenapa dibatasi 2, bukan 4 sekaligus?** Supaya tidak menghajar server dan memicu batas request. Dan kalau satu kandidat gagal (lagu salah, lirik dibatasi), kandidat itu **dilewati saja** — yang lain tetap jalan.

### Ringkasan alur search

```
getAllLyrics
 ├─ siapkan tiket
 ├─ executeTrackSearch ─────────► track.search (20 hasil)
 ├─ selectSearchCandidates           saring → beri penalti → urutkan → ambil 4
 ├─ kalau 0 kandidat:
 │    resolveTitleFallbackForSearch ─► macro.subtitles.get
 └─ kalau ada:
      untuk tiap kandidat (maks 2 bersamaan):
        executeMacroByTrackId ───► macro.subtitles.get?track_id=...
        periksa → resolveTrackLyrics → callback(hasil)
```

---

## §5. Komponen pembantu

### §5.1. Tiket (token) — `Musixmatch.kt:105-171`

Tiga fungsi:

| fungsi | baris | tugasnya |
|---|---:|---|
| `getToken()` | 105 | kalau sudah ada, pakai. Kalau belum, minta |
| `renewToken()` | 112 | minta tiket baru, **tapi** kalau ternyata sudah ada yang lebih baru, pakai yang itu |
| `fetchToken()` | 122 | benar-benar meminta ke `token.get` |

`fetchToken` juga menolak tiket palsu: kosong, semua angka nol, atau diawali `UpgradeOnly`.

`Mutex` di sini artinya: kalau 4 pekerjaan bersamaan sadar tiketnya belum ada, **hanya satu** yang meminta. Tiga lainnya menunggu lalu memakai hasilnya. Tanpa itu, kita mengirim 4 request tiket sekaligus — dan itu pernah membuat server menjawab 401.

### §5.2. Penjaga tiket — `SearchTokenState.kt`

Satu kelas, satu fungsi penting: `execute` (baris 37). Ia membungkus setiap request dengan aturan:

| kalau server bilang | yang dilakukan |
|---|---|
| **429** (kena batas) | **berhenti total**. Jangan coba lagi, jangan lanjut ke kandidat lain |
| **401** (tiket ditolak) | perbarui tiket **sekali**, lalu ulang request. Kalau ditolak lagi → berhenti |
| sudah pernah gagal total | langsung tolak, jangan sentuh server lagi |

Kata "sekali" itu penting: tanpa batas, tiket yang rusak bisa membuat kita meminta tiket baru terus-menerus.

### §5.3. Pemilih hasil — `LyricsFormat.kt:26`

Satu lagu bisa datang dengan tiga versi lirik. Fungsi ini memilih dengan urutan tetap:

| urutan | bentuk | dijadikan | mutu |
|---|---|---|---|
| 1 | **richsync** (per-kata) | **TTML** | terbaik — kata menyala satu per satu |
| 2 | **subtitle** (per-baris) | **LRC** | bagus — baris menyala sesuai waktu |
| 3 | **lyrics** (polos) | teks bersih | paling dasar — tanpa waktu |

Kalau tiga-tiganya kosong → gagal `NoMatch`.

Untuk versi polos, `cleanPlainLyrics` (baris 91) membuang baris berisi "this lyrics is not for commercial use" — itu footer lisensi Musixmatch, bukan bagian lagu.

### §5.4. Penerjemah format — `MusixmatchTtml.kt`

Mengubah data per-kata Musixmatch menjadi **TTML**, yaitu format XML yang sudah bisa dibaca app.

| fungsi | baris | tugasnya |
|---|---:|---|
| `escapeXml` | 33 | membuat tanda berbahaya aman. `&` jadi `&amp;`, `<` jadi `&lt;`. Tanpa ini lirik berisi `<3` merusak file |
| `formatTtmlTime` | 30 | menulis waktu, misalnya `7.748s` |
| `convertLine` | 47 | satu baris jadi satu `<p>` berisi `<span>` per kata |
| `convertRichSyncToTtml` | 114 | menggabungkan semua baris jadi satu dokumen |

`MIN_SPAN = 0.001` artinya: setiap kata harus punya durasi minimal 1 milidetik. Kalau tidak, app bisa menganggap kata itu tidak pernah muncul.

### §5.5. Daftar alasan gagal — `MusixmatchError.kt`

Tujuh jenis, dan **dibedakan karena penanganannya berbeda**:

| error | artinya | akibatnya |
|---|---|---|
| `NoMatch` | lagu tidak ditemukan | pindah jalur |
| `Mismatch` | ketemu, tapi lagu salah | pindah jalur |
| `Restricted` | lirik dibatasi | lewati kandidat ini |
| `TokenInvalid` | tiket ditolak | perbarui sekali |
| `RateLimited` | kena batas server | **berhenti semua** |
| `ServerError` | server rusak (5xx) | lewati |
| `MalformedResponse` | jawaban tidak bisa dibaca | lewati |

Kalau semuanya cuma "gagal" tanpa dibedakan, kita tidak bisa memutuskan mana yang boleh dilanjutkan dan mana yang harus berhenti.

---

## §6. Kamus Kotlin — hanya yang muncul di kode ini

| tulisan | artinya |
|---|---|
| `object Musixmatch` | hanya ada satu instances di seluruh app. Seperti satu kantor, bukan banyak cabang |
| `fun` | ini sebuah fungsi |
| `suspend` | fungsi ini bisa menunggu (misalnya menunggu internet) tanpa membekukan app |
| `internal` | hanya boleh dipakai dari dalam module ini |
| `private` | hanya boleh dipakai dari dalam file/kelas ini |
| `val` / `var` | `val` = tidak bisa diubah lagi; `var` = bisa diubah |
| `String?` | tanda `?` artinya "boleh kosong" |
| `?:` | "kalau yang kiri kosong, pakai yang kanan" |
| `try { } catch { }` | coba lakukan; kalau gagal, tangani di `catch` |
| `throw` | nyatakan gagal, dengan alasan tertentu |
| `Result<String>` | kotak berisi "berhasil + teks" atau "gagal + alasan" |
| `callback` | fungsi yang kita panggil untuk menyerahkan hasil |
| `by lazy` | baru dibuat saat pertama kali dipakai, bukan saat app dinyalakan |
| `@Volatile` | pastikan semua pekerjaan melihat nilai terbaru, bukan salinan lama |
| `Mutex` | gembok — hanya satu yang boleh masuk |
| `Semaphore(2)` | hanya 2 yang boleh masuk bersamaan |
| `launch` | jalankan pekerjaan ini bersamaan dengan yang lain |
| `coroutineScope` | wadah yang menunggu semua pekerjaan di dalamnya selesai |
| `ensureActive()` | "apakah user sudah batal? kalau ya, berhenti sekarang" |
| `sealed class` | daftar jenis yang sudah pasti jumlahnya — dipakai untuk error |

---

## §7. Jawaban jujur: apakah ini slop?

Aku sudah menasihati kode ini dari awal, jadi wajar kalau kamu curiga aku akan membelanya. Tapi aku baca ulang khusus untuk keterbacaan, dan jawabannya dua bagian.

### §7.1. Yang rumit karena masalahnya memang rumit

Lima hal ini **bukan pilihan kita**:

1. Musixmatch punya dua alamat dengan titik buta berbeda → harus ada dua jalur
2. Tiket bisa ditolak di tengah jalan → harus ada cache + pembaruan + gembok
3. Lirik datang dalam tiga bentuk bermutu berbeda → harus ada pemilih
4. Pencarian mengembalikan versi live/cover/remix → harus ada penyaring dan peringkat
5. App hanya memberi 8 detik → harus ada batas concurrency dan pembatalan

Kalau lima ini dihapus, provider-nya jadi sederhana — dan gagal untuk Koiiro, atau kena batas server, atau menampilkan versi cover. Jadi kompleksitas di sini sebagian besar **dibeli dengan alasan**.

### §7.2. Yang rumit karena bisa dirapikan — dan ini valid

Tiga temuan konkret:

**(a) `Musixmatch.kt` mengerjakan enam pekerjaan dalam 461 baris.** Di dalamnya: menyiapkan koneksi internet, mengurus tiket, empat pembungkus alamat server, membaca kode error, memvalidasi lagu, **dan** dua alur utama. Itu banyak. Akan lebih mudah dibaca kalau dipecah jadi tiga file: koneksi+alamat, tiket, dan alur utama.

**(b) Ada logika yang ditulis dua kali.** Di `getAllLyrics` (`Musixmatch.kt:421-436`) pemeriksaan matcher ditulis ulang dari nol — padahal `matchedTrack()` (`Musixmatch.kt:316`) sudah melakukan hal yang sama. Sekitar 8 baris yang seharusnya cukup satu panggilan. Ini bukan bug, tapi kalau nanti aturannya berubah, ada dua tempat yang harus diingat.

**(c) Tangga pemeriksaan error diulang tiga kali.** Pola "periksa 401 → 429 → 5xx → lainnya" muncul di `fetchToken` (122), `decodeApiResponse` (210), dan `fetchRichSyncSeparately` (275). Bisa disatukan.

Ketiganya **utang keterbacaan, bukan kerusakan**. Tidak mengubah perilaku kalau dirapikan, dan cocok dikerjakan di branch bersih.

### §7.3. Kesimpulan

Ini bukan slop — perilakunya terkunci 106 test dan sudah lulus di perangkat. **Tapi perasaan "ini slop" itu datang dari tidak adanya peta**, dan itu kelalaian proses kita, bukan kodemu. Belum ada satu pun dokumen yang menjelaskan "komponen apa memanggil apa" sampai sekarang.

Tiga poin di §7.2 nyata dan layak masuk branch bersih. Kalau kamu mau, aku bisa jadikan itu item eksplisit dalam rencana — terpisah dari fitur terjemahan, supaya jelas mana yang menambah kemampuan dan mana yang cuma merapikan.

---

## §8. Kalau kamu mau membaca kodenya sendiri

Urutan yang kusarankan, dari yang paling mudah:

1. **`MusixmatchLyricsProvider.kt`** (59 baris, di app) — paling sederhana, cuma meneruskan. Membaca ini membuktikan bahwa colokannya tidak menyimpan logika tersembunyi.
2. **`MusixmatchError.kt`** (17 baris) — tujuh alasan gagal. Jadi kamu tahu kosakatanya sebelum bertemu di tempat lain.
3. **`Musixmatch.getLyrics`** (`Musixmatch.kt:342`, ~35 baris) — alur playback. Pendek, dan cocokkan dengan §3 di atas.
4. **`LyricsFormat.resolveMacroLyrics`** (`LyricsFormat.kt:26`, ~40 baris) — pemilih tiga format. Terbaca seperti daftar urutan.
5. **`selectSearchCandidates`** (`MusixmatchSearch.kt:17`, ~17 baris) — penyaring. Padat tapi berurutan.
6. **`Musixmatch.getAllLyrics`** (`Musixmatch.kt:377`, ~80 baris) — paling sulit, karena concurrency. Baca terakhir, setelah lima di atas.

**Jangan mulai dari `SearchTokenState.kt` atau `MusixmatchTtml.kt`.** Keduanya paling teknis dan paling tidak mewakili alur besar.
