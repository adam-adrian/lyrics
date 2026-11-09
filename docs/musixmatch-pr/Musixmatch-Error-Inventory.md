# Inventaris error handling Musixmatch — siapa melempar, siapa menangkap

**Basis:** submodule `445dfff` · **Tanggal:** 7 Nov 2026
**Tujuan:** menuliskan perilaku **sekarang** supaya langkah 1–2 refactoring bisa membuktikan "tanpa perubahan perilaku".
**Pasangan:** `Musixmatch-Refactor-Plan.md` §6 · `Musixmatch-Execution-Trace.md`

---

## §0. Cara baca

Tujuh jenis error didefinisikan di `MusixmatchError.kt:9-17`. Untuk tiap jenis: **di mana dilempar dan kenapa**,
lalu **di mana ditangkap dan apa yang dilakukan**. Ada juga kegagalan yang **tidak melempar apa pun** (§2) —
itu bagian yang paling mudah terlewat.

Singkatan lokasi: `M` = `Musixmatch.kt`, `S` = `MusixmatchSearch.kt`, `F` = `LyricsFormat.kt`,
`T` = `SearchTokenState.kt`, `MM` = `models/MacroModels.kt`.

---

## §1. Tujuh jenis error

### §1.1 `TokenInvalid` — tiket ditolak

**Dilempar di:**

| lokasi | kenapa |
|---|---|
| `M:166` | tiket kosong, semua `'0'`, atau diawali `UpgradeOnly` |
| `M:215` | HTTP 401 pada endpoint apa pun (kecuali `token.get`) |
| `M:232` | status API 401 pada endpoint apa pun (kecuali `token.get`) |
| `M:295` | HTTP 401 pada richsync terpisah |
| `M:301` | status API 401 pada richsync terpisah |
| `T:64` | pembaruan tiket gagal (bukan karena RateLimited) → jadi terminal |

**Ditangkap di:**

| lokasi | aksi |
|---|---|
| `T:45` | **perbarui tiket sekali**, lalu ulang request |
| `T:72` | ditolak lagi setelah ulang → set terminal, lempar |
| `M:307` | lempar terus (jangan ditelan jadi "payload tidak ada") |
| `M:437` | lempar terus → membatalkan fan-out |
| `S:133` | lempar terus → menghentikan loop fallback |
| `S:158` | lempar terus |

**Efek bersih:** perbarui **sekali per operasi**; penolakan kedua = seluruh operasi berhenti.

### §1.2 `RateLimited` — kena batas server

**Dilempar di:**

| lokasi | kenapa |
|---|---|
| `M:130` | **HTTP 401 pada `token.get`** ← perhatikan, bukan TokenInvalid |
| `M:133` | HTTP 429 pada `token.get` |
| `M:152` | **status API 401 pada `token.get`** |
| `M:155` | status API 429 pada `token.get` |
| `M:218` | HTTP 429 pada endpoint lain |
| `M:233` | status API 429 pada endpoint lain |
| `M:296` | HTTP 429 pada richsync terpisah |
| `M:302` | status API 429 pada richsync terpisah |
| `T:64` | pembaruan tiket gagal karena RateLimited |

**Ditangkap di:**

| lokasi | aksi |
|---|---|
| `T:42` | set `terminalFailure`, lempar |
| `T:75` | set `terminalFailure`, lempar |
| `M:309`, `M:439`, `S:135`, `S:160` | lempar terus |

**Efek bersih:** berhenti **segera dan menyeluruh**. `T:38` memeriksa `terminalFailure` di awal setiap
`execute()`, jadi panggilan berikutnya dilempar **tanpa menyentuh jaringan**.

### §1.3 `NoMatch` — lagu tidak ditemukan

**Dilempar di:**

| lokasi | kenapa |
|---|---|
| `M:319` | status kotak matcher ≠ 200 |
| `M:321` | kotak matcher tidak berisi `track` |
| `M:421` | status matcher ≠ 200 di fan-out search |
| `S:56` | `validateMatch` menerima track null |
| `S:129` | kandidat fallback ternyata instrumental |
| `S:141` | semua kandidat fallback gagal, tanpa kegagalan tercatat |
| `F:65` | tidak ada lirik dalam format apa pun |

**Ditangkap di:**

| lokasi | aksi |
|---|---|
| `M:363` | **picu fallback** ke `resolveSearchFallback` |
| `S:137` | catat `lastFailure`, lanjut kandidat berikutnya |
| `S:162` | kembalikan null |
| `M:441` | catat "candidate skipped", kembalikan null |

### §1.4 `Mismatch` — ketemu tapi lagu salah

**Dilempar di:**

