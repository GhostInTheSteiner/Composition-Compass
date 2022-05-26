package com.gits.compositioncompass

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.os.Vibrator
import android.view.KeyEvent
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
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
import com.gits.compositioncompass.databinding.ActivityPlayerBinding
import vibrateLong
import vibrateVeryLong
import java.io.File


class PlayerActivity : AppCompatActivity(), OnPlaylistAudioChangedListener, OnErrorListener,
    CompoundButton.OnCheckedChangeListener {
    private var ignoreUp: Boolean = false
    private var favorites: String = ""
    private var recylebin: String = ""
    private var favoritesMoreInteresting: String = ""
    private var favoritesLessInteresting: String = ""
    private var targetLike: String = ""
    private var targetDislike: String = ""
    private var currentAudio: ArgAudio? = null
    private lateinit var preferencesReader: SharedPreferences
    private lateinit var preferencesWriter: SharedPreferences.Editor
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var powerManager: PowerManager
    private lateinit var audioManager: AudioManager
    private lateinit var source: ItemPicker
    private lateinit var vibrator: Vibrator
    private lateinit var player: ArgPlayerFullScreenView
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerControls: List<View>
    private lateinit var composition: CompositionRoot
    private lateinit var logger: Logger

    override fun onPause() {
        super.onPause();
        mute_ifVolumeTrigger()
    }

    override fun onResume() {
        super.onResume();
        CompositionRoot.getInstance(this)
        unmute_ifVolumeTrigger()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        composition = CompositionRoot.getInstance(this)
        logger = composition.logger

        try {
            preferencesWriter = composition.preferencesWriter
            preferencesReader = composition.preferencesReader

            val automated = composition.options.rootDirectory + "/" + composition.options.automatedDirectory

            recylebin = "$automated/Recycle Bin"
            favorites = "$automated/Favorites"
            favoritesMoreInteresting = "$favorites/More Interesting"
            favoritesLessInteresting = "$favorites/Less Interesting"

            listOf(recylebin, favorites, favoritesMoreInteresting, favoritesLessInteresting)
                .forEach { File(it).mkdirs() }

            source = ItemPicker(this, ::sourceSuccess, ::sourceError)
//
////        val device = BluetoothDevice(this)
////        device.sendTextOverAVRCP()
////        device.artist = "test"

            playerControls = listOf(findViewById(R.id.like), findViewById(R.id.dislike))
            playerControls.forEach { it.isEnabled = false }

            vibrator = applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");
            wakeLock.acquire()

            player = findViewById(R.id.argmusicplayer)
            player.setOnPlaylistAudioChangedListener(this)
            player.setOnErrorListener(this)
            player.enableNotification(this)

            val triggers = findViewById<CheckBox>(R.id.volume_button_triggers)
            triggers.setOnCheckedChangeListener(this)

            val triggersValue = preferencesReader.getBoolean("view:${triggers.id}", false)
            triggers.isChecked = triggersValue
        }
        catch (e: Exception) {
            logger.error(e)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = onKeyPress(keyCode, event, false)
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = onKeyPress(keyCode, event, true)

    fun onKeyPress(keyCode: Int, event: KeyEvent?, keyDown: Boolean): Boolean {
        val keyUp = !keyDown

        if (keyDown && keyCode == KeyEvent.KEYCODE_BACK) close()

        //handle volume buttons
        else if (findViewById<CheckBox>(R.id.volume_button_triggers).isChecked) {

            if (keyUp && keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                unmute() //station still open, restore previous volume

                if (ignoreUp) ignoreUp = false
                else {
                    // like(findViewById(R.id.like))
                    vibrator.vibrateLong()
                }

                return true
            }

            else if (keyUp && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (ignoreUp) ignoreUp = false
                else {
                    unmute() //station is closed anyway, no reason to restore previous volume
                    // dislike(findViewById(R.id.dislike))
                    vibrator.vibrateLong()
                }

                return true
            }

            else if (keyDown && keyCode == KeyEvent.KEYCODE_VOLUME_UP && event!!.repeatCount == 5) {
                ignoreUp = true
                unmute()
                // like(findViewById(R.id.like), true)
                vibrator.vibrateLong()
                return true
            }

            else if (keyDown && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event!!.repeatCount == 5) {
                ignoreUp = true
                close()
                vibrator.vibrateVeryLong()
                return true
            }
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
        source.folder()
    }

    fun close(view: View) = close()

    fun close() {
        mute()
        player.stop()
        wakeLock.release()
        finish()
    }

    private fun mute() = audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
    private fun unmute() = audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 4, AudioManager.FLAG_SHOW_UI)

    private fun setTarget(path: String) {

        if (path.startsWith(favorites)) {
            targetLike = favoritesMoreInteresting
            targetDislike = favoritesLessInteresting
        }

        else {
            targetLike = favorites
            targetDislike = recylebin
        }
    }

    private fun playFolder(path: String) {
        val audioList = ArgAudioList(false)
        LocalFile(path).listFiles().forEach {
            audioList.add(ArgAudio.createFromFilePath(
                it.nameWithoutExtension.split(" - ").first(),
                it.nameWithoutExtension.split(" - ").last(),
                it.originalPath))
        }

        player.playPlaylist(audioList)
        unmute_ifVolumeTrigger()
    }

    private fun unmute_ifVolumeTrigger() {
        val triggers = findViewById<CheckBox>(R.id.volume_button_triggers)
        if (triggers.isChecked) unmute()
    }

    private fun mute_ifVolumeTrigger() {
        val triggers = findViewById<CheckBox>(R.id.volume_button_triggers)
        if (triggers.isChecked) mute()
    }

    private fun sourceSuccess(result: ActivityResult, file: File) {
        val filePath = file.absolutePath
        setTarget(filePath)
        playFolder(filePath)
        playerControls.forEach { it.isEnabled = true }
    }


    private fun sourceError(activityResult: ActivityResult) {
        logger.error(Exception("Couldn't select folder: ${activityResult.resultCode}\n\n$activityResult"))
    }

    override fun onPlaylistAudioChanged(playlist: ArgAudioList?, currentAudioIndex: Int) {
        currentAudio = playlist?.get(currentAudioIndex)
    }

    override fun onError(errorType: ErrorType?, description: String?) {
        logger.error(Exception("Error during playback: $errorType: $description"))
    }

    override fun onCheckedChanged(checkBox: CompoundButton?, checked: Boolean) {
        when (checkBox!!.id) {
            R.id.volume_button_triggers -> {
                //so we hear the audio on the bluetooth receiver (usually a car radio)
                if (checked) unmute()
                else mute()

                preferencesWriter.putBoolean("view:${R.id.volume_button_triggers}", checked)
                preferencesWriter.apply()
            }
        }
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