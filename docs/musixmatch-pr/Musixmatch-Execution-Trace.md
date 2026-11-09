# Jejak eksekusi Musixmatch — reverse-engineering dari kode

**Basis:** submodule `445dfff`, app `dev`. Semua nomor baris sudah dicocokkan.
**Isi:** permintaan app (§1-§3) · jawaban macro (§4-§5) · tiga format: TTML (§6-§10), LRC (§11), polos (§12) ·
perbandingan format (§13) · dua metode playback + fallback (§14) · **diagram gabungan seluruh alur (§15)** · inventaris panggilan jaringan (§16).

Dokumen ini disusun sebagai simulasi: apa yang ada di memori pada tiap langkah.

---

## §1. Bentuk permintaan dari app

Kontrak `lyrics/LyricsProvider.kt` — **lima nilai datar, tidak ada objek lagu**:

```kotlin
suspend fun getLyrics(id: String, title: String, artist: String, album: String?, duration: Int): Result<String>
suspend fun getAllLyrics(id, title, artist, album, duration, callback: (String) -> Unit)
```

### §1.1 Trace playback

```
LyricsScreen.kt:305  /  MusicService.kt:1421     lyricsHelper.getLyrics(mediaMetadata)
LyricsHelper.kt:128-135                          bangun RequestKey
LyricsHelper.kt:346                              provider.getLyrics(mediaId, title, artists, album, duration)
MusixmatchLyricsProvider                         buang id, teruskan 4 sisanya
Musixmatch.getLyrics(title, artist, album, durationSeconds)
```

| field | sumber |
|---|---|
| `mediaId` | `mediaMetadata.id` — id YouTube/MediaStore, **tidak berguna untuk Musixmatch** |
| `title` | `mediaMetadata.title` |
| `artists` | **`mediaMetadata.artists.joinToString { it.name }`** — sudah diratakan jadi satu string |
| `album` | `mediaMetadata.album?.title` |
| `duration` | `mediaMetadata.duration` — **detik**, `-1` = tidak tahu (`SongEntity.kt:31`) |
| `providers` | hanya untuk kunci cache, tidak dikirim ke provider |

### §1.2 Trace search

```
LyricsMenu.kt:309-315     viewModel.search(searchMediaMetadata.id,
                              titleField.text,     ← user ketik
                              artistField.text,    ← user ketik
                              searchMediaMetadata.album?.title,
                              searchMediaMetadata.duration)
LyricsMenuViewModel.search → lyricsHelper.getAllLyrics(...)
LyricsHelper.kt:228        provider.getAllLyrics(mediaId, songTitle, songArtists, songAlbum, duration) { ... }
```

**Catatan desain:** di search, `album` dan `duration` tetap dari lagu yang sedang diputar, sementara judul/artis dari input user. Kalau user mengganti ke lagu lain, filter durasi ±8 detik masih memakai durasi lagu lama. Keputusan "search = perbaiki lagu ini, atau cari lagu apa pun" belum pernah dinyatakan di kode.

### §1.3 Yang app TIDAK berikan

Bahasa target terjemahan, preferensi provider, posisi pemutaran, informasi percobaan ulang.

### §1.4 Batas waktu

`LyricsHelper.kt:345` → `withTimeoutOrNull(PROVIDER_TIMEOUT_MS)` = **8000 ms**, dipasang dari luar. Provider tidak diberi tahu dan tidak bisa menawar.

---

## §2. Sebelum menyentuh jaringan

Contoh: **Koiiro / もさを。**, album `null`, durasi 282.

```
Musixmatch.kt:342   getLyrics
  cleanTitle  = "Koiiro"
  cleanArtist = "もさを。"
  cleanAlbum  = ""            ← null jadi string kosong
  guard: judul/artis kosong → berhenti, nol request
```

### §2.1 Tiket diambil lebih dulu

`Musixmatch.kt:355`:

```kotlin
val tokenState = SearchTokenState(searchRequest { getToken() }, ::renewToken)
```

`getToken()` adalah argumen konstruktor → **dijalankan sebelum yang lain**. Jadi tiket diminta sebelum tahu lagunya ada.

`getToken()` (`:105`): cache ada → pakai (nol request); cache kosong → `tokenMutex.withLock { fetchToken() }`. Mutex memastikan hanya satu request tiket meski banyak pekerjaan bersamaan.

---

## §3. Dua request HTTP pertama

### §3.1 `token.get`

```http
GET https://apic-appmobile.musixmatch.com/ws/1.1/token.get?app_id=mac-ios-v2.0
```

Header dipasang sekali di `createHttpClient` (`:62`) lewat `defaultRequest`, dibawa **setiap** request:

```
Accept:            application/json
Accept-Language:   en-US,en;q=0.9
X-Cookie:          x-mxm-token-guid=
x-mxm-app-version: 10.1.1
X-User-Agent:      Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0
```

Klien dibuat sekali (`by lazy`, `:84`):

```
requestTimeoutMillis = 20000    connectTimeoutMillis = 15000    socketTimeoutMillis = 20000
expectSuccess = false           ← status diperiksa sendiri, bukan oleh Ktor
```

**Anggaran nyata 8 detik, bukan 20.** App membatalkan lebih dulu, jadi 20 detik tidak pernah tercapai.

Pemeriksaan jawaban tiket dua lapis (HTTP, lalu `message.header.status_code`), lalu tiga aturan tolak (`:160`): kosong, semua `'0'`, atau diawali `UpgradeOnly`.

### §3.2 `macro.subtitles.get`

`executeMacro` (`:239`) — inilah bentuk permintaan sebenarnya:

```http
GET .../macro.subtitles.get
    ?format=json
    &namespace=lyrics_richsynched
    &subtitle_format=mxm
    &app_id=mac-ios-v2.0
    &optional_calls=track.richsync
    &richsync_compact_type=words
    &part=track_lyrics_translation_status,track_structure,track_performer_tagging
    &q_track=Koiiro
    &q_artist=もさを。
    &q_artists=もさを。
    &usertoken=<tiket>
    &q_duration=282
    &f_subtitle_length=282
```

`q_album` tidak muncul karena album kosong. Dari lima nilai app, hanya **empat** yang sampai ke server; sisanya parameter tetap.

**Dua parameter sia-sia:** `part=...` terbukti tidak menghasilkan sub-call apa pun; `q_artists` identik dengan `q_artist`.

---

## §4. Bentuk jawaban macro — pola bersarang

Pola `message: { header, body }` muncul **dua kali**:

```
{ "message": {                               ← bungkus LUAR
    "header": { "status_code": 200 },         ← "permintaan macro diproses?"
    "body": { "macro_calls": {                ← kotak berisi 4 jawaban
        "matcher.track.get":   { "message": { "header": {"status_code":200}, "body": {"track":{...}} } },
        "track.lyrics.get":    { "message": { "header": {"status_code":200}, "body": {"lyrics":{...}} } },
        "track.subtitles.get": { "message": { "header": {"status_code":200}, "body": {"subtitle_list":[...]} } },
        "track.richsync.get":  { "message": { "header": {"status_code":404}, "body": null } }
    } }
} }
```

**Kunci:** `HTTP 200 + luar 200 + matcher 200 + richsync 404` adalah respons **normal dan sukses**. Status luar = nasib permintaan; status dalam = nasib tiap jawaban.

| tingkat | kelas | file |
|---|---|---|
| bungkus luar | `ApiMessage<MacroResponseBody>` | `CommonModels.kt:12` |
| `macro_calls` | `MacroCalls` | `MacroModels.kt:33` |
| tiap jawaban | `MacroCall` | `MacroModels.kt:22` |
| bungkus dalam | `MacroMessage` | `MacroModels.kt:27` |
| `body` dalam | **`JsonElement?`** | `MacroModels.kt:29` |