| lokasi | kenapa |
|---|---|
| `M:323` | id dari matcher ≠ id yang diminta |
| `M:425` | id tidak cocok di fan-out search |
| `M:431` | instrumental **atau** durasi melenceng di fan-out search |
| `S:61` | selisih durasi > 8 detik |
| `S:82` | judul **dan** artis dua-duanya tidak nyambung |

**Ditangkap di:** `M:365` (picu fallback), `S:137`, `S:162`, `M:441` — **persis sama dengan `NoMatch`**.

### §1.5 `Restricted` — lirik dibatasi

**Dilempar di:** `M:330` dan `F:31` — keduanya memeriksa `lyrics.restricted == 1` (duplikat).

**Ditangkap di:**

| lokasi | aksi |
|---|---|
| **tidak ada penangkap khusus di `getLyrics`** | jatuh ke `M:372` → `Result.failure` |
| `S:137` | catat, lanjut kandidat |
| `S:162` | null |
| `M:441` | lewati kandidat |

**Efek bersih:** di playback utama → **seluruh operasi gagal, tanpa fallback**. Di dalam loop → hanya
melewati satu kandidat. (Lihat §6.2.)

### §1.6 `ServerError` — server rusak

**Dilempar di:** `M:136`, `M:158` (`token.get`), `M:221`, `M:234` (endpoint lain) — semua untuk status ≥ 500.

**Ditangkap di:**

| lokasi | aksi |
|---|---|
| `M:311` | **ditelan jadi null** (richsync terpisah) |
| `M:372` | `Result.failure` |
| `S:137` | catat, lanjut kandidat |
| `S:162` | null |
| `M:441` | lewati kandidat |

### §1.7 `MalformedResponse` — jawaban tidak bisa dibaca

**Dilempar di:**

| lokasi | kenapa |
|---|---|
| `M:139` | HTTP selain 401/429/5xx pada `token.get` |
| `M:147` | body `token.get` gagal di-decode |
| `M:161` | status API selain 200/401/429/5xx |
| `M:224`, `M:230`, `M:235` | pola yang sama untuk endpoint lain |
| `M:236` | body kosong |
| `T:21` | **exception apa pun** yang bukan MusixmatchError dan bukan Cancellation → dibungkus jadi ini |

**Ditangkap di:** sama seperti `ServerError`.

---

## §2. Kegagalan senyap — tidak melempar apa pun

Ini yang paling mudah terlewat: tidak ada `throw`, tidak ada `catch`, hasilnya `null`.

| lokasi | kondisi | hasil | akibat |
|---|---|---|---|
| `MM:43` | body sub-call gagal di-decode | `null` | **"rusak" tidak terbedakan dari "tidak ada"** |
| `M:297` | richsync HTTP gagal selain 401/429 | `null` | lanjut tanpa richsync |
| `M:303` | richsync status API ≠ 200 | `null` | lanjut tanpa richsync |
| `M:311` | exception lain di richsync | `null` | lanjut tanpa richsync |
| `F:43-46` | konversi richsync → TTML gagal | `null` | **turun ke subtitle** |
| `F:80-89` | konversi subtitle → LRC gagal | `null` | **turun ke plain** |
| `MusixmatchTtml:116` | semua baris ditolak penjaga | `""` | turun ke subtitle |

**Pola yang sudah benar:** richsync terpisah menelan kegagalan biasa tapi **melempar terus**
`TokenInvalid`, `RateLimited`, `CancellationException` (`M:305-312`). Itulah model yang akan
digeneralisasi ke tangga jemputan.

**Yang belum decided:** `MM:43`. Kegagalan decode terjadi **sebelum** jenis error apa pun ada,
jadi tangga §3 akan melihatnya sebagai "tidak ada" dan menjemput ulang. (Lihat §6.4.)

---

## §3. Pembatalan — ditangani eksplisit di setiap titik

`CancellationException` **selalu dilempar terus**, tidak pernah ditelan:

```
M:305   M:370   M:435   M:451
S:131   S:156
T:16    T:58
```

Satu tempat khusus: `M:451-454`. Bila `callback` melempar `CancellationException` (konsumen berhenti),
kita memanggil `searchScope.cancel(e)` lalu melempar — itu yang menghentikan **kandidat lain** yang
sedang berjalan.

Pemeriksaan tambahan: `ensureActive()` dipanggil di `M:412`, `M:416`, `M:445`, `M:449`, `M:402`.

---

## §4. Tujuan akhir di sisi app

### §4.1 Playback

```
Result.failure → LyricsHelper.kt:360-364
    if (it is CancellationException) throw it
    reportException(it); null
```

Provider menyumbang **null** — dianggap tidak punya lirik. Provider lain tetap jalan.

### §4.2 Search

```
exception → LyricsHelper.kt:237-241
    CancellationException → throw
    Exception → reportException
```

### §4.3 Timeout

`withTimeoutOrNull(8000)` → null → `logTimeout(provider)`. Bukan error, hanya catatan.

