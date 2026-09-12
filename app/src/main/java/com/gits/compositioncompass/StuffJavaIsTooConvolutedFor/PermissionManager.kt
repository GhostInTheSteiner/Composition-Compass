package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

//Requests access to exactly one folder (and everything under it) via the Storage Access
//Framework, replacing the previous MANAGE_EXTERNAL_STORAGE / READ+WRITE_EXTERNAL_STORAGE
//flow. No broad storage permission is requested or declared anymore - just consent for the
//single folder the user picks via ACTION_OPEN_DOCUMENT_TREE. Everything under that tree is
//then accessed through SafStorage (DocumentFile/ContentResolver), which works uniformly
//regardless of which storage volume the folder is on.
class PermissionManager(private val activity: AppCompatActivity) {

    interface Callback {
        fun onGranted()
        fun onDenied()
    }

    private var pendingCallback: Callback? = null

    private val treePickerLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data

        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            pendingCallback?.onGranted()
        } else {
            pendingCallback?.onDenied()
        }

        pendingCallback = null
    }

    /** Launches the SAF folder picker so the user can grant access to one directory tree. */
    fun requestStorageAccess(callback: Callback) {
        pendingCallback = callback

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

        treePickerLauncher.launch(intent)
    }

    /** Synchronous check - has a folder already been picked (and is the grant still valid)? */
    fun hasStorageAccess(): Boolean =
        activity.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.isWritePermission }
}