Body dalam sengaja tanpa tipe karena keempat jawaban berbeda bentuk. Dibaca sesuai kebutuhan lewat `bodyAs<T>()` (`MacroModels.kt:40`).

---

## §5. Membuka amplop matcher, lalu validasi

Contoh bagian ini: **Blinding Lights / The Weeknd**, durasi 200.

`matchedTrack(calls)` (`Musixmatch.kt:316`) membuka **satu** amplop:

```
status matcher != 200  → NoMatch
bodyAs<MatcherBody>()?.track == null → NoMatch
```

Hasil:

```
trackId=194169545  commonTrackId=<kanonik>  trackName="Blinding Lights"
artistName="The Weeknd"  trackLength=200  hasRichsync=1  instrumental=0
```

`validateMatch` (`MusixmatchSearch.kt:49`):

1. durasi: `abs(200-200)=0` ≤ 8 → lolos
2. normalisasi: buang semua kecuali huruf/angka, kecilkan → `"blindinglights"`, `"theweeknd"`
3. judul saling mengandung **atau** artis saling mengandung. Gagal hanya jika **dua-duanya** tidak nyambung

---

## §6. `resolveTrackLyrics` — empat keputusan berurutan

`Musixmatch.kt:328`:

```
1. lyrics.restricted == 1 ?          → Restricted
2. track.isInstrumental ?            → return "♪ Instrumental ♪"
3. richsync ikut di macro?           → baca inline (:333-335)
4. inline null TAPI has_richsync=1?  → jemput terpisah (:336-338)
```

### §6.1 Request HTTP ketiga

`fetchRichSyncSeparately` (`:275`):

```http
GET .../track.richsync.get
    ?format=json&subtitle_format=mxm&app_id=mac-ios-v2.0
    &richsync_compact_type=words
    &commontrack_id=<commonTrackId>       ← bukan track_id
    &f_subtitle_length=200&q_duration=200
    &usertoken=<tiket>
```

Kegagalan apa pun kecuali `TokenInvalid`/`RateLimited`/`CancellationException` → `null`, kandidat tetap lanjut.

---

## §7. Percabangan TTML — ada TIGA titik

### Titik A — `Musixmatch.kt:336` (keputusan mengambil)

```kotlin
val additional = if (inline == null && track.isRichsyncAvailable) { jemput } else null
```

### Titik B — `LyricsFormat.kt:41-42` (keputusan format)

```kotlin
val richSync = richSyncBodyStr ?: macroRichSync
if (!richSync.isNullOrBlank()) { konversi; if (!ttml.isNullOrBlank()) return ttml }
```

`richSyncBodyStr` = `additional` dari A. Hasil jemputan didahulukan, inline jadi cadangan.

### Titik C — `MusixmatchTtml.kt:116` (konversi gagal diam-diam)

```kotlin
if (pTags.isEmpty()) return ""
```

Semua baris ditolak penjaga → string kosong → B baris 47 salah → **turun ke LRC**.

### Tabel keputusan

| inline | `has_richsync` | hasil A | dipakai B | format akhir |
|---|---|---|---|---|
| ada | apa pun | tidak menjemput | inline | **TTML** |
| tidak | 1 | jemput | hasil jemputan | **TTML** bila berhasil |
| tidak | 1 | jemput → null | null | LRC / polos |
| tidak | 0 | tidak menjemput | null | LRC / polos |

**Penjaga sebenarnya adalah flag `has_richsync`.** Kalau 0, TTML tidak pernah dicoba.

---

## §8. Konversi ke TTML

`richsync_body` adalah **STRING berisi JSON**, jadi ada dua kali decode:

```kotlin
val lines = jsonFormat.decodeFromString<List<RichSyncLine>>(richSync)   // decode #2
MusixmatchTtml.convertRichSyncToTtml(lines)
```

Nama field (`RichSyncModels.kt`): `ts`→startTime, `te`→endTime, `l`→words, `x`→text; per kata `c`→text, `o`→offset (**relatif terhadap awal baris**).

### §8.1 `convertLine` (`:47`)

Penjaga: tanpa kata dan teks kosong → `null`; `endTime <= startTime` atau jarak < 2 ms → `null`.

Tokenisasi: `c` kosong/spasi → `Token.Gap`; lainnya → `Token.Word` dengan
`start = (lineStart + offset).coerceIn(lineStart, lineEnd - 0.001)`.

Akhir kata **dihitung sendiri**, tidak dikirim server:
`end = max(mulai kata berikutnya, mulai sendiri + 0.001)`; kata terakhir → `lineEnd`.

Rakit: `Word` → `<span>`, `Gap` → teks biasa **tanpa span** (sebabnya spasi tidak menyala).

Contoh keluaran satu baris:

```xml
<p begin="7.748s" end="12.100s"><span begin="7.748s" end="8.168s">I&apos;ve</span> <span begin="8.168s" end="8.598s">been</span> <span begin="8.598s" end="9.048s">tryna</span> <span begin="9.048s" end="12.100s">call</span></p>
```

### §8.2 Bungkus dokumen (`:114`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
  <body>
    <div>
      <p ...>...</p>
    </div>
  </body>
</tt>
```

---

## §9. Kembali ke app

```
String TTML → resolveMacroLyrics → resolveTrackLyrics → Result.success → wrapper → LyricsHelper
```

Yang app terima tetap **satu string**, bukan objek.

### §9.1 App menilai mutu (`LyricsHelper.kt:374`)

```
hasWordSyncedLyrics → 2 (WORD_SYNCED_QUALITY)
isTtml || isLineSyncedLrc → 1
else → 0
```

`hasWordSyncedLyrics` mencari `<span>` yang punya **begin dan end**. Span kita punya keduanya → **nilai 2, tertinggi**.

### §9.2 App mendeteksi lalu mengurai

```
LyricsUtils.isTtml()    → mulai "<" dan mengandung "<tt"
LyricsUtils.parseTtml() → TTMLParser.parseTTML(normalizeLyricsText(ttml))
```

### §9.3 Bentuk akhir yang dipakai UI

```
LyricsEntry(
    text="I've been tryna call", startTime=7.748, endTime=12.100,
    words=[WordTimestamp("I've", 7.748, 8.168, isBackground=false), ...],
    agent=null, providerTranslationText=null, providerRomanizedText=null, isRtl=false,
)
```

Empat field kosong itu celah kapabilitas yang sudah diaudit.

---

## §10. Temuan sepanjang jejak

### §10.1 Data sama dibaca berulang

| dibaca di | dibaca lagi di |
|---|---|
| `Musixmatch.kt:329` restricted | `LyricsFormat.kt:31` restricted |
| `Musixmatch.kt:333-335` richsync inline | `LyricsFormat.kt:37-40` **empat baris identik** |
| `track` sudah jadi argumen | `LyricsFormat.kt:33` baca ulang matcher untuk instrumental |

Yang kedua menjelaskan kenapa percabangan terasa ada di dua tempat: keputusan yang sama memang dibelah dua.

### §10.2 `bodyAs` menelan kegagalan decode

`runCatching { }.getOrNull()` (`MacroModels.kt:40`) membuat "body rusak" terlihat sama dengan "body tidak ada".

### §10.3 Kebijakan instrumental tersebar di enam tempat

`MusixmatchSearch.kt:35` buang kandidat · `Musixmatch.kt:332` return penanda · `LyricsFormat.kt:33` return penanda · `Musixmatch.kt:427` Mismatch · `MusixmatchSearch.kt:129` NoMatch · `MusixmatchSearch.kt:155` null.

Sebagian sengaja beda (playback vs search), tapi kebijakannya tersirat, tidak dinyatakan di satu tempat.

### §10.4 Bug app di `normalizeLyricsText`

`isEscapedTtml` (`LyricsUtils.kt:565`) benar bila teks memuat namespace TTML **dan** `&lt;`. TTML kita selalu memuat namespace; lirik berisi `<` (mis. `<3`) kita escape jadi `&lt;`. Akibatnya app membuka escape → XML rusak → lirik hilang.

Berlaku untuk **semua** provider TTML. Test round-trip kita lolos karena memanggil `TTMLParser` langsung, melewati `normalizeLyricsText`.

### §10.5 Tangga status ditulis tiga kali

`Musixmatch.kt:129` (token), `:214` (decode umum), `:295` (richsync terpisah).

### §10.6 Module tidak pernah pindah dispatcher

Tidak ada `withContext`/`Dispatchers` di seluruh module — mewarisi `Dispatchers.IO` dari app. Sifat baik, layak dipertahankan.

---

## §11. Jalur LINE-SYNC (subtitle → LRC)

Contoh: **Koiiro / もさを。** — memang jalur ini (`has_richsync=0`, `has_subtitles=1`).

### §11.1 Titik mulai — hanya SATU, dan asimetris

`LyricsFormat.kt:50-56`:

```kotlin
val subtitleBody = calls?.trackSubtitlesGet
    ?.takeIf { it.message.header.statusCode == 200 }
    .bodyAs<SubtitlesBody>(jsonFormat)
    ?.subtitleList?.firstOrNull()?.subtitle?.subtitleBody
