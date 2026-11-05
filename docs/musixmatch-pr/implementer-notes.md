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
