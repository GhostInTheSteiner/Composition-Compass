# Storage Access Framework migration

Replaces `MANAGE_EXTERNAL_STORAGE` (full "All files access") with the Storage Access
Framework: on first launch, the user picks one folder via `ACTION_OPEN_DOCUMENT_TREE`,
and that grant (plus everything under it) is all Composition Compass can read or write
from then on.

## Design

Rather than rewriting every file operation in the app to go through `DocumentFile`/
`ContentResolver`, the granted tree's real filesystem path is resolved **once**, right
after the picker returns (`SafPath.resolve`), and used everywhere downstream exactly
like the old `Environment.getExternalStorageDirectory()`-based path was. This was the
better fit here for three reasons:

- **yt-dlp writes via a native process** (`YoutubeDL.execute()`), not through Android's
  APIs - it fundamentally cannot write to a `content://` URI, only a real path.
- **ArgAudio's player** (`File(currentAudio!!.path)` and friends) expects real paths too.
- Every existing `File`-based move/list/rename call in `Query.kt`, `YoutubeDownloader.kt`,
  `FileQuery.kt`, and `PlayerActivity.kt`'s like/dislike/browse logic keeps working
  completely unchanged, since they're all still just consuming ordinary absolute path
  strings - they have no idea the source of that string changed.

This works because scoped storage gates *discovering* paths without consent, not using
one you already have consent for - once the user grants a tree via SAF, Android doesn't
block plain `File` reads/writes to the real path underneath it. This isn't the path
Google's docs recommend (they want `DocumentFile` exclusively), but it's a well-worn,
pragmatic technique for exactly this situation: a native downloader + third-party player
that both need real paths, with a permission model that no longer hands them out for free.

**Known limitation:** this only resolves paths on the **primary storage volume**
("Internal storage"). A tree picked on a removable SD card can't be reliably mapped back
to a real path this way. `SafPath.resolve` returns `null` in that case, and
`PermissionManager` treats it as a denial (with `onDenied()` firing) rather than silently
continuing with something broken - the person picking a folder will need to choose one
under Internal storage.

## New file

- `StuffJavaIsTooConvolutedFor/SafPath.kt` - resolves a granted SAF tree URI to its real
  absolute path. Everything else routes through this.

## Modified files

- `StuffJavaIsTooConvolutedFor/PermissionManager.kt` - `requestBroadStorageAccess()` →
  `requestStorageAccess()`, now launches `ACTION_OPEN_DOCUMENT_TREE` instead of the
  `MANAGE_EXTERNAL_STORAGE` Settings intent, calls `takePersistableUriPermission` so the
  grant survives restarts/reboots, and validates the pick resolves to a real path before
  reporting success.
- `StuffJavaIsTooConvolutedFor/ItemPicker.kt` - this already used
  `ACTION_OPEN_DOCUMENT_TREE` for the ad-hoc "browse to a folder" feature in
  `PlayerActivity`, but had two real bugs fixed here: it never called
  `takePersistableUriPermission` (so the grant silently didn't survive a restart), and it
  derived the path via `Uri.path` directly, which doesn't reliably give a real filesystem
  path for a tree URI. Now uses the same `SafPath` resolution as the main grant.
- `StuffJavaIsTooConvolutedFor/LocalFile.kt` - simplified. Its own ad-hoc path-guessing
  (splitting the URI string on `:`) is gone, since resolution now happens once via
  `SafPath` before a `LocalFile` is ever constructed.
- `Configuration/CompositionRoot.kt` - `config.ini`'s path is now built from
  `SafPath.resolvePersistedRoot()` instead of
  `Environment.getExternalStorageDirectory().absolutePath + "/Music/Pandora"`. This is
  the one change that ripples outward correctly: `CompositionCompassOptions.configFile.parent`
  (the snippet you pointed at) needs no changes at all, since it already does the right
  thing once `configFile`'s path is sourced correctly.
- `MainActivity.kt` - one-line rename at the call site
  (`requestBroadStorageAccess` → `requestStorageAccess`); the surrounding
  grant/init/deny flow was already structured the right way and needed no other changes.

## Files NOT touched (and why)

`Configuration/CompositionCompassOptions.kt`, `Queries/Query.kt`, `Queries/FileQuery.kt`,
`Downloader/YoutubeDownloader.kt`, `PlayerActivity.kt` (its directory setup, like/dislike,
and `playFolder` logic) - all of these only ever consume `options.rootDirectoryPath` and
the derived `*DirectoryPath` properties as plain absolute path strings. Since those
strings now originate from a SAF-granted real path instead of a blanket-permission real
path, nothing in these files needed to change.

## AndroidManifest.xml (not included in your zip - apply by hand)

Remove these, if present:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

and, on the `<application>` tag if present:

```xml
android:requestLegacyExternalStorage="true"
```

`ACTION_OPEN_DOCUMENT_TREE` needs no manifest permission at all - it's a system picker,
not a runtime permission.

If `MainActivity.openFile()` (which opens `config.ini` for editing via `FileProvider`)
has an associated `res/xml/file_paths.xml` (or similar) restricting which paths
`FileProvider` is allowed to serve, double check it still covers wherever `config.ini`
ends up now (inside whatever folder the user picks - no longer a fixed
`Music/Pandora` path). A `<external-path name="root" path="." />` entry (or similarly
permissive) should cover it; I can't confirm without seeing that file, since it wasn't
in the zip.

## What I couldn't verify

Same caveat as the rest of this thread: no Android SDK/build tooling here, so this is
reviewed by reading, not by compiling. The riskiest untested assumption is `SafPath`'s
`DocumentsContract.getTreeDocumentId()` parsing - it's a standard, well-documented
pattern, but worth confirming on an actual device that a freshly-picked internal-storage
folder resolves to the path you expect (a log line or toast showing
`SafPath.resolvePersistedRoot(this)`'s result right after granting would confirm it
quickly).