if (!subtitleBody.isNullOrBlank()) {
    formatSubtitlesToLrc(subtitleBody)?.takeIf { it.isNotBlank() }?.let { return it }
}
```

Richsync punya tiga titik (§7); line-sync hanya ini. **Tidak ada jemputan subtitle terpisah** —
satu-satunya penyebutan `track.subtitles.get` di module adalah pemetaan nama di `MacroModels.kt:36`.

Akibat: kalau kotak subtitle 404 padahal `has_subtitles=1`, langsung jatuh ke lirik polos.
Richsync mendapat kesempatan kedua, subtitle tidak.

### §11.2 Isi kotaknya

```json
{ "subtitle_list": [ { "subtitle": {
      "subtitle_body": "[{\"text\":\"...\",\"time\":{\"total\":12.34}}, ...]",
      "subtitle_language": "ja"
} } ] }
```

`subtitle_body` adalah **STRING berisi JSON** — pola sama dengan `richsync_body`, jadi tetap dua kali decode.

`firstOrNull()` hanya mengambil subtitle **pertama**. Bila server mengirim beberapa bahasa, sisanya dibuang tanpa diperiksa.

### §11.3 Konversi — `formatSubtitlesToLrc` (`:79`)

```kotlin
val lines = jsonFormat.decodeFromString<List<SubtitleLine>>(subtitlesJson)   // decode #2
if (lines.isEmpty()) return null
lines.joinToString("\n") { line ->
    val timestamp = formatLrcTimestamp(line.time?.total ?: 0.0)
    val text = line.text?.trim()?.ifEmpty { "♪" } ?: "♪"
    "$timestamp $text"
}
```

Model: `SubtitleLine(text, time)`, `SubtitleTime(total)` (`MacroModels.kt`).
**Teks kosong jadi `♪`**, bukan baris kosong — keputusan kita, berpengaruh nanti.

### §11.4 Matematika timestamp — `formatLrcTimestamp` (`:68`)

```kotlin
val ms = (seconds * 1000).toLong().coerceAtLeast(0L)
"[%02d:%02d.%03d]".format(ms / 60000, (ms % 60000) / 1000, ms % 1000)
```

| detik | ms | keluaran |
|---|---:|---|
| 12.34 | 12340 | `[00:12.340]` |
| 18.0 | 18000 | `[00:18.000]` |
| 20.5 | 20500 | `[00:20.500]` |
| -3.0 | 0 | `[00:00.000]` |

`.toLong()` **memotong**, tidak membulatkan. Negatif dipaksa nol.

### §11.5 Hasil yang keluar

```
[00:12.340] 無敵の笑顔で荒らすメディア
[00:18.000] ♪
[00:20.500] 知りたいその秘密ミステリアス
```

### §11.6 App menilai: dapat 1, bukan 2

```
hasWordSyncedLyrics → false   (tidak ada <span>, bukan QRC)
isTtml              → false
isLineSyncedLrc     → true    (LINE_REGEX cocok)
→ kualitas 1
```

Kalau provider lain memberi TTML untuk lagu yang sama, **dia menang atas kita**.

### §11.7 App mengurai — `parseLyrics` (`LyricsUtils.kt:457`)

```
normalizeLyricsText → bukan QRC → pecah per baris
tiap baris: parseEnhancedLrcLine ?: parseLineSyncedLrcLine ?: parseMillisecondsSyncedLine
lalu :493  mergeLineSyncedTranslations(result).sorted()
```

`parseLineSyncedLrcLine` (`:628`): `LINE_REGEX.matchEntire`, ambil grup waktu + grup teks,
buat `LyricsEntry(time, text)` per timestamp.

### §11.8 Kait terjemahan sudah terpasang

`mergeLineSyncedTranslations` (`:766`) memakai `linkedMapOf<Long, LyricsEntry>` — kunci **waktu dalam ms**.
Dua baris bertimestamp **identik**:

```
baris pertama → entri utama
baris kedua   → providerTranslationText entri pertama   (:793)
```

Syarat (`:790`): teks berbeda dari yang pertama, dan yang pertama belum punya terjemahan.

LRC kita sekarang: semua waktu berbeda → tidak ada penggabungan.
**Bila kita emit dua baris bertimestamp sama, terjemahan langsung aktif tanpa format baru.**

### §11.9 Bentuk akhir

```
LyricsEntry(time=12340, text="無敵の笑顔で荒らすメディア",
            words=null, durationMs=0, providerTranslationText=null, agent=null, isRtl=null)
```

| field | TTML | LRC |
|---|---|---|
| `words` | terisi | **null** |
| `durationMs` | terisi | **0** |
| nilai mutu | 2 | 1 |
| jemputan terpisah | ada | **tidak ada** |
| kait terjemahan | span `x-translation` | baris kedua bertimestamp sama |

`durationMs=0` → app menebak akhir baris dari awal baris berikutnya. TTML punya akhir eksplisit.

### §11.10 Catatan

1. **Server bisa memberi LRC langsung.** Kita meminta `subtitle_format=mxm` (JSON) lalu mengonversi sendiri.
   Musixmatch punya `subtitle_format=lrc`. Pilihan mengonversi sendiri masuk akal (kontrol 3 digit ms dan `♪`),
   tapi alternatifnya ada.
2. **`runCatching{}.getOrNull()`** (`:89`) — subtitle JSON rusak terlihat sama dengan tidak ada → jatuh ke polos tanpa jejak.
3. **Asimetri = calon celah.** Keputusan "subtitle juga perlu jemputan terpisah?" belum pernah diambil.

---

## §12. Jalur PLAIN TEXT (lirik polos)

### §12.1 Titik mulai — pilihan terakhir

`LyricsFormat.kt:58-65`:

```kotlin
val plain = calls?.trackLyricsGet
    ?.takeIf { it.message.header.statusCode == 200 }
    .bodyAs<LyricsBody>(jsonFormat)
    ?.lyrics?.lyricsBody
