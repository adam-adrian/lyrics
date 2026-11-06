# Kenapa Musixmatch Hanya Satu Hasil + Wiring Lengkap ArchiveTune → Module

**Tanggal:** 5 Nov 2026
**Diverifikasi terhadap:** `ArchiveTune` dev `91a98f2`, `adam-adrian/lyrics` `ed10b0b`

---

# BAGIAN 1 — Kenapa Musixmatch selalu satu hasil

## 1.1 Jawaban singkat

Karena `Musixmatch.getAllLyrics()` di module hanya membungkus `getLyrics()`:

```kotlin
// musixmatch/.../Musixmatch.kt — baris terakhir
suspend fun getAllLyrics(..., callback: (String) -> Unit) {
    getLyrics(title, artist, album, durationSeconds).onSuccess(callback)
}
```

`onSuccess(callback)` memanggil callback **paling banyak satu kali**. Tidak ada pencarian, tidak ada daftar kandidat.

KuGou melakukan hal yang sama sekali berbeda.

## 1.2 Kontraknya: default implementation

`LyricsProvider.kt` memberi default yang tinggal dipakai:

```kotlin
suspend fun getAllLyrics(
    id: String, title: String, artist: String, album: String?, duration: Int,
    callback: (String) -> Unit,
) {
    getLyrics(id, title, artist, album, duration).onSuccess(callback)
}
```

Jadi **setiap provider yang tidak meng-override otomatis hanya menghasilkan satu hasil.** Itu bukan bug, itu perilaku default kontrak.

## 1.3 KuGou meng-override dengan pencarian nyata

Wrapper app:
```kotlin
// KuGouLyricsProvider.kt
override suspend fun getAllLyrics(..., callback: (String) -> Unit) {
    KuGou.getAllPossibleLyricsOptions(title, artist, duration, callback)
}
```

Module:
```kotlin
// KuGou.kt:86-113
suspend fun getAllPossibleLyricsOptions(title, artist, duration, callback) {
    val keyword = generateKeyword(title, artist)
    searchSongs(keyword).data.info.forEach {              // ← LOOP hasil pencarian
        if (duration == -1 || abs(it.duration - duration) <= DURATION_TOLERANCE) {
            searchLyricsByHash(it.hash).candidates.firstOrNull()?.let { candidate ->
                Base64.Default.decode(downloadLyrics(...).content)
                    .decodeToString().normalize()
                    .let(callback)                        // ← callback per kandidat
            }
        }
    }
    searchLyricsByKeyword(keyword, duration).candidates.forEach { candidate ->
        ... .let(callback)                                // ← loop kedua
    }
}
```

Dua loop, masing-masing memanggil `callback` untuk setiap kandidat. Itulah sumber banyak hasil.

## 1.4 Perbandingan

| | KuGou | Musixmatch (MVP) |
|---|---|---|
| Endpoint pencarian | `search/song` + `searchLyricsByHash` + `searchLyricsByKeyword` | **tidak ada** |
| Jumlah panggilan `callback` | sebanyak kandidat yang lolos filter | maksimal 1 |
| Sumber hasil | daftar lagu hasil pencarian | satu hasil `matcher.track.get` terbaik |
| Filter durasi | per kandidat, `DURATION_TOLERANCE` | satu kali, sebagai guard penolakan |

Musixmatch memakai `macro.subtitles.get` dengan `q_track`/`q_artist`, yang dirancang mengembalikan **satu kecocokan terbaik** — bukan daftar. Jadi bukan sekadar kurang loop; **endpoint yang dipakai memang tidak menghasilkan daftar.**

## 1.5 Koreksi terhadap spec-ku

Spec §8 menunda "`getAllLyrics` multi-format". **Itu framing yang keliru.** Yang kutunda adalah memancarkan TTML + LRC + plain dari **lagu yang sama**. Yang kamu butuhkan — dan yang KuGou lakukan — adalah **banyak kandidat lagu berbeda**.

Dua fitur yang berbeda. Yang kedua tidak pernah masuk spec secara eksplisit, jadi wajar implementer tidak membuatnya. Itu kekuranganku, bukan kekurangannya.

---

## 1.6 Rancangan perbaikan

### Langkah

