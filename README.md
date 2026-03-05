# Android TV Player — SSD Cache Edition

A fully featured Android TV application that plays HLS and DASH network streams
with transparent SSD/USB caching via ExoPlayer (Media3).

---

## Features

- 🎬 **HLS & DASH** playback via ExoPlayer (Media3 1.2.0)
- 💾 **External SSD / USB drive caching** — streams are cached to the first
  available non-emulated external storage (SSD > USB > SD card > internal)
- 📺 **Leanback TV UI** — fully D-pad navigable, Android TV launcher ready
- ⚙️ **Settings screen** — configure cache size (MB), view cache path & usage,
  clear cache, and add/remove streams
- 🔄 **Cache-while-playing** — the cache is filled as content is streamed,
  so repeated views don't re-download data

---

## Project Structure

```
app/
  src/main/
    java/com/androidtvplayer/
      TVPlayerApplication.kt       # Application entry, cache init
      cache/
        CacheManager.kt            # ★ Core SSD cache logic (SimpleCache + LRU)
      data/
        PreferencesManager.kt      # SharedPreferences: cache size, stream list
        StreamItem.kt              # Data model
      ui/
        browse/
          MainActivity.kt          # FragmentActivity host
          BrowseFragment.kt        # Leanback BrowseSupportFragment
          StreamCardPresenter.kt   # Card for stream items
          CardPresenters.kt        # Cache & Settings card presenters
        player/
          PlayerActivity.kt        # ★ ExoPlayer + CacheDataSource
        settings/
          SettingsActivity.kt      # PreferenceFragmentCompat
    res/
      layout/                      # Layouts for all screens and cards
      drawable/                    # Vector drawables (cards, icons)
      values/                      # strings.xml, themes.xml
    AndroidManifest.xml
  build.gradle                     # ExoPlayer, Leanback, Glide deps
```

---

## How the SSD Cache Works

`CacheManager.kt` is the heart of the caching system:

1. **Storage selection** (`resolveCacheDirectory()`):
   - Iterates `context.getExternalFilesDirs(null)` — all mounted external volumes
   - Prefers `!Environment.isExternalStorageEmulated(dir)` → physical SSD/USB
   - Falls back to emulated external (SD card) then internal cache

2. **SimpleCache setup**:
   ```kotlin
   SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(cacheSizeBytes), databaseProvider)
   ```
   - **LRU eviction** keeps the cache within the configured size limit
   - Cache size is user-configurable in Settings (default: 20 GB)

3. **CacheDataSource**:
   ```kotlin
   CacheDataSource.Factory()
       .setCache(cache)
       .setUpstreamDataSourceFactory(upstreamFactory)  // HTTP fallback
       .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
   ```
   - Serves cached segments first; fetches from network only for uncached data
   - Filled progressively as the stream plays

---

## Getting Started

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android TV device / emulator (API 21+)
- External SSD/USB connected via USB-C OTG adapter (or USB hub)

### Build & Install

```bash
git clone <this-repo>
cd AndroidTVPlayer
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open in Android Studio and click **Run**.

### Connecting an External SSD

1. Connect your SSD via a USB-C OTG adapter or USB hub to your Android TV box
2. On Android, format the drive as **portable storage** (not adoptable storage)
3. Launch the app — it automatically detects and uses the SSD for caching
4. Go to **Settings → Cache Location** to confirm the path

### Changing Cache Size

Settings → Cache Size → enter size in MB (e.g. `51200` for 50 GB)

---

## Customising Demo Streams

Edit `PreferencesManager.kt` to change the default stream list, or add streams
at runtime via **Settings → Add Stream**.

---

## Permissions

| Permission | Reason |
|---|---|
| `INTERNET` | Stream playback |
| `READ/WRITE_EXTERNAL_STORAGE` | SSD cache access (API ≤ 28) |
| `MANAGE_EXTERNAL_STORAGE` | SSD cache access (API 29+) |

On Android 11+ you may need to grant `MANAGE_EXTERNAL_STORAGE` manually in
Settings → Apps → TV Player → Permissions.

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| `androidx.media3:media3-exoplayer` | 1.2.0 | HLS/DASH playback |
| `androidx.media3:media3-datasource-cache` | 1.2.0 | SSD caching |
| `androidx.leanback:leanback` | 1.2.0-alpha04 | TV UI |
| `com.github.bumptech.glide:glide` | 4.16.0 | Thumbnail loading |
| `com.google.code.gson:gson` | 2.10.1 | JSON serialization |
# force rebuild Thu Mar  5 10:04:19 EAT 2026