if (!plain.isNullOrBlank()) {
    cleanPlainLyrics(plain).takeIf { it.isNotBlank() }?.let { return it }
}
throw MusixmatchError.NoMatch("No lyrics found in any format")
```

Ini dasar terakhir. Kalau ini juga kosong → `NoMatch`, dan di playback itu memicu
`resolveSearchFallback` (§7 jalur A tidak berlaku di sini karena sudah di dalam pemilih format).

**Tidak ada decode #2.** Berbeda dari richsync dan subtitle, `lyrics_body` adalah teks biasa,
bukan string berisi JSON. Satu-satunya jalur yang tidak perlu decode kedua.

### §12.2 Pembersihan — `cleanPlainLyrics` (`:91`)

```kotlin
val lines = rawBody.lines()
val filtered = lines.filterNot { line ->
    val lower = line.lowercase()
    LICENSE_FOOTER_PATTERNS.any { lower.contains(it) }
}
return filtered.joinToString("\n").trim()
```

Pola (`:20-24`), dicocokkan setelah baris dikecilkan:

```
"this lyrics is not for commercial use"
"these lyrics are not for commercial use"
```

Jadi baris `******* This Lyrics is NOT for Commercial use *******` terbuang.
**Hanya itu yang dibersihkan** — tidak ada pemotongan, pemformatan, atau penambahan apa pun.

Kalau setelah pembersihan hasilnya kosong, `takeIf { it.isNotBlank() }` gagal → jatuh ke `NoMatch`.

### §12.3 App menilai: dapat 0

```
hasWordSyncedLyrics → false
isTtml              → false
isLineSyncedLrc     → false
→ kualitas 0
```

Provider lain yang memberi LRC atau TTML untuk lagu sama akan **selalu** menang atas kita.

### §12.4 App memperlakukannya berbeda — waktu PALSU

`ui/component/Lyrics.kt:484-497`:

```kotlin
if (isLineSyncedLrc(lyrics))      → parseLyrics(lyrics)
else if (isTtml(lyrics))          → parseTtml(lyrics, duration)
else                              → lyrics.lines().mapIndexed { index, line ->
                                        LyricsEntry(index * 100L, line)     // ← WAKTU PALSU
                                    }
```

**`parseLyrics` tidak dipanggil untuk lirik polos.** App membuat sendiri entri dengan waktu
`index × 100 ms` — 0, 100, 200, ... Itu bukan waktu lagu, hanya supaya teks bisa masuk
struktur `List<LyricsEntry>` yang sama.

Dua akibat langsung:

1. **`mergeLineSyncedTranslations` tidak pernah jalan** (§11.8) → **tidak ada kait terjemahan
   untuk lirik polos.** Fitur terjemahan hanya bisa lewat TTML atau LRC.
2. Semua waktu palsu itu berbeda satu sama lain, jadi kalaupun penggabungan jalan, tidak akan berguna.

### §12.5 Sinkronisasi dimatikan

`Lyrics.kt:529-531`:

```kotlin
val isSynced = !lyrics.isNullOrEmpty() && (isLineSyncedLrc(lyrics) || isTtml(lyrics))
```

Untuk lirik polos → **false**. Lalu `Lyrics.kt:641-646`:

```kotlin
if (lyrics.isNullOrEmpty() || (!isLineSyncedLrc(lyrics) && !isTtml(lyrics))) {
    currentLineIndex = -1
    currentPlaybackPosition = 0L
    return@LaunchedEffect
}
```

Jadi: **tidak ada auto-scroll, tidak ada baris aktif, tidak ada klik-untuk-lompat.**
Lirik polos ditampilkan sebagai teks statis yang bisa di-scroll manual.

### §12.6 Bentuk akhir

```
LyricsEntry(time=0,   text="baris pertama",  words=null, durationMs=0, ...)
LyricsEntry(time=100, text="baris kedua",    words=null, durationMs=0, ...)
LyricsEntry(time=200, text="baris ketiga",   words=null, durationMs=0, ...)
```

### §12.7 Efek samping yang tidak disengaja

Penanda instrumental kita — `"♪ Instrumental ♪"` (`Musixmatch.kt:332`) — tidak punya timestamp.
Jadi ia **mengambil jalur plain text ini**: waktu palsu, `isSynced=false`, satu baris statis.
Itu perilaku yang benar, tapi terjadi karena kebetulan bentuk, bukan karena keputusan yang dinyatakan.

### §12.8 Belum diverifikasi

Musixmatch dikenal membatasi `lyrics_body` untuk akses tidak berlisensi (sering hanya sebagian lagu).
**Kami belum mengukur ini**, jadi jangan diasumsikan. Bila benar, `cleanPlainLyrics` tidak menanganinya —
ia hanya membuang footer lisensi, tidak mendeteksi atau menandai pemotongan.

---

## §13. Perbandingan tiga jalur

| | TTML (richsync) | LRC (subtitle) | PLAIN |
|---|---|---|---|
| titik percabangan | **3** (A, B, C) | 1 | 1 |
| jemputan terpisah | **ada** | tidak | tidak |
| decode kedua | ya (string→JSON) | ya (string→JSON) | **tidak** |
| nilai mutu app | **2** | 1 | **0** |
| `words` | terisi | null | null |
| `durationMs` | terisi | 0 | 0 |
| waktu | nyata | nyata | **palsu (index×100ms)** |
| `isSynced` | true | true | **false** |
| auto-scroll | ya | ya | **tidak** |
| kait terjemahan | span `x-translation` | baris bertimestamp sama | **tidak ada** |
| fungsi parse app | `parseTtml` | `parseLyrics` | **tidak ada** |

**Urutan pilihan kita** (`LyricsFormat.resolveMacroLyrics`): richsync → subtitle → polos → `NoMatch`.
Urutan itu selaras dengan nilai mutu app (2 → 1 → 0), jadi kita selalu mengirim bentuk terbaik
yang tersedia. Itu keputusan yang benar dan sudah terbukti di perangkat.

---

## §14. Jalur playback: dua metode dan fallback

Perbedaan pokoknya satu hal: **siapa yang memilih lagunya.**

| | Metode 1 | Metode 2 |
|---|---|---|
| alamat | `macro.subtitles.get` | `track.search` → `macro.subtitles.get?track_id=X` |
| parameter | `q_track`, `q_artist`, `q_album`, `q_duration` | `track_id` |
| yang memilih lagu | **server** (matcher fuzzy) | **kita** (dari daftar) |
| jumlah request | 1 | 1 + sampai 2 |
| eksekusi | tunggal | **berurutan**, bukan paralel |

### §14.1 Percabangan — `Musixmatch.kt:356-367`

```kotlin
val lyrics = try {
    val calls = tokenState.execute { executeMacro(cleanTitle, cleanArtist, cleanAlbum, durationSeconds, token) }.macroCalls
    val track = matchedTrack(calls)                                   // :360
    validateMatch(track, cleanTitle, cleanArtist, durationSeconds)     // :361
    resolveTrackLyrics(calls, track, tokenState)                       // :362
} catch (e: MusixmatchError.NoMatch) {
    resolveSearchFallback(tokenState, cleanTitle, cleanArtist, durationSeconds)   // :364
} catch (e: MusixmatchError.Mismatch) {
    resolveSearchFallback(tokenState, cleanTitle, cleanArtist, durationSeconds)   // :366
}
```

### §14.2 Pemicu hanya DUA

`NoMatch` (tidak menemukan) dan `Mismatch` (menemukan tapi salah).

Yang **tidak** memicu fallback: `Restricted`, `RateLimited`, `TokenInvalid` (ditangani penjaga tiket),
`ServerError`, `MalformedResponse`. Semuanya langsung `Result.failure` di `:372`.

Jadi bila server bermasalah, kita tidak menghajarnya lewat jalur kedua. Hanya "lagunya salah/tidak ada"
yang boleh mencoba lagi.

### §14.3 Metode 2 — `MusixmatchSearch.kt:115-142`

```
:122   track.search                              → sampai 20 hasil
:124   selectSearchCandidates(..., limit = 2)     → saring, penalti versi, urutkan, ambil 2
       for (candidate in ...) {                   → BERURUTAN, yang pertama berhasil dipakai
:126       macro.subtitles.get?track_id=candidate.trackId
:127       matchedTrack(calls, candidate.trackId) → pastikan id kembali SAMA
:128       validateMatch(track, title, artist, duration)
:129       if (track.isInstrumental) throw NoMatch → lewati kandidat
:130       return resolveTrackLyrics(...)          → berhasil, keluar
       }