```
1. track.search  → daftar lagu
   q_track, q_artist, page_size, f_has_lyrics=1
   → message.body.track_list[].track

2. filter kandidat
   - buang yang instrumental
   - toleransi durasi (kecuali duration == -1)
   - batasi jumlah, misal 5 teratas

3. untuk SETIAP kandidat:
   macro.subtitles.get dengan track_id=<id>   (bukan q_track/q_artist)
   → richsync / subtitle / plain, seperti alur sekarang

4. callback(ttml_atau_lrc_atau_plain) segera setelah tiap kandidat selesai
```

### Kendala keras: anggaran 8 detik

`LyricsHelper` membungkus **seluruh** `provider.getAllLyrics()` dengan:

```kotlin
withTimeoutOrNull(PROVIDER_TIMEOUT_MS)   // 8_000 ms
```

Satu `track.search` + 5 `macro.subtitles.get` berurutan bisa mendekati atau melewati batas itu, terutama saat jaringan lambat.

**Karena itu: pancarkan hasil secara inkremental, jangan dikumpulkan dulu.** Hasil yang sudah masuk channel sebelum timeout **tetap dipakai** — consumer berjalan paralel dan `channel.trySend` terjadi di dalam callback. Jadi kalau hanya 3 dari 5 kandidat selesai dalam 8 detik, user tetap melihat 3 hasil.

Ini juga pola yang dipakai KuGou: `forEach` + `callback` langsung, bukan `map` lalu return list.

### Saran konkret

- Batasi **5 kandidat**, jangan lebih
- Jalankan 2–3 fetch kandidat secara paralel (bukan 5 sekaligus — Musixmatch punya rate limit)
- Pancarkan segera, jangan tunggu semua
- Pertahankan guard durasi sebagai **filter**, bukan penolakan global
- Hormati `ensureActive()` di dalam loop supaya pembatalan search responsif

### Pertanyaan awal sebelum smoke test

Apakah `macro.subtitles.get` menerima `track_id` sebagai pengganti `q_track`/`q_artist`? Hasil pengujian di bawah sekarang mengonfirmasi dukungan itu untuk lima kandidat Levitating.

### Hasil smoke test live (6 Nov 2026)

#### `track.search`

Request dengan `q_track=Levitating`, `q_artist=Dua Lipa`, `q_artists=Dua Lipa`, `page_size=5`, dan `f_has_lyrics=1` berhasil:

- HTTP `200`, `message.header.status_code` `200`.
- `message.body.track_list` berupa array; jumlah kandidat yang dikembalikan: 5.
- Setiap item membungkus objek lagu di `track_list[].track`.
- Data kandidat yang terlihat meliputi `track_id`, `commontrack_id`, `track_name`, `artist_name`, `track_length`, `instrumental`, `has_lyrics`, `has_subtitles`, dan `has_richsync`.
- Hasil contoh mencakup versi studio (204 detik), versi feat. DaBaby (203 detik), live (258 dan 265 detik), serta remix (251 detik). Ini menunjukkan endpoint memang menyediakan kandidat berbeda, bukan satu match saja.

#### Pengambilan macro dengan ID

Sebelum probe `track.search`, `macro.subtitles.get` diuji dengan `track_id=472658144` untuk track Levitating versi studio. Responsnya HTTP/status `200`, matcher mengembalikan `track_id` yang sama, dan richsync tersedia. Request serupa dengan `commontrack_id=125324377` juga berhasil.

Probe awal lanjutan sempat berhenti karena `token.get` mengembalikan HTTP `200` dengan payload `status_code=401`. Probe itu meminta token baru, bukan memakai ulang token dari probe search sebelumnya. Penyebab penolakan server belum diketahui.

Pengujian ulang memakai satu token untuk search dan kelima macro berhasil. Semua matcher mengembalikan `track_id` yang diminta. Empat kandidat punya richsync; versi Live From Mexico mendapat richsync `404` tetapi subtitle `200`.

#### Script otomatis dan pengukuran waktu

Jalankan dari root repo dengan Bun atau Node 22+:

```sh
bun docs/musixmatch-pr/probe-search.mjs
```

Argumen opsional: judul, artis, durasi detik. Contoh `bun docs/musixmatch-pr/probe-search.mjs "Levitating" "Dua Lipa" 204` menerapkan filter toleransi durasi 8 detik. Default durasi `-1` melewati filter tersebut, sehingga lima versi berbeda dapat diuji.

