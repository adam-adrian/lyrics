# Pengukuran Langsung API Musixmatch dari Sandbox

**Tanggal:** 6 Nov 2026
**Script:** `tools/mxm-measure.py` (bisa dijalankan ulang)
**Sifat:** pengukuran terbatas, **bukan** stress test

---

## 0. Batas yang sengaja dijaga

Aku **tidak** memburu ambang 429. Alasannya:

- Kita mengakses API ini di area ToS abu-abu; mendorong proteksi sampai jebol adalah perilaku yang berbeda dari pemakaian wajar
- IP sandbox bisa ditandai, sehingga probe berikutnya justru gagal dan kita kehilangan kemampuan mengukur
- Ambang batasnya tidak perlu diketahui untuk mengambil keputusan desain. Yang perlu diketahui: **apakah pemakaian wajar aman**

Volume yang dijalankan: **13 request dalam ~7 detik**, setara ~3 pencarian manual. Script berhenti pada tanda throttle pertama (401/429), bukan mendorong lebih jauh.

---

## 1. Hasil mentah

| | Search | Fanout (3 kandidat, concurrency 2) | Hasil pertama setelah search |
|---|---|---|---|
| S1 — Levitating / Dua Lipa (dingin) | 371 ms | 830 ms | 381 ms |
| S2 — Bohemian Rhapsody / Queen (hangat) | 333 ms | 769 ms | 376 ms |
| S3 — Levitating / Dua Lipa (ulang S1) | 321 ms | 790 ms | 353 ms |

```
token.get        : 1.067 ms  (1 kali, dipakai ulang untuk semua 12 request berikutnya)
total request    : 13
token request    : 1
throttle         : tidak ada
matcher track_id : 9/9 kandidat cocok dengan yang diminta
```

---

## 2. Yang terjawab

### 2.1 Pemakaian wajar tidak mengalami degradasi

| | S1 | S2 | S3 |
|---|---|---|---|
| Search | 371 ms | 333 ms | 321 ms |
| Fanout | 830 ms | 769 ms | 790 ms |

Tidak ada tren memburuk — malah sedikit membaik, kemungkinan karena koneksi TLS sudah hangat. **Concurrency 2 dengan 3 kandidat aman** pada volume ini.

### 2.2 Satu token cukup untuk banyak request

Satu `token.get` melayani 12 request berikutnya lintas 3 pencarian berbeda tanpa penolakan. Ini menguatkan bahwa cache token masuk akal, dan bahwa koreksi implementer sebelumnya benar: `401` terdahulu bukan bukti token cepat mati.

**Batas:** ini hanya membuktikan token bertahan ~7 detik dan 12 panggilan. **Bukan** bukti masa hidup token sebenarnya.

### 2.3 `track_id` dihormati, terkonfirmasi kedua kali

9 dari 9 kandidat menghasilkan `matcher.track.get` dengan `track_id` yang sama dengan yang diminta. Konfirmasi independen terhadap probe implementer.

### 2.4 Hangat vs dingin — sekarang terukur, bukan estimasi

| | Dingin | Hangat | Rasio |
|---|---|---|---|
| Hasil pertama | **1.819 ms** | **692 ms** | **2,6×** |
| Total | 2.268 ms | 1.106 ms | 2,1× |

Ini menggantikan estimasi pengurangan di review sebelumnya dengan angka yang benar-benar diukur — meskipun dari lingkungan yang berbeda.

**Estimasiku sebelumnya (3,7×) terlalu optimis untuk lingkungan ini.** Terukur 2,6×.

---

## 3. Temuan yang paling berguna: manfaat warm-up justru lebih besar di jaringan lambat

Bandingkan porsi token terhadap total waktu ke hasil pertama:

| Lingkungan | token.get | Hasil pertama dingin | Porsi token |
|---|---|---|---|
| Mesin implementer | 3.451 ms | 4.746 ms | **73%** |
| Sandbox (datacenter) | 1.067 ms | 1.819 ms | **59%** |

Sandbox **2,6× lebih cepat** dari mesin implementer. Karena porsi token lebih besar di lingkungan yang lebih lambat, maka:

**Semakin lambat jaringan, semakin besar manfaat warm-up.**

Jadi angka sandbox yang tampak "cuma" 2,6× adalah **batas bawah**. Di perangkat user dengan data seluler, manfaatnya kemungkinan lebih besar — bukan lebih kecil. Ini memperkuat rekomendasi warm-up, walaupun tetap harus diukur di perangkat nyata.

---

## 4. Temuan sampingan: 3 kandidat ≠ 3 lagu word-synced

Format yang tersedia per kandidat:

| Pencarian | Kandidat 1 | Kandidat 2 | Kandidat 3 |
|---|---|---|---|
| S2 (Bohemian Rhapsody) | richsync (51 baris) | plain | plain |
| S3 (Levitating) | richsync (74 baris) | plain | richsync (90 baris) |

Dari 3 kandidat, biasanya hanya **1–2 yang punya richsync**; sisanya plain. Jadi user akan melihat daftar dengan kualitas beragam — itu wajar dan justru berguna (bisa pilih versi yang word-synced), tapi jangan dijanjikan sebagai "3 hasil karaoke".

Jumlah baris yang dilaporkan sudah memakai guard `MIN_SPAN` yang sama dengan konverter produksi, jadi angka ini mencerminkan apa yang benar-benar akan dirender.

---

## 5. Yang TIDAK terjawab, dan tidak akan dijawab dengan cara ini

| Pertanyaan | Status |
|---|---|
| Ambang 429 sebenarnya | **Sengaja tidak dicari** |
| Masa hidup token | Hanya terbukti ~7 detik / 12 panggilan |
| Perilaku di jaringan seluler | Tidak bisa diukur dari datacenter |
| Sesi panjang / puluhan pencarian | Belum diuji |
| Apakah IP datacenter diperlakukan berbeda | Tidak diketahui — bisa lebih longgar atau lebih ketat dari IP residensial |

Poin terakhir penting: **angka absolut dari sini tidak boleh dipakai sebagai janji performa.** Yang bisa dipindahkan adalah kesimpulan *relatif* — tidak ada degradasi pada volume wajar, token bisa dipakai ulang, dan hangat lebih cepat dari dingin.

---

## 6. Rekomendasi yang diperbarui

1. **Concurrency 2, cap 3 kandidat** — sudah terbukti aman pada volume wajar. Tidak perlu diturunkan lagi.
2. **Warm-up tetap layak**, dan manfaatnya kemungkinan lebih besar di perangkat nyata daripada yang terlihat di sandbox (§3). Tapi tetap ukur di perangkat sebelum mengklaim angka.
3. **Jangan naikkan concurrency** tanpa pengukuran di perangkat nyata. Sandbox yang lebih cepat bukan bukti bahwa perangkat user aman.
4. **Jangan cari ambang rate limit.** Yang kita butuh sudah terjawab: pemakaian wajar tidak kena throttle. Mengetahui plafonnya tidak mengubah desain, dan mencarinya berisiko merusak akses kita sendiri.
5. **Untuk deskripsi PR**, gunakan kalimat seperti: "pada pengujian terbatas (13 request, 3 pencarian berurutan, satu token), tidak terjadi throttle dan semua kandidat cocok dengan ID yang diminta" — bukan klaim tentang batas rate limit.

---

## 7. Cara menjalankan ulang

```sh
python3 tools/mxm-measure.py
```

Script membatasi diri pada 30 request, berhenti pada 401/429 pertama, dan tidak mencetak token, URL lengkap, response body, atau lirik. Kalau ingin menguji lagu lain, ubah daftar `plan` di dalam script.

---

## 8. Koreksi setelah tanggapan implementer

Implementer menolak empat kesimpulan. **Keempatnya benar.** Selain itu, saat menelaah ulang script untuk menjawabnya, aku menemukan **bug di alat ukurku sendiri** yang membatalkan salah satu temuan di §4.

### 8.1 §3 salah arah, bukan sekadar belum terbukti

Klaimku: "semakin lambat jaringan, semakin besar manfaat warm-up; 2,6× adalah batas bawah."