:141   semua gagal → throw lastFailure ?: NoMatch
```

Tiga error menghentikan seluruh loop (`:131-136`): `CancellationException`, `TokenInvalid`, `RateLimited`.
Error Musixmatch lain dicatat lalu lanjut ke kandidat berikutnya (`:137-138`).

### §14.4 Kenapa harus dua — terukur

| lagu | Metode 1 (matcher) | Metode 2 (search) |
|---|---|---|
| Koiiro / もさを。 | **ketemu** | 0 hasil |
| アイドル / YOASOBI | 404 | **ketemu** |

`track.search` ketat soal aksara nama artis; matcher lebih fuzzy. Keduanya saling menutupi titik buta.

### §14.5 Dua perbedaan perilaku

**(a) Kebijakan instrumental berbeda.** Metode 1 (`Musixmatch.kt:332`) mengembalikan `♪ Instrumental ♪`
sebagai jawaban sah. Metode 2 (`MusixmatchSearch.kt:129`) melewatinya sebagai bukan jawaban.

**(b) Metode 2 memeriksa lebih ketat.** `matchedTrack(calls, candidate.trackId)` memakai `expectedTrackId`
untuk memastikan macro mengembalikan lagu yang sama dengan yang diminta. Metode 1 memanggil
`matchedTrack(calls)` tanpa itu, karena server yang memilih.

### §14.6 Anggaran request (skenario terburuk)

```
token.get                                1
macro (metode 1, gagal)                  1
track.search                             1
kandidat 1: macro + richsync terpisah    2
kandidat 2: macro + richsync terpisah    2
──────────────────────────────────────────
total                                    7
```

Semua harus selesai dalam **8 detik** dari app. Tuas pertama bila sering timeout:
turunkan `SEARCH_FALLBACK_MAX_ATTEMPTS` dari 2 ke 1 — **bukan** menaikkan concurrency.

### §14.7 Cerminnya di jalur search

```
playback:  matcher  → gagal  → search
search:    search   → kosong → matcher   (resolveTitleFallbackForSearch, MusixmatchSearch.kt:144)
```

Pasangan endpoint sama, hanya urutan awalnya berbeda. Simetri ini sudah rapi dan layak dipertahankan.

---

## §15. Diagram gabungan — bentuk jam pasir

Module ini punya **satu titik leher**: `resolveTrackLyrics` dipanggil dari **empat** tempat,
dan ia **satu-satunya** pemanggil `resolveMacroLyrics`. Semua lirik yang keluar lewat fungsi itu.

```
EMPAT cara menemukan lagu   →   SATU fungsi bersama   →   TIGA format keluar
```

### §15.1 Bagian 1 — mencari lagu (empat jalur)

```
PLAYBACK  getLyrics:342
│
├─ METODE 1  macro.subtitles.get?q_track=...              §3.2
│    matchedTrack(calls)          :360                     §5
│    validateMatch(...)           :361                     §5
│    └────────────────────────────────────────► [leher]
│
└─ NoMatch / Mismatch  :363-366
   └─ METODE 2  resolveSearchFallback                      §14
        track.search                    :122
        selectSearchCandidates(limit 2) :124
        for tiap kandidat, BERURUTAN:
          macro?track_id=               :126
          matchedTrack(calls, id)       :127
          validateMatch                 :128
          instrumental? lewati          :129
          └──────────────────────────────────► [leher]

SEARCH  getAllLyrics:377
│
├─ track.search (20 hasil)              :390
├─ selectSearchCandidates(limit 4)      :392
│
├─ 0 kandidat → resolveTitleFallbackForSearch
│    macro (matcher) → matchedTrack → validateMatch:154
│    └──────────────────────────────────────► [leher]
│
└─ ada kandidat → maks 2 PARALEL (Semaphore)
     tiap kandidat: macro?track_id= → periksa inline :421-436
     └──────────────────────────────────────► [leher]
```

### §15.2 Bagian 2 — leher: `resolveTrackLyrics:328`

```
├─ restricted?              → throw Restricted             §6
├─ instrumental?            → return "♪ Instrumental ♪"     §6
├─ richsync inline?         → baca  :333-335               §7 titik A
├─ perlu jemput terpisah?   → track.richsync.get  :336     §6.1
└─ resolveMacroLyrics(calls, additional)   LyricsFormat:26
```

### §15.3 Bagian 3 — memilih format (tiga cabang)

```
resolveMacroLyrics
├─ richsync ada  → decode #2 → convertRichSyncToTtml → TTML    §7B, §8
├─ subtitle ada  → decode #2 → formatSubtitlesToLrc  → LRC     §11
├─ lyrics ada    → cleanPlainLyrics                  → polos   §12
└─ semua kosong  → throw NoMatch
```

### §15.4 Bagian 4 — kembali ke app

```
String → Result.success (playback) / callback (search)         §9
       → lyricsQuality: TTML=2, LRC=1, polos=0                 §9.1
       → parseTtml / parseLyrics / waktu palsu                 §9.2, §11.7, §12.4
       → List<LyricsEntry> → UI
```

### §15.5 Tiga temuan struktural

**(a) Lehernya sudah ada, tapi mencampur dua urusan.**
`resolveTrackLyrics` melakukan **pengambilan** (jemput richsync, butuh tiket) *dan* mendelegasikan
**penyajian** (pilih format). Itu sebabnya `tokenState` masuk ke fungsi yang seharusnya hanya memilih format.
Bila ingin memotong di satu tempat, di leher inilah: pisahkan "jemput semua payload" dari "pilih bentuk terbaik".

**(b) `validateMatch` dipanggil tiga kali, tapi tidak di jalur keempat.**

| jalur | validasi |
|---|---|
| playback metode 1 | `validateMatch:361` |
| playback metode 2 | `validateMatch:128` |
| search title-fallback | `validateMatch:154` |
| **search fanout** | **tulis sendiri inline** (`Musixmatch.kt:421-436`) |

**(c) Kebijakan instrumental punya empat hasil berbeda, tergantung jalur.**

| jalur | instrumental diperlakukan sebagai |
|---|---|
| leher (`:332`) | jawaban sah → `♪ Instrumental ♪` |
| playback metode 2 (`:129`) | bukan jawaban → lewati kandidat |
| search fanout (`:427`) | bukan jawaban → `Mismatch` |
| penyaring kandidat (`:35`) | bukan kandidat → dibuang sebelum dicoba |

Semua masuk akal secara niat, tapi **niat itu tidak pernah dinyatakan** — ia tersebar sebagai empat `if`.

### §15.6 Kesimpulan bentuk

Module ini sudah berbentuk jam pasir dengan leher yang jelas. Yang belum ada adalah
**pernyataan niat per jalur**. Sekarang niat itu tersirat di dalam `if` yang tersebar,
bukan di satu tempat yang bisa dibaca dari luar.

---

## §16. Inventaris panggilan jaringan

**5 panggilan ke 4 endpoint.** Semuanya `GET`. Endpoint `macro.subtitles.get` dipakai dua kali
dengan cara pengalamatan berbeda (§14).

### §16.1 Yang selalu sama

```
BASE  https://apic-appmobile.musixmatch.com/ws/1.1/
APP_ID  mac-ios-v2.0