Script meminta token sekali, menyimpannya di memori, mencari maksimal lima kandidat, memfilter instrumental/durasi, lalu mengambil macro dengan dua worker. Deadline total 8 detik mencakup token, search, dan seluruh fetch. Request yang masih aktif dibatalkan saat deadline lewat. Tidak ada retry token otomatis. Output JSON memuat metadata, status, timing, dan format yang tersedia; token, URL lengkap, response body, dan lirik tidak dicetak atau disimpan.

Run otomatis pada 6 Nov 2026, durasi input `-1`:

- Satu request token selesai dalam 3.451 ms; search dalam 770 ms.
- Kelima kandidat lolos filter dan berhasil diambil dengan ID yang cocok.
- Hasil pertama tersedia pada 4.746 ms; seluruh proses selesai pada 6.357 ms.
- Empat kandidat punya richsync, satu hanya subtitle; deadline tidak terlewati.
- Exit code `0`; `allEligibleSucceeded=true`.

Ini satu sampel jaringan live. Script memeriksa payload dan format tersedia, belum mengonversi lewat kode Kotlin atau mengirim callback ke app. Deadline ada di script, tetapi jalur pembatalan deadline belum terpicu pada run ini. Belum ada jaminan semua pencarian selesai dalam 8 detik. Implementasi module tetap perlu emit inkremental dan menguji cancellation serta hasil parsial.

Catat hasilnya di `implementer-notes.md`.

---

# BAGIAN 2 — Wiring lengkap ArchiveTune → module lyrics

## 2.1 Lapisan build (statis)

```
ArchiveTune/settings.gradle.kts
    include(":lyrics:musixmatch")          ← subproject, BUKAN includeBuild
        ↓
ArchiveTune/app/build.gradle.kts
    implementation(project(":lyrics:musixmatch"))
        ↓
lyrics/musixmatch/build.gradle.kts
    kotlin { jvmToolchain(21) }            ← JVM murni, tanpa Android
    implementation(libs.ktor.client.core)  ← 'libs' = catalog INDUK
```

**Konsekuensi penting:** karena `include` (bukan `includeBuild`), module memakai **version catalog repo induk**, bukan `lyrics/gradle/libs.versions.toml`. Catalog submodule hanya dipakai saat CI submodule berdiri sendiri. Setiap dependensi harus ada di **kedua** catalog.

Batas module: **tidak boleh ada import Android**, dan **tidak boleh ada dependensi lintas module** (mis. `musixmatch` → `betterlyrics`).

## 2.2 Lapisan runtime — pencarian manual

```
LyricsMenu / LyricsScreen  (UI)
    │  user menekan "search lyrics"
    ↓
LyricsMenuViewModel.searchLyrics()            :167
    │  state = Loading, job di Dispatchers.IO
    ↓
LyricsHelper.getAllLyrics(...)                :191
    │
    ├─ orderedProviders()                     :394
    │     baca DataStore: LyricsProviderOrderKey + flag enable
    │     deserializeLyricsProviderOrder() → urutan
    │     providerMap: enum → object provider
    │     gabung dengan baseProviders, filter yang disabled
    │
    ├─ cek cache (RequestKey termasuk daftar nama provider)
    │     TTL 120_000 ms → kalau ada, pancarkan ulang, selesai
    │
    ├─ cek jaringan → kalau offline, berhenti
    │
    └─ coroutineScope + Channel<IndexedLyrics>(UNLIMITED)
          │
          ├─ producer: supervisorScope
          │     providers.forEachIndexed { index, provider ->
          │         launch {                              ← PARALEL
          │             providerPermits.withPermit {      ← Semaphore(6)
          │                 withTimeoutOrNull(8_000) {
          │                     provider.getAllLyrics(...) { lyrics ->
          │                         channel.trySend(
          │                             IndexedLyrics(index,
          │                                 LyricsResult(provider.name, lyrics))
          │                         )
          │                     }
          │                 }
          │             }
          │         }
          │     }
          │
          └─ consumer: for (result in channel)
                normalisasi via LyricsUtils.lyricsOrNotFound()
                buang kalau LYRICS_NOT_FOUND
                buang duplikat (perbandingan LyricsResult)
                callback(entry.result)  →  kembali ke ViewModel
```

Di dalam `provider.getAllLyrics`, batas app/module dilintasi:

```
MusixmatchLyricsProvider.getAllLyrics()        ← app, boleh pakai Android
    │  (object : LyricsProvider)
    ↓
Musixmatch.getAllLyrics()                      ← module, JVM murni
    ↓
Musixmatch.getLyrics()
    ↓
getToken() → executeMacro() → fallback berjenjang
    ↓
HTTP ke apic-appmobile.musixmatch.com
```

Lalu hasil kembali ke atas:

```
ViewModel menerima LyricsResult(providerName, lyrics)
    ↓
.toUiModel(index)                              :403
    ├─ displayLyricsText(lyrics)      → preview
    ├─ hitung baris & karakter
    ├─ isTtml(lyrics)                 → parseTtml kalau ya
    ├─ LyricsUtils.hasWordSyncedLyrics(lyrics)
    └─ LyricsSearchResultUiModel(
           id = "${providerName}_${lyrics.hashCode()}_$index",
           providerName, lyrics, preview,
           lineCount, characterCount,
           isLineSynced, isWordSynced )
    ↓
state = Success(results, isSearching = true)   ← diperbarui tiap hasil masuk
    ↓
setelah semua provider selesai → isSearching = false
```

UI menampilkan badge word-synced / line-synced / plain per hasil, dengan nama provider sebagai label.

## 2.3 Lapisan runtime — pemutaran biasa (bukan pencarian)

Jalur berbeda, dan ini yang menjelaskan batasan "provider baru dicoba terakhir":

```
LyricsHelper.getLyrics(mediaMetadata)
    ↓
fetchPriorityLyrics(providers, request)        :281
    │  iterate providers SESUAI URUTAN
    │  each: providerPermits.withPermit { withTimeoutOrNull(8_000) { ... } }
    ↓
provider PERTAMA yang sukses → dipakai, sisanya tidak dipanggil
```

Jadi untuk pemutaran biasa, urutan menentukan segalanya. `MUSIXMATCH` ditambahkan di **ujung** urutan bagi user lama, sehingga hanya dipakai bila semua provider lain gagal.

Pencarian manual **tidak** berperilaku begitu — semua provider dipanggil paralel, jadi Musixmatch tetap muncul di daftar hasil meski urutannya terakhir.

## 2.4 Angka-angka yang menentukan desain

| Konstanta | Nilai | Lokasi | Implikasi |
|---|---|---|---|
| `MAX_CONCURRENT_PROVIDERS` | 6 | `LyricsHelper:471` | maksimal 6 provider jalan bersamaan |
| `PROVIDER_TIMEOUT_MS` | 8.000 ms | `:473` | anggaran **total** per provider, bukan per request |
| `SEARCH_CACHE_TTL_MS` | 120.000 ms | `:476` | hasil search di-cache 2 menit |

`RequestKey` menyertakan daftar nama provider, jadi **mengubah urutan atau toggle provider membuat cache search tidak berlaku** — pencarian berikutnya mengambil ulang.

## 2.5 Tanggung jawab tiap lapisan

| Lapisan | Boleh | Tidak boleh |
|---|---|---|
| Module (`lyrics/musixmatch`) | Ktor, kotlinx-serialization, logika parse | import Android, dependensi module lain |
| Wrapper (`MusixmatchLyricsProvider`) | Context, DataStore, GlobalLog | logika jaringan/parse |
| `LyricsHelper` | urutan, cache, timeout, concurrency, dedup | tahu detail API provider |
| ViewModel + UI | state, model tampilan | panggil module langsung |

Wrapper sengaja tipis — 59 baris, hanya meneruskan. Semua logika ada di module supaya bisa diuji di JVM tanpa Android.

---

## Ringkasan tindakan

1. **Penyebab satu hasil:** `Musixmatch.getAllLyrics` mendelegasi ke `getLyrics`. Bukan bug, tapi fitur yang belum dibuat — dan spec-ku salah membingkainya sebagai "multi-format".
2. **Untuk memperbaikinya:** perlu endpoint `track.search` + fetch lirik per kandidat, dipancarkan inkremental dalam anggaran 8 detik, maksimal ~5 kandidat.
3. **Verifikasi dulu** apakah `macro.subtitles.get` menerima `track_id`.
4. **Wiring** sudah benar dan tidak perlu diubah untuk fitur ini — cukup module dan sedikit di wrapper. Kontrak `getAllLyrics` sudah mendukung banyak hasil sejak awal.
