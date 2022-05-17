package com.gits.compositioncompass

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.BluetoothDevice
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.ItemPicker


class PlayerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = BluetoothDevice(this)

        device.sendTextOverAVRCP()
//        device.artist = "test"

        val picker = ItemPicker(this)
        picker.folder(success = { path -> doSth(path)})
//        val view = findViewById<InstantMultiAutoCompleteTextView>(R.id.startbutton)
    }

    private fun doSth(path: String) {
        TODO("Not yet implemented")
    }

//    fun openActivityForResult() {
//        startForResult.launch(Intent(this, AnotherActivity::class.java))
//    }
//
//    val startForResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
//            result: ActivityResult ->
//        if (result.resultCode == Activity.RESULT_OK) {
//            val intent = result.data
//            // Handle the Intent
//            //do stuff here
//        }
//    }
}