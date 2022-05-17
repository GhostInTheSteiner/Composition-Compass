package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gits.compositioncompass.PlayerActivity


class ItemPicker(private val activity: AppCompatActivity) {
    private var intent: Intent? = null;

    fun folder(success: (path: String) -> Unit = { _ -> }, error: (result: ActivityResult) -> Unit = { _ -> }) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addCategory(Intent.CATEGORY_DEFAULT)

        val startForResult = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                result: ActivityResult ->
                    if (result.resultCode == Activity.RESULT_OK)
                        success(result.data!!.data!!.path!!)
                    else
                        error(result)
        }

        startForResult.launch(i)
    }


}