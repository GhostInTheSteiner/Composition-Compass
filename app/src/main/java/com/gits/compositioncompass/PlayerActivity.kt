package com.gits.compositioncompass

import android.content.Context
import android.os.Bundle
import android.os.Vibrator
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AppCompatActivity
import com.arges.sepan.argmusicplayer.Callbacks.OnErrorListener
import com.arges.sepan.argmusicplayer.Callbacks.OnPlaylistAudioChangedListener
import com.arges.sepan.argmusicplayer.Enums.ErrorType
import com.arges.sepan.argmusicplayer.Models.ArgAudio
import com.arges.sepan.argmusicplayer.Models.ArgAudioList
import com.arges.sepan.argmusicplayer.PlayerViews.ArgPlayerFullScreenView
import com.gits.compositioncompass.Configuration.CompositionRoot
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.ItemPicker
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.LocalFile
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Logger
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Notifier
import com.gits.compositioncompass.databinding.ActivityPlayerBinding
import vibrateLong
import java.io.File


class PlayerActivity : AppCompatActivity(), OnPlaylistAudioChangedListener, OnErrorListener {
    private var targetLike: String = ""
    private var targetDislike: String = ""
    private var currentAudio: ArgAudio? = null
    private lateinit var source: ItemPicker
    private lateinit var vibrator: Vibrator
    private lateinit var player: ArgPlayerFullScreenView
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerControls: List<View>
    private lateinit var composition: CompositionRoot
    private lateinit var logger: Logger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        composition = CompositionRoot.getInstance()

        val notifier = composition.notifier(this);
        logger = composition.logger(notifier)

        try {
            source = ItemPicker(this, ::sourceSuccess)
//
////        val device = BluetoothDevice(this)
////        device.sendTextOverAVRCP()
////        device.artist = "test"

            player = findViewById(R.id.argmusicplayer)
            player.setOnPlaylistAudioChangedListener(this)
            player.setOnErrorListener(this)

            playerControls = listOf(findViewById(R.id.like), findViewById(R.id.dislike))

            vibrator = applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        catch (e: Exception) {
            logger.error(e)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!findViewById<CheckBox>(R.id.volume_button_triggers).isChecked) { } //pass

        else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            dislike(findViewById(R.id.dislike))
            vibrator.vibrateLong()
            return true
        }

        else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            like(findViewById(R.id.dislike))
            vibrator.vibrateLong()
            return true
        }

        return false
    }

    fun like(view: View) {
        val source = File(currentAudio!!.path)
        val target = File("$targetLike/${source.name}")
        source.renameTo(target)
    }

    fun dislike(view: View) {
        val source = File(currentAudio!!.path)
        val target = File("$targetDislike/${source.name}")
        source.renameTo(target)
    }

    fun browse(view: View) {
        playerControls.forEach { it.isEnabled = false }
        source.folder()
    }

    private fun setTarget(path: String) {
        val favorites = composition.options.rootDirectory + "/" + composition.options.automatedDirectory + "/Favorites"
        val recylebin = composition.options.rootDirectory + "/" + composition.options.automatedDirectory + "/Recycle Bin"

        if (path.startsWith(favorites)) {
            targetLike = "$favorites/More Interesting"
            targetDislike = "$favorites/Less Interesting"
        }

        else {
            targetLike = favorites
            targetDislike = recylebin
        }
    }

    private fun loadFolder(path: String) {
        val audioList = ArgAudioList(false)
        LocalFile(path).listFiles().forEach {
            audioList.add(ArgAudio.createFromFilePath(
                it.nameWithoutExtension.split(" - ")[0],
                it.nameWithoutExtension.split(" - ")[1],
                it.originalPath))
        }

        player.loadPlaylist(audioList);
    }

    private fun sourceSuccess(result: ActivityResult, file: File) {
        val filePath = file.absolutePath
        loadFolder(filePath)
        setTarget(filePath)
        playerControls.forEach { it.isEnabled = true }
    }

    override fun onPlaylistAudioChanged(playlist: ArgAudioList?, currentAudioIndex: Int) {
        currentAudio = playlist?.get(currentAudioIndex)
    }

    override fun onError(errorType: ErrorType?, description: String?) {
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