# Storage Access Framework migration (v2 - corrected)

Supersedes the previous round's approach, which turned out to be wrong: resolving a SAF
grant to a real path and using plain `java.io.File` on it doesn't work. Scoped storage's
FUSE layer blocks raw filesystem calls outside the app's own sandbox regardless of a
valid SAF grant - that's exactly what the `EPERM`/`Operation not permitted` crash was.

This version goes through `DocumentFile`/`ContentResolver` genuinely, for everything
inside the user-picked folder - including `config.ini`, `downloaded.txt`, and
`error.log`, per your last message, so all three are editable from any file manager
app, same as the music library itself.

## Design

**Everything lives inside the SAF-picked tree now** - `config.ini`, `downloaded.txt`,
`error.log`, and the Artists/Albums/Stations/`!automated` library structure - all
accessed through the new `SafStorage` helper (`DocumentFile`/`ContentResolver` only).

**One deliberate exception:** yt-dlp is a native process (not JVM code) and can only
write to a real filesystem path - it has no notion of `content://` URIs. Each download
still stages into a private scratch folder (`activity.cacheDir/download-staging/...`,
mirroring the same relative folder structure as its real destination), and
`YoutubeDownloader.flushStagingToTree()` copies the finished file(s) into the actual
SAF destination right after each `yt-dlp` run completes, deleting the staged copy. This
is the only place raw `File` I/O happens against anything other than `activity.cacheDir`
now. `downloaded.txt` itself is **not** part of this exception - the code never actually
passed it to yt-dlp as `--download-archive`; it's a pure Kotlin-side read/append check,
so it goes straight through `SafStorage` like everything else.

## New file

- `StuffJavaIsTooConvolutedFor/SafStorage.kt` - the storage layer: get-or-create
  directories/files, read/write/append text, copy a real `File` in (for staged
  downloads), move/rename within the tree. All paths are relative to the SAF root.

## Modified files

- `StuffJavaIsTooConvolutedFor/PermissionManager.kt` - simplified from the previous
  round: no more real-path resolution/validation, since genuine `DocumentFile` access
  works uniformly on any volume (including removable SD cards - that limitation from
  last round no longer applies).
- `StuffJavaIsTooConvolutedFor/Logger.kt` - `error.log` via `SafStorage.appendText`.
- `StuffJavaIsTooConvolutedFor/ItemPicker.kt` - one small additive change: exposes a
  `storage: SafStorage` property for `Query` subclasses to use (see below). The
  ad-hoc "browse to any folder for playback" feature itself is untouched - still
  deferred, see "What's NOT done" below.
- `Configuration/CompositionCompassOptions.kt` - `config.ini` read/written via
  `SafStorage`. `rootDirectoryPath` is now `""` (the SAF tree root itself) rather than
  a filesystem path; `__filePath` is gone, replaced by `configFileUri: Uri?` for
  opening the file externally.
- `Configuration/CompositionRoot.kt` - constructs one `SafStorage` and threads it into
  `CompositionCompassOptions`, `YoutubeDownloader`, and `Logger`.
- `Downloader/YoutubeDownloader.kt` - the staging+copy dance described above;
  `downloaded.txt` and the "already-explored artist" cleanup now go through
  `SafStorage` directly.
- `Queries/Query.kt`, `Queries/FileQuery.kt` - read via `SafStorage` instead of `File`
  (`getSpecifiedMoreInteresting()`'s directory scan, and the `Files/*.txt` search-query
  reader respectively). `FileQuery`'s constructor gained a `storage: SafStorage`
  parameter - `CompositionRoot` already passes it.
- `MainActivity.kt` - `requestBroadStorageAccess` → `requestStorageAccess` rename
  (as before); `openFile()` now takes the config's `content://` URI directly instead of
  building one via `FileProvider` from a raw path - simpler, and no longer needs
  `FileProvider` for this at all, so its manifest `<provider>` entry can potentially go
  (see below).

## What's NOT done yet

**`PlayerActivity.kt`** - like/dislike/browse/`playFolder` and the "already explored"
song-shuffling logic are untouched, still using raw `File` against paths that no longer
resolve to anything real. I'm holding off here on purpose: whether this can go through
`SafStorage` cleanly depends entirely on whether ArgAudio (the
`com.arges.sepan.argmusicplayer` player library) can play from a `content://` URI, or
needs a real file path handed to it. I couldn't confirm this from ArgAudio's public docs/
examples - they only show network URL playback. If it needs a real path, we're back to a
staging-style copy for playback too (copy the file about to play into private cache,
point ArgAudio at that, clean up after) - a real design decision, not a small patch, so I
didn't want to guess again given how the last round went. Let me know what you find (or
if you can point me at wherever ArgAudio actually loads local files from) and I'll finish
this properly.

## Gradle dependency needed

`SafStorage` uses `androidx.documentfile.provider.DocumentFile`. Add to `app/build.gradle`:

```gradle
implementation 'androidx.documentfile:documentfile:1.0.1'
```

## AndroidManifest.xml / resources - still not in your zip

Same as last round - remove `READ_EXTERNAL_STORAGE`/`WRITE_EXTERNAL_STORAGE`/
`MANAGE_EXTERNAL_STORAGE` `<uses-permission>` entries and
`android:requestLegacyExternalStorage="true"` if present.

**New this round:** since `openFile()` no longer uses `FileProvider`, if that
`<provider>` entry (and its `res/xml/file_paths.xml` or similar) in the manifest isn't
used for anything else in the app, it can be removed too. I can't confirm whether
anything else depends on it without seeing the manifest.

## What I couldn't verify

Still no Android SDK/build tooling here - reviewed by reading, not compiling. Two
specific things worth confirming on-device early:
- `openOutputStream(uri, "wa")` (append mode) - most `DocumentsProvider`
  implementations (including the built-in "Internal storage" one) support this, but
  `appendText()` has a read+concat+rewrite fallback in case yours doesn't; worth
  checking `downloaded.txt`/`error.log` actually grow correctly rather than silently
  hitting the fallback path repeatedly (which would still work, just less efficiently
  for a large log file).
- `DocumentFile.createFile()`'s exact filename-sanitization behavior can vary slightly
  by provider - `copyFileInto()`/`moveFile()` try to rename back to the exact requested
  name if it drifted, but if track titles contain characters some providers reject
  outright (rather than sanitizing), worth watching for on first real batch download.
