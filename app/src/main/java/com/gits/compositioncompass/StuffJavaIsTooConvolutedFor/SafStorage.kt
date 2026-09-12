package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

//Single storage mechanism for everything Composition Compass reads/writes inside the
//folder tree the user picked via ACTION_OPEN_DOCUMENT_TREE (see PermissionManager).
//Backed by DocumentFile/ContentResolver exclusively - raw java.io.File access to this
//tree does not work, even with a valid persisted grant: scoped storage's FUSE layer
//blocks direct filesystem calls outside the app's own sandbox regardless of SAF consent.
//
//The one exception is actual audio downloads: yt-dlp is a native process and can only
//write to a real filesystem path, so YoutubeDownloader stages those to private storage
//first and copies the finished file in via copyFileInto() - see its comments there.
//
//All paths handled here are relative, "/"-separated, and tolerate (and ignore) a leading
//"/", e.g. "Artists/Daft Punk" or "!automated/Favorites" or "/!automated/Favorites".
class SafStorage(private val activity: Activity) {

    private val dirCache = mutableMapOf<String, DocumentFile>()

    val isReady: Boolean get() = root() != null

    val treeUri: Uri?
        get() = activity.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && it.isWritePermission }
            ?.uri

    private fun root(): DocumentFile? {
        val granted = treeUri ?: return null
        return DocumentFile.fromTreeUri(activity, granted)
    }

    //resolves a SEPARATELY-picked tree Uri (e.g. from a second ACTION_OPEN_DOCUMENT_TREE
    //call, like ItemPicker.folder() browsing to a subfolder) to its path relative to
    //THIS tree, if it's actually nested inside it. Relies on the standard behavior of
    //the built-in "Internal storage" DocumentsProvider, where a subfolder's document ID
    //is the parent's document ID with a "/segment" suffix appended. Returns null if the
    //picked folder isn't part of this tree (different volume, different provider, or
    //just somewhere unrelated) - callers should treat that as "not supported" rather
    //than silently falling back to something broken.
    fun relativePathOf(pickedTreeUri: Uri): String? {
        val mainUri = treeUri ?: return null
        if (pickedTreeUri.authority != mainUri.authority) return null

        val pickedId = DocumentsContract.getTreeDocumentId(pickedTreeUri)
        val mainId = DocumentsContract.getTreeDocumentId(mainUri)

        return when {
            pickedId == mainId -> ""
            pickedId.startsWith("$mainId/") -> pickedId.removePrefix("$mainId/")
            else -> null
        }
    }

    private fun segments(relativePath: String): List<String> =
        relativePath.split("/").filter { it.isNotEmpty() }

    fun getOrCreateDirectory(relativePath: String): DocumentFile? {
        val key = segments(relativePath).joinToString("/")
        if (key.isEmpty()) return root()

        dirCache[key]?.let { return it }

        var current = root() ?: return null
        for (segment in segments(relativePath)) {
            val existing = current.findFile(segment)
            current = when {
                existing != null && existing.isDirectory -> existing
                existing != null -> return null //name collision with a file
                else -> current.createDirectory(segment) ?: return null
            }
        }

        dirCache[key] = current
        return current
    }

    fun findDirectory(relativePath: String): DocumentFile? {
        val segs = segments(relativePath)
        if (segs.isEmpty()) return root()

        var current = root() ?: return null
        for (segment in segs) {
            current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return current
    }

    fun listChildren(relativePath: String): List<DocumentFile> =
        findDirectory(relativePath)?.listFiles()?.toList() ?: listOf()

    fun listFiles(relativePath: String): List<DocumentFile> =
        listChildren(relativePath).filter { it.isFile }

    fun listFileNames(relativePath: String): List<String> =
        listFiles(relativePath).mapNotNull { it.name }

    fun findFile(relativePath: String, fileName: String): DocumentFile? {
        val dir = findDirectory(relativePath) ?: return null
        return dir.findFile(fileName)
            ?: dir.listFiles().firstOrNull { it.name == fileName }
    }

    fun exists(relativePath: String, fileName: String): Boolean =
        findFile(relativePath, fileName) != null

    private fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "txt", "log", "ini" -> "text/plain"
            "mp3" -> "audio/mpeg"
            "opus" -> "audio/opus"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }

    fun getOrCreateFile(relativePath: String, fileName: String): DocumentFile? {
        val dir = getOrCreateDirectory(relativePath) ?: return null

        // 1. Return existing file if it already exists (findFile is flaky on some
        //    OEM skins, so we double-check with a manual scan).
        dir.findFile(fileName)?.let { return it }
        dir.listFiles().firstOrNull { it.name == fileName }?.let { return it }

        // 2. Create with a generic MIME type first. This prevents the provider from
        //    appending an extension (e.g. config.ini -> config.ini.txt). If the
        //    provider rejects application/octet-stream, fall back to the inferred type.
        val doc = dir.createFile("application/octet-stream", fileName)
            ?: dir.createFile(mimeTypeFor(fileName), fileName)
            ?: return null

        // 3. If the provider still mangled the display name, rename it back.
        if (doc.name != fileName) {
            doc.renameTo(fileName)
            // Re-query so callers get the updated Uri / correct name
            return dir.listFiles().firstOrNull { it.name == fileName } ?: doc
        }

        return doc
    }

    fun readText(relativePath: String, fileName: String): String? {
        val doc = findFile(relativePath, fileName) ?: return null
        return activity.contentResolver.openInputStream(doc.uri)?.use { it.bufferedReader().readText() }
    }

    fun readLines(relativePath: String, fileName: String): List<String> =
        readText(relativePath, fileName)?.lines()?.filter { it.isNotEmpty() } ?: listOf()

    //overwrites the file's full contents
    fun writeText(relativePath: String, fileName: String, content: String): Boolean {
        val doc = getOrCreateFile(relativePath, fileName) ?: return false

        return try {
            activity.contentResolver.openOutputStream(doc.uri, "wt")?.use { it.write(content.toByteArray()) }
            true
        } catch (e: Exception) {
            false
        }
    }

    //appends to the end of the file; falls back to read+concat+rewrite if the underlying
    //provider doesn't support append mode ("wa") - not all DocumentsProvider implementations do
    fun appendText(relativePath: String, fileName: String, content: String): Boolean {
        val doc = getOrCreateFile(relativePath, fileName) ?: return false

        return try {
            activity.contentResolver.openOutputStream(doc.uri, "wa")?.use { it.write(content.toByteArray()) }
            true
        } catch (e: Exception) {
            val existing = readText(relativePath, fileName) ?: ""
            writeText(relativePath, fileName, existing + content)
        }
    }

    //copies a real File (e.g. one yt-dlp just wrote to private staging storage) into the
    //SAF tree at relativePath/fileName, overwriting any existing file with that name
    fun copyFileInto(source: File, relativePath: String, fileName: String): Boolean {
        val dir = getOrCreateDirectory(relativePath) ?: return false

        dir.findFile(fileName)?.delete()
        val target = dir.createFile(mimeTypeFor(fileName), fileName) ?: return false

        return try {
            activity.contentResolver.openOutputStream(target.uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            }

            //createFile() may sanitize/append an extension based on the mime type;
            //rename back to the exact name we asked for if it drifted
            if (target.name != fileName) target.renameTo(fileName)

            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteFile(relativePath: String, fileName: String): Boolean =
        findFile(relativePath, fileName)?.delete() ?: false

    fun renameFile(relativePath: String, fileName: String, newName: String): Boolean =
        findFile(relativePath, fileName)?.renameTo(newName) ?: false

    //moves a file from one SAF-tree location to another (e.g. the "already explored
    //artist" cleanup in YoutubeDownloader). DocumentFile has no guaranteed atomic move
    //across all providers, so this copies then deletes the source.
    fun moveFile(fromRelativePath: String, fileName: String, toRelativePath: String, newFileName: String = fileName): Boolean {
        val source = findFile(fromRelativePath, fileName) ?: return false
        val targetDir = getOrCreateDirectory(toRelativePath) ?: return false

        targetDir.findFile(newFileName)?.delete()
        val target = targetDir.createFile(mimeTypeFor(newFileName), newFileName) ?: return false

        return try {
            activity.contentResolver.openInputStream(source.uri)?.use { input ->
                activity.contentResolver.openOutputStream(target.uri)?.use { output ->
                    input.copyTo(output)
                }
            }

            if (target.name != newFileName) target.renameTo(newFileName)

            source.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    //same as above, but for a file only known by its own content Uri (e.g.
    //PlayerActivity.currentAudio's path, which is a single-document Uri rather than a
    //relative path within this tree - see ArgAudio's FILE_PATH mode). Uses
    //DocumentFile.fromSingleUri, not fromTreeUri: this wraps one specific document, not
    //a granted tree root. Works without any extra permission handling as long as the
    //Uri is a child of a tree this app already holds a persisted grant for.
    fun moveFile(sourceUri: Uri, toRelativePath: String, newFileName: String? = null): Boolean {
        val source = DocumentFile.fromSingleUri(activity, sourceUri) ?: return false
        val fileName = newFileName ?: source.name ?: return false
        val targetDir = getOrCreateDirectory(toRelativePath) ?: return false

        targetDir.findFile(fileName)?.delete()
        val target = targetDir.createFile(mimeTypeFor(fileName), fileName) ?: return false

        return try {
            activity.contentResolver.openInputStream(source.uri)?.use { input ->
                activity.contentResolver.openOutputStream(target.uri)?.use { output ->
                    input.copyTo(output)
                }
            }

            if (target.name != fileName) target.renameTo(fileName)

            source.delete()
            true
        } catch (e: Exception) {
            false
        }
    }
}