HEADER (dipasang sekali di createHttpClient:62, dibawa setiap request)
  Accept:            application/json
  Accept-Language:   en-US,en;q=0.9
  X-Cookie:          x-mxm-token-guid=
  x-mxm-app-version: 10.1.1
  X-User-Agent:      Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0

TIMEOUT  request 20000 ms · connect 15000 ms · socket 20000 ms · expectSuccess = false
         (anggaran nyata tetap 8000 ms dari app — lihat §1.4)
```

Semua jawaban dibungkus pola yang sama:

```json
{ "message": { "header": { "status_code": 200, "execute_time": 0.03 }, "body": { ... } } }
```

### §16.2 `token.get` — `fetchToken` (:122, request :125)

| args | nilai |
|---|---|
| `app_id` | `mac-ios-v2.0` |

Output → `TokenBody` (`TokenModels.kt`)

```json
{ "message": { "body": { "user_token": "..." } } }
```

Ditolak bila kosong, semua `'0'`, atau diawali `UpgradeOnly` (:160).

### §16.3 `track.search` — `executeTrackSearch` (:173, request :179)

| args | nilai |
|---|---|
| `q_track` | judul |
| `q_artist` | artis |
| `q_artists` | artis (sama — redundan) |
| `page_size` | `20` (`SEARCH_PAGE_SIZE`) |
| `f_has_lyrics` | `1` |
| `app_id` | `mac-ios-v2.0` |
| `usertoken` | tiket |

Output → `SearchBody` (`SearchModels.kt`)

```json
{ "track_list": [ { "track": { ...Track... } }, ... ] }
```

Isi `Track` (`MacroModels.kt:52-70`) — dipakai juga oleh matcher:

```
track_id, commontrack_id, track_name, artist_name, album_name,
track_rating, track_length,
has_richsync, has_subtitles, has_lyrics, has_lyrics_crowd, instrumental
```

Turunan: `isInstrumental`, `isRichsyncAvailable`, `isSubtitlesAvailable`, `isLyricsAvailable`.

**Hanya metadata — tidak ada lirik di endpoint ini.**

### §16.4 `macro.subtitles.get` by-id — `executeMacroByTrackId` (:191, request :196)

| args | nilai |
|---|---|
| `format` | `json` |
| `namespace` | `lyrics_richsynched` |
| `subtitle_format` | `mxm` |
| `app_id` | `mac-ios-v2.0` |
| `optional_calls` | `track.richsync` |
| `richsync_compact_type` | `words` |
| `part` | `track_lyrics_translation_status,track_structure,track_performer_tagging` — **mati** |
| `track_id` | id rekaman |
| `usertoken` | tiket |

### §16.5 `macro.subtitles.get` by-name — `executeMacro` (:239, request :248)

Tujuh args pertama **identik** dengan §16.4, lalu:

| args | nilai |
|---|---|
| `q_track` | judul |
| `q_artist` | artis |
| `q_artists` | artis (redundan) |
| `usertoken` | tiket |
| `q_album` | album — **hanya bila tidak kosong** |
| `q_duration` | durasi — **hanya bila > 0** |
| `f_subtitle_length` | durasi — **hanya bila > 0** |

Output §16.4 dan §16.5 **sama** → `MacroResponseBody`

```json
{ "macro_calls": {
    "matcher.track.get":   { "message": { "header": {...}, "body": { "track": {...} } } },
    "track.lyrics.get":    { "message": { "header": {...}, "body": { "lyrics": {
                                "lyrics_id": N, "lyrics_body": "teks polos",
                                "restricted": 0, "instrumental": 0 } } } },
    "track.subtitles.get": { "message": { "header": {...}, "body": { "subtitle_list": [
                                { "subtitle": { "subtitle_body": "[JSON string]" } } ] } } },
    "track.richsync.get":  { "message": { "header": {...}, "body": { "richsync": {
                                "richsync_id": N, "richsync_body": "[JSON string]" } } } }
} }
```

Tiap kotak punya `status_code` sendiri — bisa 404 meski yang lain 200 (§4).

### §16.6 `track.richsync.get` — `fetchRichSyncSeparately` (:275, request :284)

| args | nilai |
|---|---|
| `format` | `json` |
| `subtitle_format` | `mxm` |
| `app_id` | `mac-ios-v2.0` |
| `richsync_compact_type` | `words` |
| `commontrack_id` | **id kanonik, bukan `track_id`** |
| `f_subtitle_length` | `track.trackLength` |
| `q_duration` | `track.trackLength` |
| `usertoken` | tiket |

Output → `RichSyncBody`

```json
{ "richsync": { "richsync_id": N, "richsync_body":
  "[{\"ts\":7.748,\"te\":12.1,\"l\":[{\"c\":\"I've\",\"o\":0.0}],\"x\":\"...\"}]" } }
```

**Tidak mengirim `namespace`, `optional_calls`, atau `part`.**

### §16.7 Ringkasan

| # | endpoint | pemanggil | jumlah args | output |
|---|---|---|---:|---|
| 1 | `token.get` | `fetchToken` | 1 | `user_token` |
| 2 | `track.search` | `executeTrackSearch` | 7 | daftar `Track` (metadata saja) |
| 3 | `macro.subtitles.get` | `executeMacroByTrackId` | 9 | 4 kotak |
| 4 | `macro.subtitles.get` | `executeMacro` | 12–14 | 4 kotak |
| 5 | `track.richsync.get` | `fetchRichSyncSeparately` | 8 | `richsync_body` |

### §16.8 Tiga ketidakkonsistenan

1. **`part=` dikirim di #3 dan #4, tidak di #5** — dan terbukti tidak menghasilkan sub-call apa pun.
   Beban mati di setiap request (§10).
2. **`q_artists` selalu duplikat `q_artist`** di #2 dan #4.
3. **Petunjuk durasi tidak konsisten**: #4 kirim (bila > 0), #3 **tidak**, #5 kirim.
   Padahal #3 dan #4 endpoint yang sama. Belum diukur apakah berpengaruh.

### §16.9 Dua identitas berbeda

```
track_id       id rekaman spesifik (versi ini)      → dipakai #3
commontrack_id id lagu kanonik (lintas versi)       → dipakai #5
```

Richsync dijemput atas nama **lagunya**, bukan rekaman spesifiknya — masuk akal karena sinkronisasi
kata bisa dipakai lintas rilis, tapi berarti identitas yang dipakai berbeda dari yang dipakai untuk macro.

### §16.10 Flag ketersediaan yang tidak terpakai

| flag | dipakai di |
|---|---|
| `isRichsyncAvailable` | `Musixmatch.kt:336` — satu tempat |
| `isSubtitlesAvailable` | **tidak ada** |
| `isLyricsAvailable` | **tidak ada** |

Dua dari tiga flag didefinisikan (`MacroModels.kt:68-69`) tapi tidak pernah dibaca.
Jadi bukan hanya "jemputan subtitle belum dibuat" — flag-nya sudah tersedia lalu diabaikan (§11.1).

---

## §17. Jalur search dan loop kandidat

**Tanggal:** 8 Nov 2026 · **Basis:** submodule `445dfff` · **Status:** reverse-engineering kode sekarang,
bukan rancangan akhir. Sisi app sudah dibongkar di `Musixmatch-Refactor-Plan.md` §1.4; bagian ini
membongkar sisi provider: penyaring, dua loop kandidat, concurrency, pembatalan, dan tiket bersama.

### §17.0 Di mana loop kandidat duduk

```
orkestrator → track.search → selectSearchCandidates → [loop kandidat] → resolveTrackLyrics → String
```

Sekarang loop itu punya **dua implementasi terpisah**:

1. `resolveSearchFallback` (`MusixmatchSearch.kt:115-142`) — playback fallback, berurutan.
2. Fan-out di dalam `getAllLyrics` (`Musixmatch.kt:404-459`) — search, paralel terbatas.

Rancangan refactor sempat ingin menjadikannya satu fungsi loop (`resolveCandidates()`), tetapi
**keputusan final 8 Nov 2026: loop TIDAK digabung** — tetap dua, di masing-masing orkestrator. Yang
shared hanya `selectSearchCandidates` (murni) + `resolveRequest`. Lihat `Refactor-Plan` §1.4.4.

### §17.1 Penyaring — `selectSearchCandidates` (`MusixmatchSearch.kt:17-32`)

```
track.search → hasil
  .mapNotNull { it.track }        buang item tanpa track
  .filter { isSearchCandidate }   id > 0, bukan instrumental, durasi ±8 bila diketahui
  .distinctBy { it.trackId }      buang id kembar
  → hitung demerit versi
  → sort: demerit naik, lalu track_rating turun
  .take(limit)                    4 untuk search, 2 untuk playback fallback
