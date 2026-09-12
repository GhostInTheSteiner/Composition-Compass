package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

//Resolves a Storage Access Framework tree URI (from ACTION_OPEN_DOCUMENT_TREE) back to a
//real, directly-usable absolute filesystem path.
//
//This is deliberately NOT the officially-recommended way to work with SAF - Google wants
//all access to go through DocumentFile/ContentResolver. We resolve to a real path instead
//because once the user has granted access to a specific tree via the picker, Android does
//not block plain File-based reads/writes to that same real path; nothing about scoped
//storage prevents using a path you already have consent for. Relying on this lets the rest
//of the codebase - yt-dlp's native downloader process, ArgAudio's player, and all the
//existing File-based move/list/rename logic - keep working completely unchanged, since
//they all just see an ordinary absolute path.
//
//LIMITATION: only resolves paths on the primary shared-storage volume ("Internal storage").
//Trees picked on removable SD cards or other secondary volumes can't be reliably mapped to
//a real path this way. Callers should treat a null result as "unsupported location - please
//pick a folder under Internal storage" rather than silently degrading.
object SafPath {

    fun resolve(treeUri: Uri): String? {
        return try {
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = documentId.split(":", limit = 2)

            if (parts.size != 2 || parts[0] != "primary") return null //not the primary volume

            val relativePath = parts[1]
            val basePath = Environment.getExternalStorageDirectory().absolutePath

            if (relativePath.isEmpty()) basePath else "$basePath/$relativePath"
        } catch (e: Exception) {
            null
        }
    }

    //convenience: resolves whichever tree is currently persisted for this app (there's only
    //ever meant to be one - see PermissionManager), or null if nothing has been granted yet,
    //it's since been revoked, or it can't be resolved to a real path.
    fun resolvePersistedRoot(context: Context): String? {
        val granted = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.isReadPermission && it.isWritePermission }
            ?: return null

        return resolve(granted.uri)
    }
}
