package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.File


class ItemPicker(
    private val options: CompositionCompassOptions,
    private val activity: Activity,
    success: (result: ActivityResult, file: File) -> Unit = { _, _ -> },
    error: (result: ActivityResult) -> Unit = { _ -> }
){

    private var channel: Channel<LocalFile?>
    private var launcher: ActivityResultLauncher<Intent>
    private var intent: Intent? = null;

    init {
        channel = Channel(0)

        launcher = (activity as AppCompatActivity).registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            result: ActivityResult ->
                var file: LocalFile? = null

                if (result.resultCode == Activity.RESULT_OK) {
                    file = LocalFile(result.data!!.data!!.path!!)
                    success(result, file)
                }
                else
                    error(result)

                channel.offer(file)
        }
    }

    suspend fun folder() : LocalFile? {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addCategory(Intent.CATEGORY_DEFAULT)
        launcher.launch(i)

        val file = channel.receive()
        return file
    }
}