```

Permintaan search (`Musixmatch.kt:179-187`): `q_track`, `q_artist`, `q_artists`, `page_size=20`,
`f_has_lyrics=1`, `app_id`, `usertoken`. Server sudah menyaring "punya lirik" lewat `f_has_lyrics=1`.

`isSearchCandidate` (`S:34-37`):

```
track.trackId > 0 && !track.isInstrumental &&
  (durationSeconds <= 0 || track.trackLength <= 0 ||
    abs(track.trackLength - durationSeconds) <= DURATION_TOLERANCE)
```

`DURATION_TOLERANCE = 8` (`S:14`). Bila durasi input tidak diketahui (`<= 0`) atau durasi track tidak
diketahui (`<= 0`), gerbang durasi dilewati.

#### Contoh data nyata: Bohemian Rhapsody / Queen, input 354 s

Dari `tools/funnel-result.json`: 19 hasil kembali, 7 lolos gerbang, 4 diambil untuk search.

| id | durasi | Δ | rich | album |
|---|---:|---:|---:|---|
| 326718395 | 354 | 0 | 0 | Mix: Rock Viejo Y Nuevo |
| 324544544 | 355 | +1 | 1 | Academic Burn Out |
| 318289442 | 357 | +3 | 1 | Rock Royalty 2023 |
| 84683088 | 358 | +4 | 0 | Wayne's World (soundtrack) |
| 170624046 | 355 | +1 | 0 | Greatest Hits Platinum |
| 85297891 | 361 | +7 | 0 | Killer Queen — Tribute To Queen |
| 454577798 | 351 | −3 | 1 | Live at Wembley Stadium |

Yang dibuang antara lain: `(Live)` 330 s (Δ−24), `Live Aid` 148 s (Δ−206), `Operatic Section` 63 s
(Δ−291), `Live in Skanderborg` 365 s (Δ+11, lebih 3 detik dari batas).

**Versi Live at Wembley lolos gerbang durasi** karena hanya beda 3 detik. Di sinilah `demerit` bekerja:
input `Bohemian Rhapsody` tidak punya penanda versi → `inputMarkers = 0`; judul kandidat punya
`(Live At Wembley Stadium)` → penanda `live` → `demerit 1`. Ia terdorong ke belakang kandidat studio.

#### Dua temuan dari data itu

1. **Skenario "coba keduanya" nyata.** Di antara kandidat demerit 0 ada yang `rich=0` dan ada yang
   `rich=1`, semuanya `sub=1`. Bila rating menaruh `rich=0` di peringkat 1, playback fallback lama
   berhenti di LRC padahal peringkat 2 bisa memberi TTML.
2. **Album "Tribute" tidak kena demerit.** `versionDemerit:108` mengambil dari nama album hanya bit
   cover: `versionMarkers(albumName) and 4`. Album `Killer Queen — Tribute To Queen` menghasilkan
   penanda `tribute` (64), dan `64 and 4 = 0`. Judul tanpa kualifier → demerit 0. Rekaman tribute duduk
   setara studio. Belum disebut bug; ini perilaku yang perlu diketahui saat memutuskan peringkat.

### §17.2 Loop A — playback fallback (`resolveSearchFallback`, `S:115-142`)

```
log "Musixmatch playback fallback: ranked search"
body = tokenState.execute { executeTrackSearch(...) }          (:122)
lastFailure = null

untuk tiap kandidat dari selectSearchCandidates(limit 2):       (:124, BERURUTAN)
    calls = tokenState.execute { executeMacroByTrackId(...) }   (:126)
    track = matchedTrack(calls, candidate.trackId)              (:127)
    validateMatch(track, judul, artis, durasi)                  (:128)
    instrumental? → throw NoMatch                               (:129)
    return resolveTrackLyrics(calls, track, tokenState)         (:130, SUKSES PERTAMA KELUAR)

    catch CancellationException → throw                         (:131)
    catch TokenInvalid          → throw                         (:133, tinggalkan loop)
    catch RateLimited           → throw                         (:135)
    catch MusixmatchError       → lastFailure = e               (:137, lanjut kandidat)

throw lastFailure ?: NoMatch                                    (:141)
```

**Sifat:** satu hasil, berurutan, peringkat 1 dipakai bila berhasil. Error lokal dicatat dan dilanjut;
error global meninggalkan loop.

**Detail yang mudah terlewat:** tangkapan terakhir adalah `MusixmatchError`, **bukan `Exception`**.
Exception tak terduga di `matchedTrack` / `validateMatch` / `resolveTrackLyrics` lolos keluar dari loop.

### §17.3 Loop B — search fan-out (`getAllLyrics`, `M:377-460`)

```
trim judul/artis; kosong → return diam-diam                     (:384-386)
tokenState = SearchTokenState(...)                              (:388)
searchBody = tokenState.execute { executeTrackSearch(...) }     (:389)
candidates = selectSearchCandidates(limit 4)                    (:392)
logSearchFunnel                                                 (:393)

candidates kosong?                                              (:395)
    → resolveTitleFallbackForSearch (by-name)
    → kalau ada hasil: ensureActive → callback
    → return

