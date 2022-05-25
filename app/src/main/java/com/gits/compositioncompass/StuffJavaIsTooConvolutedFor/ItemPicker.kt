package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gits.compositioncompass.PlayerActivity
import java.io.File


class ItemPicker(
    private val activity: AppCompatActivity,
    success: (result: ActivityResult, file: File) -> Unit = { _, _ -> },
    error: (result: ActivityResult) -> Unit = { result -> throw Exception("ItemPicker failed: " + result.toString()) }) {

    private var launcher: ActivityResultLauncher<Intent>
    private var intent: Intent? = null;

    init {
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            result: ActivityResult ->
                if (result.resultCode == Activity.RESULT_OK)
                    success(result, LocalFile(result.data!!.data!!.path!!))
                else
                    error(result)
        }
    }

    fun folder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addCategory(Intent.CATEGORY_DEFAULT)

        launcher.launch(i)
    }


}