**Jadi dari sudut app, semua kegagalan kita berakhir sama: provider ini tidak menyumbang apa-apa.**
Perbedaan jenis error hanya berpengaruh di **dalam** module — menentukan apakah boleh lanjut atau harus berhenti.

---

## §5. Matriks ringkas

Baris = jenis error. Kolom = tempat ditangkap. Isi = aksi.

| | `getLyrics` utama | fallback playback `S:131-139` | fan-out search `M:435-443` | title-fallback `S:156-163` | richsync `M:305-312` |
|---|---|---|---|---|---|
| `NoMatch` | **picu fallback** | catat, lanjut | lewati | null | — |
| `Mismatch` | **picu fallback** | catat, lanjut | lewati | null | — |
| `Restricted` | **gagal total** | catat, lanjut | lewati | null | — |
| `ServerError` | gagal total | catat, lanjut | lewati | null | **null** |
| `MalformedResponse` | gagal total | catat, lanjut | lewati | null | **null** |
| `TokenInvalid` | perbarui sekali (T) | **lempar** | **lempar** | **lempar** | **lempar** |
| `RateLimited` | **berhenti total** | **lempar** | **lempar** | **lempar** | **lempar** |
| `CancellationException` | **lempar** | **lempar** | **lempar** | **lempar** | **lempar** |

---

## §6. Lima temuan untuk refactoring

### §6.1 ⚠️ JEBAKAN: 401 pada `token.get` ≠ 401 pada endpoint lain

```
token.get      401 → RateLimited   (M:130, M:152)
endpoint lain  401 → TokenInvalid  (M:215, M:232, M:295, M:301)
```

Ini **sengaja dan benar** — kita pernah mengukur bahwa `token.get` dua kali berurutan menghasilkan
401 dengan `hint=captcha`, yaitu throttling, bukan tiket rusak.

**Bila tangga status disatukan tanpa menjaga kasus ini**, 401 pada `token.get` akan jadi `TokenInvalid`
→ memicu pembaruan tiket → memanggil `token.get` lagi → 401 lagi. Loop yang memperburuk throttle.

**Wajib:** penyatuan tangga status (langkah migrasi 5) harus mempertahankan pengecualian ini.

**Kabar baik — sudah terkunci dua test** (diperiksa 7 Nov 2026):

| test | mengunci apa |
|---|---|
| `MusixmatchApiTest.kt:202` `testTokenGet_status200_bodyStatus401_detectedAsRateLimited` | HTTP 200 + status body 401 → `RateLimited` |
| `MusixmatchApiTest.kt:219` `testTokenGet_401_rateLimited_noRetry` | HTTP 401 → `RateLimited` **dan tepat 1 request** (tidak ada percobaan ulang) |

Jadi jebakan ini tidak akan lolos tanpa terdeteksi. Cukup jangan hapus kedua test itu saat merapikan suite.

### §6.2 `Restricted` tidak memicu fallback — SUDAH DIPUTUSKAN, dan alasannya benar

**Apa itu `restricted`:** flag pada payload **lirik polos** (`MacroModels.kt:107`). Dari probe kita,
ia berada di tengah kelompok metadata hak cipta:

```
lyrics_copyright · publisher_list · writer_list · locked · published_status · verified · restricted
```

Jadi `restricted=1` berarti **pemegang hak membatasi konten lirik ini** — bukan kegagalan teknis.

**Catatan cakupan:** flag ini hanya ada di `LyricsData`. `RichSyncData` dan `SubtitleData` **tidak punya**
field `restricted`. Kita memakai flag dari lirik polos untuk menolak **ketiga** format. Itu pilihan
konservatif yang benar: pembatasan dari pemegang hak dihormati lintas format.

**Keputusan yang sudah tercatat** (`Musixmatch-Hybrid-Decisions.md:94`):

> `Restricted` dari **matcher utama** tetap terminal — tidak boleh fallback. Fallback hanya berjalan
> setelah `NoMatch`/`Mismatch`, yaitu ketika tidak ada track yang cocok, sehingga tidak ada pembatasan
> yang dilewati. Di dalam fallback, restricted bersifat per-rekaman.

**Alasannya prinsipil:** fallback ada untuk menemukan lagu ketika tidak ada yang cocok. Kalau lagu
**sudah** cocok tapi liriknya dibatasi, mencari versi lain lewat fallback berarti **mengakali pembatasan
itu**. Menolak fallback = menghormati pembatasan.

Di dalam loop (fallback/search), restricted bersifat **per-rekaman**: lewati kandidat itu, coba yang lain.
Itu berbeda karena kita sedang menawarkan rekaman berbeda yang status haknya memang berbeda — bukan
mengakali pembatasan pada satu rekaman yang sudah cocok.