semaphore = Semaphore(2)                                        (:404)
callbackMutex = Mutex()                                         (:405)
coroutineScope {                                                (:406)
    untuk tiap kandidat → launch {                              (:408-409, semua diluncurkan segera)
        try {
            semaphore.withPermit {                              (:411, hanya 2 jalan bersamaan)
                ensureActive                                    (:412)
                macroBody = tokenState.execute { macro by-id }  (:413)
                ensureActive                                    (:416)
                matcher status ≠ 200 → NoMatch                  (:420)
                track null / id beda → Mismatch                 (:424)
                instrumental / durasi → Mismatch                (:427)
                resolveTrackLyrics                              (:433)
            }
        }
        catch CancellationException → throw                     (:435)
        catch TokenInvalid          → throw                     (:437)
        catch RateLimited           → throw                     (:439)
        catch Exception → log "candidate skipped", null         (:441, lebih lebar dari Loop A)

        kalau hasil ada:
            ensureActive                                        (:446)
            callbackMutex.withLock {                            (:447)
                ensureActive                                    (:448)
                callback(hasil)                                 (:450)
                catch Cancellation → searchScope.cancel(e); throw (:451-453)
            }
    }
}
```

**Sifat:** banyak hasil, paralel terbatas, semua kandidat dicoba, tidak ada pemilihan.

### §17.4 Empat mekanisme concurrency

| mekanisme | apa yang dijamin |
|---|---|
| `launch` untuk semua kandidat | semua dijadwalkan segera; urutan mulai ≠ urutan selesai |
| `Semaphore(2)` | maksimal 2 request macro berjalan bersamaan |
| `callbackMutex` | callback app tidak pernah dipanggil bersamaan |
| `coroutineScope` | bila satu anak melempar, semua sibling dibatalkan dan fungsi melempar |

`ensureActive()` muncul di 5 titik: sebelum macro, sesudah macro, sebelum callback, di dalam mutex,
dan di cabang kandidat-kosong. Itu pemeriksaan kooperatif "apakah app sudah membatalkan aku?",
misalnya karena timeout 8 detik.

### §17.5 Pembatalan — tiga arah

1. **App membatalkan** (timeout) → `ensureActive` berikutnya melempar → anak mati → scope batal.
2. **Anak melempar global** (`TokenInvalid` / `RateLimited`) → `coroutineScope` membatalkan sibling →
   seluruh operasi gagal.
3. **Callback melempar `CancellationException`** → `searchScope.cancel(e)` → sibling dibatalkan.
   Ini pintu bagi app untuk menghentikan provider di tengah aliran. Dalam praktik app sekarang,
   callback-nya `channel.trySend` yang tidak melempar, jadi pintu ini belum terpakai.

### §17.6 Tiket di dalam loop — `SearchTokenState`

Satu `tokenState` dibagi semua kandidat dalam satu operasi.

```
execute(block):
    terminalFailure ada? → throw, TANPA jaringan                 (:38)
    previous = token
    coba block(previous)
    catch RateLimited → simpan terminal, throw                   (:42)
    catch TokenInvalid → renewalMutex.withLock {                 (:46)
        token sudah diganti orang lain? → pakai itu              (:48)
        renewalUsed? → terminal, throw                           (:51)
        renewalUsed = true; token = renewToken(previous)         (:55-57)
    }
    ulang block(retryToken)                                      (:71)
```

**Konsekuensi:** kandidat pertama yang kena 401 membayar perbaruan; sibling melihat `token != previous`
dan memakai tiket baru tanpa minta lagi. Jatah perbarui **satu per operasi**, bukan per kandidat.
`RateLimited` menyimpan `terminalFailure`, sehingga request berikutnya langsung gagal tanpa jaringan.

### §17.7 Anggaran request

| jalur | request |
|---|---|
| playback sukses jalur utama | 1 macro |
| playback fallback sekarang | 1 macro + 1 search + 1 macro kandidat (berhenti di sukses pertama) |
| playback fallback setelah keputusan "coba keduanya" | 1 macro + 1 search + 2 macro kandidat (+ jemputan tangga bila perlu) |
| search | 1 search + sampai 4 macro (+ jemputan tangga) |

Batas waktu **8000 ms** dipasang app dari luar; provider tidak diberi tahu.

### §17.8 Perbedaan kedua loop

| | Loop A (playback) | Loop B (search) |
|---|---|---|
| jumlah kandidat | 2 | 4 |
| keserentakan | berurutan | 2 paralel |
| hasil | satu, `return` | banyak, `callback` |
| args validasi | judul + artis + durasi | id + durasi |
| instrumental | `NoMatch` → lanjut | `Mismatch` → lewati |
| tangkapan terakhir | `MusixmatchError` | `Exception` (lebih lebar) |
| semua gagal | throw `lastFailure` | diam |
| mutex callback | tidak ada | ada |
| `ensureActive` | tidak ada | 5 titik |

### §17.9 Abstraksi loop kandidat

Kalau detail dilepas, loop kandidat (yang final tetap di orkestrator, bukan fungsi bersama) pada dasarnya:

> Diberi daftar kandidat berperingkat, hasilkan outcome tiap kandidat — dengan batas keserentakan,
> satu tiket bersama, error lokal yang dapat dilanjutkan, dan error terminal yang diarbitrase pemanggil.

Karena pemilihan mutu sudah dipindah ke `getLyrics()` dan concurrency menjadi aman untuk keduanya,
fungsi loop tidak perlu tahu limit maupun mode. Limit sudah dipakai penyaring saat membentuk daftar.
Concurrency tetap 2. Pemilihan di pemanggil.

**Enam invariant yang harus selamat:**

1. Error lokal (`NoMatch`, `Mismatch`, `Restricted`, `Instrumental`, `ServerError`,
   `MalformedResponse`, dan `Exception` non-global lain) menjadi outcome per kandidat dan tidak
   menghentikan kandidat lain.
2. `TokenInvalid`/`RateLimited` ditampung sebagai outcome terminal, bukan dilempar dari child. Playback:
   bila ada sukses, pilih sukses terbaik; tanpa sukses, lempar terminal terbaik. Fan-out: hentikan antrean,
   tunggu worker aktif, pertahankan callback sukses, lalu lempar terminal terbaik.
3. Pembatalan eksternal tetap diteruskan; early-stop playback hanya membatalkan child loser dan bukan
   kegagalan kandidat.
4. Pemeriksaan pembatalan tetap ada di titik-titik yang sekarang.
5. `emit` tidak boleh dipanggil bersamaan.
6. Tiket diperbarui maksimal sekali per operasi, dibagi antar kandidat; urutan datang bukan peringkat.

### §17.10 Keputusan baru yang mengubah loop

- **Coba keduanya, bukan berhenti di sukses pertama.** Playback fallback mencoba semua kandidat lolos
  saringan; `getLyrics()` memilih mutu tertinggi dengan aturan versi-dulu (`Refactor-Plan` §1.4).
- **Scoring tidak di dalam loop.** Loop hanya meng-emit hasil; pemanggil yang menilai.
- **Bentuk yang dipakai (final):** pemanggil memegang `search → filter` lewat `executeTrackSearch` +
  `selectSearchCandidates` (tanpa wrapper), lalu loop tetap di orkestrator. Tidak ada `resolveCandidates`.
  Dengan begitu `getAllLyrics` bisa melihat daftar kosong, dan `getLyrics` bisa melihat `demerit` tiap
  kandidat tanpa mekanisme laporan tambahan.
- **Lebar catch `Exception` + outcome terminal:** Loop A dan Loop B mengubah error lokal menjadi
  outcome per kandidat; `CancellationException` eksternal diteruskan, sedangkan `TokenInvalid`/`RateLimited`
  ditampung sebagai outcome terminal dan tidak dilempar dari child. Playback mengembalikan sukses terbaik
  jika ada; tanpa sukses, terminal berperingkat terbaik menang atas error lokal. Fan-out menghentikan
  antrean, menunggu worker aktif, mengirim callback sukses yang didapat, lalu melempar terminal terbaik.
  Tidak ada `lastFailure` bersama. Lihat `Refactor-Plan` §6.1–§6.2.
- **Concurrency playback DIKUNCI 2:** playback fallback tidak lagi berurutan. Karena keduanya sudah
  diluncurkan, early-stop aman berarti membatalkan yang masih in-flight, bukan berhenti meluncurkan.
  Lihat `Refactor-Plan` §1.4.4 / §2.6.