Dua kesalahan:

**(a) Inferensi dari n=2 lingkungan tanpa kontrol.** Mesin implementer dan sandbox berbeda dalam routing, TLS, kondisi server sesaat, dan geografi. Selisih porsi token (73% vs 59%) bisa berasal dari mana saja. Aku menyimpulkan sebab dari dua titik yang tidak dikendalikan.

**(b) Untuk pemicu menu, arah klaimku justru terbalik.** Manfaat warm-up dibatasi waktu persiapan, bukan durasi token:

```
manfaat = min(lead_time, token_time)
```

Dengan lead time tetap 1 detik:

| Jaringan | token_time | Terhemat | Porsi token terhemat | Sisa tunggu |
|---|---|---|---|---|
| Cepat | 1,07 s | 1,0 s | **93%** | 0,07 s |
| Lambat | 3,45 s | 1,0 s | **29%** | 2,45 s |

Jadi di jaringan lambat, warm-up dengan lead time pendek justru **menghemat porsi yang lebih kecil** dan menyisakan tunggu yang lebih lama. Bukan "manfaat lebih besar".

**Implikasi yang jujur:** manfaat penuh hanya tercapai bila `lead_time >= token_time`. Itu berarti pemicu yang **lebih awal** — dan aku sebelumnya menolak `bind()` dan app start. Penolakan itu berbasis biaya, bukan manfaat; kalau pengukuran di app menunjukkan lead time menu tidak cukup, kalkulasi biayanya berubah dan pemicu lebih awal jadi layak dipertimbangkan lagi. Aku tidak menarik keputusan itu sekarang, tapi juga tidak lagi menganggapnya selesai.

### 8.2 Rasio 2,6× tidak mengukur manfaat implementasi warm-up

S1 dan S2 mencari lagu berbeda. S3 mengulang S1, tapi tetap mencampur dua efek: cache token **dan** koneksi yang sudah terbentuk (TLS, pool HTTP, DNS).

Aku sendiri menulis "kemungkinan karena koneksi TLS sudah hangat" untuk menjelaskan waktu search yang membaik — lalu tetap menyajikan 2,6× sebagai manfaat warm-up. Itu tidak konsisten.

Yang benar: **2,6× adalah selisih dingin-vs-hangat pada proses yang sama, bukan manfaat pemicu `LyricsMenuViewModel.init`.** Manfaat pemicu hanya bisa diukur dengan A/B di app.

### 8.3 "Mencerminkan apa yang benar-benar akan dirender" — ditarik

Pengukuran ini tidak menjalankan konverter Kotlin, `TTMLParser`, `LyricsUtils.parseTtml`, `PrepareLyricsUseCase`, maupun renderer. Ia hanya menyaring **input** dengan satu guard yang menyerupai `MIN_SPAN`.

Frasa yang tepat: **"perkiraan kelayakan input"**, bukan verifikasi render.

### 8.4 `401` bukan `429`

Aku menyebut keduanya "throttle". Hanya `429` yang secara eksplisit berarti rate limit. `401` adalah kegagalan autentikasi — bisa token kedaluwarsa, captcha, `app_id` ditolak, atau sebab lain. Penyebab `401` pada probe terdahulu masih belum diketahui.

Berhenti pada keduanya tetap aturan konservatif yang wajar untuk script, tapi **labelnya** harus dibedakan. Dalam pengukuran ini keduanya tidak terjadi, jadi ini soal interpretasi, bukan data.

---

## 9. Bug di alat ukurku sendiri — dan data yang dikoreksi

### 9.1 Bug

`usable_lines()` diterapkan ke `richsync_body` **dan** `subtitle_body` dengan guard yang sama:

```python
(ln.get("te", 0) - ln.get("ts", 0)) >= 0.002
```

Padahal bentuk subtitle adalah `{"text": ..., "time": {"total": ...}}` — **tidak punya `ts`/`te`**. Hasilnya `0 - 0 = 0`, gagal guard, sehingga **setiap baris subtitle terbuang**.

