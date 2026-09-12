package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import kotlinx.coroutines.channels.Channel

//Lets the user pick a folder via ACTION_OPEN_DOCUMENT_TREE (used by PlayerActivity's
//"browse" feature to navigate into a subfolder of the library, e.g. Stations/!Similar
//(...)). Previously resolved the pick to a guessed real filesystem path (SafPath) -
//that never actually worked (scoped storage blocks raw File access to the SAF tree
//regardless of grant), which is why browsing produced an empty playlist. Now resolves
//the pick to a path relative to the app's main storage tree instead - the same
//convention SafStorage/options.stationsDirectoryPath etc. already use everywhere else -
//so the rest of the app (PlayerActivity, SafStorage) can just treat it identically to
//any other library path.
class ItemPicker(
    private val options: CompositionCompassOptions,
    private val activity: Activity,
    success: (result: ActivityResult, relativePath: String) -> Unit = { _, _ -> },
    error: (result: ActivityResult) -> Unit = { _ -> }
){

    //shared with Query subclasses (see Query.getSpecifiedMoreInteresting) and used
    //below to resolve picks against the main tree - cheap to construct, does no I/O
    //until actually used
    val storage = SafStorage(activity)

    private var channel: Channel<String?>
    private var launcher: ActivityResultLauncher<Intent>

    init {
        channel = Channel(0)

        launcher = (activity as AppCompatActivity).registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                result: ActivityResult ->
            var relativePath: String? = null

            if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
                val uri = result.data!!.data!!

                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                relativePath = storage.relativePathOf(uri)

                if (relativePath != null)
                    success(result, relativePath)
                else
                    error(result) //picked from outside the app's granted tree - not supported
            }
            else
                error(result)

            channel.trySend(relativePath).isSuccess
        }
    }

    //returns the picked folder's path relative to the app's main storage tree ("" for
    //the tree root itself), or null if nothing was picked, or it wasn't part of the
    //granted tree
    suspend fun folder(): String? {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addCategory(Intent.CATEGORY_DEFAULT)
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )

        launcher.launch(i)

        return channel.receive()
    }
}