**Koreksi atas dokumen ini sebelumnya:** saya sempat menyajikan ini sebagai "mungkin terlewat" dan
mengusulkan fallback bisa mencari versi yang tidak dibatasi. Usulan itu justru bertentangan dengan
alasan keputusan di atas. **Tidak ada yang perlu diubah di sini.**

### §6.3 `NoMatch` dan `Mismatch` tidak pernah ditangani berbeda

Di **semua** penangkap, keduanya menghasilkan aksi identik. Pembedaan tiposnya murni untuk diagnosis
(pesan log). Boleh digabung tanpa mengubah perilaku — tapi mempertahankan keduanya membantu membaca log.

### §6.4 `MM:43` — DIPUTUSKAN: biarkan seperti sekarang

**Keputusan user (7 Nov 2026): biarkan.** Payload yang gagal di-decode tetap terlihat sebagai
"tidak ada", sehingga tangga jemputan akan menjemput ulang.

Alasan yang diterima: menjemput ulang payload rusak berpeluang memperbaiki hasil, dan biayanya hanya
satu request tambahan pada kasus yang jarang. Tidak ada perubahan kode di `MM:43`, dan tidak ada
test baru yang diperlukan untuk ini.

**Konsekuensi untuk tangga §3:** anak tangga tidak boleh mengasumsikan "null berarti server tidak punya".
Null bisa berarti "ada tapi rusak". Itu diterima secara sadar.

Ini juga menjawab **§9.3 di `Musixmatch-Refactor-Plan.md`**.

### §6.5 Tiga pemeriksaan duplikat yang akan hilang sendiri

| duplikat | lokasi |
|---|---|
| `restricted` | `M:330` dan `F:31` |
| richsync inline | `M:333-335` dan `F:37-40` |
| `instrumental` | `M:332` dan `F:33` |

Ketiganya hilang bila `resolveTrackLyrics` dihapus (langkah migrasi 2). Tidak perlu ditangani terpisah.

---

## §7. Yang harus dikunci dengan test sebelum langkah 1

Supaya klaim "tanpa perubahan perilaku" bisa dibuktikan:

- [ ] 401 pada `token.get` → `RateLimited`, **bukan** `TokenInvalid` (§6.1)
- [ ] 401 pada endpoint lain → `TokenInvalid` → perbarui sekali → berhasil
- [ ] `TokenInvalid` kedua setelah perbaruan → terminal, tidak ada request ketiga
- [ ] `RateLimited` di kandidat pertama → kandidat lain tidak mengirim request
- [ ] `Restricted` di playback utama → tidak ada fallback (§6.2, apa pun keputusannya)
- [ ] richsync gagal biasa → null, kandidat tetap lanjut
- [ ] richsync `RateLimited` → seluruh operasi berhenti
- [ ] `CancellationException` dari callback → kandidat lain dibatalkan
- [ ] `CancellationException` tidak pernah berubah jadi `Result.failure`

**Status pemeriksaan (7 Nov 2026):** §6.1 **sudah terkunci** oleh `MusixmatchApiTest.kt:202` dan `:219`.
Butir lain juga sudah punya test yang cocok:

| butir | test yang ada |
|---|---|
| 401 endpoint lain → perbarui sekali | `MusixmatchApiTest.kt:280` `testMacro_401_renewsTokenOnceAndRetriesOnce` |
| perbaruan kedua → terminal | `MusixmatchSearchConcurrencyTest.kt:340`, `:485` |
| `RateLimited` menghentikan saudara | `MusixmatchSearchConcurrencyTest.kt:535`, `:538` |
| richsync gagal biasa → lanjut | `MusixmatchRichsyncSearchTest.kt` (suite) |
| richsync `RateLimited` → berhenti | `MusixmatchRichsyncSearchTest.kt:123` `standaloneRateLimitIsTerminal` |
| `Restricted` tanpa fallback | `MusixmatchHybridTest.kt:186` `primarySuccessRestrictedServerMalformedAndRateLimitDoNotSearch` |
| renewal gagal menghentikan search | `MusixmatchHybridTest.kt:197`, `MusixmatchSearchConcurrencyTest.kt:394` |

**Jadi dasar untuk langkah 1 sudah ada.** Dua keputusan desain yang sempat menggantung kini selesai:

| pertanyaan | keputusan |
|---|---|
| §6.2 `Restricted` perlu fallback? | **Tidak** — sudah diputuskan sebelumnya, alasannya prinsipil. Tidak ada perubahan. |
| §6.4 payload rusak dijemput ulang? | **Ya, biarkan** — keputusan user 7 Nov 2026. Tidak ada perubahan. |

**Artinya langkah 1 dan 2 benar-benar murni "tanpa perubahan perilaku".** Tidak ada keputusan yang
tersisa yang bisa menyelundup ke dalamnya.
