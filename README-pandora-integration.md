# Pandora support for Composition Compass

This adds Pandora as a fully wired `IStreamingServiceQuery` source, alongside Spotify
and Last.fm. It talks to Pandora's undocumented JSON-RPC "tuner" API — the same one
used by the official apps and reimplemented by open-source clients like
[pianobar](https://6xq.net/pianobar/) and [pydora](https://github.com/mcrute/pydora).
Station creation + `station.getPlaylist` stands in for Spotify/Last.fm's "similar
tracks" recommendation endpoints.

## New files

- `Queries/PandoraCryptor.kt` — Blowfish request/response crypto (mirrors pydora's
  `pandora.transport.Encryptor`).
- `Queries/PandoraQuery.kt` — the `IStreamingServiceQuery` implementation: partner +
  user login, search, add, and the three "similar" endpoints.

## Modified files

- `Models/Enumerations.kt` — added `QuerySource.Pandora`.
- `Configuration/CompositionCompassOptions.kt` — added `pandoraUsername`,
  `pandoraPassword`, `pandoraPartnerUsername`, `pandoraPartnerPassword`,
  `pandoraDeviceModel`, `pandoraDecryptionKey`, `pandoraEncryptionKey` (same
  reflective-map-backed property pattern as the existing Spotify/Last.fm options).
- `Configuration/CompositionRoot.kt` — `changeQuerySource` now instantiates
  `PandoraQuery` for `QuerySource.Pandora`.
- `MainActivity.kt` — added "Pandora" to the source spinner.
- `build.gradle` (root), `app/build.gradle`, `gradle/wrapper/gradle-wrapper.properties`
  — unrelated build-toolchain fixes needed to get the project compiling at all
  (see "Build toolchain fixes" below). Not required for the Pandora feature itself.

## Build toolchain fixes

Three separate, pre-existing build breaks surfaced while getting this compiling —
none are caused by the Pandora addition:

1. **`youtubedl-android` JitPack coordinates dead.** That project moved to Maven
   Central under a new group ID a while back. Bumped
   `com.github.yausername.youtubedl-android:*:-SNAPSHOT` →
   `io.github.junkfood02.youtubedl-android:*:0.18.1`. Same package namespace
   (`com.yausername.youtubedl_android`), so no source changes needed.
2. **`korim:2.0.7` (transitive, via `spotify-api-kotlin-core:3.8.0`) JCenter-only,
   JCenter is dead.** Bumped `spotify-api-kotlin-core` to `3.8.8` (same 3.x API,
   patch-only bump) which pulls a Central-hosted korlibs version.
3. **`compileSdk 29` too old for the AndroidX versions the above two bumps
   transitively pull in** (some require compileSdk 31–34). Bumped
   `compileSdk`/`targetSdk` to `34`, AGP `7.2.0` → `8.3.2`, Gradle wrapper
   `7.3.3` → `8.5`, Kotlin Gradle plugin → `1.9.22`, and added the `namespace`
   declaration AGP 7.3+/8.x require in place of the manifest `package` attribute.
   Dropped the pinned `buildToolsVersion 31.0.0` so AGP picks a compatible one.

I wasn't able to actually run this build end-to-end myself (no Android SDK / Google's
Maven repo reachable from where I'm working) — these are diagnosed from the error
messages and public dependency/compatibility metadata, not verified against a live
build. AGP 8.x also requires **JDK 17** to run Gradle itself; recent Android Studio
versions bundle this, but if you're invoking Gradle from a separate JDK, check that
too. If anything else surfaces, paste it back and I'll keep working through it.

## Configuration

Composition Compass has no in-app settings screen — every option (including the
existing Spotify/Last.fm credentials) is read from `config.ini`, which
`CompositionCompassOptions` generates and re-reads on every launch. The same applies
here; add these lines to your `config.ini`:

```
pandoraUsername=you@example.com
pandoraPassword=your-pandora-password
pandoraPartnerUsername=...
pandoraPartnerPassword=...
pandoraDeviceModel=...
pandoraDecryptionKey=...
pandoraEncryptionKey=...
```

`pandoraUsername`/`pandoraPassword` are your normal Pandora account login.

`pandoraPartnerUsername`/`pandoraPartnerPassword`/`pandoraDeviceModel`/
`pandoraDecryptionKey`/`pandoraEncryptionKey` are the "partner" credentials that
identify the client to Pandora's API, the same way the official apps do — they
aren't tied to your account and aren't shipped with this project (Pandora doesn't
publish an official developer API, so these have to be sourced the same way pianobar
and pydora's users source theirs — see pydora's README/docs for pointers). I left
them out deliberately rather than hardcoding a specific set of reverse-engineered
values into the source.

## Notes / limitations

- Pandora's `music.search` endpoint only returns songs, artists, and genre stations —
  there's no native "album" search. `searchAlbum`/`addAlbum` approximate an album as
  the set of songs matched by a combined title+artist search (same idea the original
  incomplete draft started with).
- `getSimilarAlbums`/`getSimilarArtists` build their sample by repeatedly calling
  `station.getPlaylist` (Pandora returns ~4 tracks per call, advancing the station's
  play queue each time). This is capped at 40 calls regardless of
  `options.samplesSimilarAlbums`/`samplesSimilarArtists`, since those defaults are
  tuned for Spotify/Last.fm's much higher-throughput endpoints.
- Genres you add (`addGenre`) are stored as plain strings for folder-naming purposes,
  consistent with how the `Query` base class already uses `addedGenres` — they aren't
  used to seed a Pandora genre station directly.