Akibat:
- kolom `subtitleLines` di §1 dan §4 **selalu 0**, bukan karena tidak ada subtitle
- `format` tidak pernah melaporkan `"subtitle"`, jatuh ke `"plain"`

Jadi §4 menyatakan coverage lebih buruk daripada kenyataan.

### 9.2 Verifikasi setelah perbaikan

Query yang sama (Bohemian Rhapsody / Queen), 5 request, tanpa throttle:

| Kandidat | Laporan lama (buggy) | Laporan benar |
|---|---|---|
| 1 | richsync, 51 baris | richsync 51 + subtitle 51 |
| 2 | **plain** | **subtitle, 54 baris** |
| 3 | plain | richsync 57 + subtitle 57 |

Dua koreksi penting:

- Kandidat 2 **bukan plain** — ia punya 54 baris line-synced. Ini murni akibat bug.
- Musixmatch sering mengembalikan **richsync dan subtitle sekaligus** untuk track yang sama (51/51, 57/57). Laporan lama menyembunyikan ini sepenuhnya.

**§4 dengan ini ditarik.** Klaim "dari 3 kandidat hanya 1–2 richsync, sisanya plain" tidak berdasar. Pada verifikasi ini, **ketiga kandidat punya lirik synced** (2 richsync + 1 subtitle), nol plain.

Catatan: kandidat 3 berbeda antara dua run, dan urutan hasil `track.search` bisa bervariasi — jadi perbedaan itu tidak bisa seluruhnya diatribusikan ke bug. Yang pasti teratribusikan adalah kandidat 2 dan kolom `subtitleLines`.

Ini tetap satu query, bukan statistik. Tapi arahnya jelas: laporanku sebelumnya **meremehkan** coverage karena alatnya rusak.

### 9.3 Perbaikan lain pada script

`first_ms` dihitung dengan pola check-then-set di dalam thread tanpa lock — dua worker bisa sama-sama melihat `None`. Sudah ditambah `threading.Lock`. Dampaknya kecil (paling buruk mencatat waktu finisher kedua), tapi tetap cacat yang perlu dibetulkan.

Script yang sudah diperbaiki: `tools/mxm-measure.py`, dan salinannya di `docs/musixmatch-pr/mxm-measure.py`.

---

## 10. Yang masih bisa dipegang setelah semua koreksi

Terbatas pada volume dan lingkungan yang diuji:

- Satu token melayani 12 request berikutnya lintas 3 pencarian — **reuse token terbukti**
- Tidak ada `401` maupun `429` selama 13 request itu. Frasa yang tepat: **"tidak mengalami throttle pada pengujian terbatas ini"**, bukan "aman"
- 9/9 kandidat mengembalikan matcher dengan ID sesuai permintaan — **`track_id` dihormati**
- Tersedia campuran richsync, subtitle, dan plain — **fallback format per kandidat wajib dipertahankan**
- Tidak ada tren memburuk antar tiga pencarian berurutan

Cukup untuk melanjutkan **cap 3 kandidat, concurrency 2, callback inkremental**.

## 11. Yang tidak boleh dikutip

- Angka latensi absolut sebagai janji performa
- Rasio 2,6× sebagai manfaat warm-up
- Klaim "makin lambat makin untung"
- Jumlah baris sebagai bukti render produksi
- `401` sebagai bukti rate limit
- Distribusi format di §4 (sudah ditarik, lihat §9)

## 12. Rencana yang disepakati

Sejalan dengan implementer:

1. **Multi-kandidat dilanjutkan** — 3 kandidat, 2 fetch aktif, callback inkremental, tanpa guard teks di mode search
2. **Warm-up tetap eksperimen terpisah** — manfaat pemicu menu belum terukur, dan §8.1 menunjukkan manfaatnya mungkin terbatas
3. **Pengukuran warm-up dilakukan di app**, bukan disimpulkan dari probe jaringan
4. **Script-ku belum direview implementer.** Aku menemukan dua cacat sendiri (§9); kemungkinan masih ada yang lain. Review tetap diperlukan sebelum angka dari script ini dipakai lagi
