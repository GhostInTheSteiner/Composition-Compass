package com.gits.compositioncompass

import QuerySource
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Vibrator
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import com.gits.compositioncompass.Configuration.CompositionRoot
import com.gits.compositioncompass.Queries.LastFMQuery
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.BluetoothDevice
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.ItemPicker
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Logger
import com.gits.compositioncompass.databinding.ActivityPlayerBinding
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import vibrateLong
import vibrateVeryLong

/** Replaces ArgPlayer's ArgAudio. Plays directly from the SAF content:// URI via
 *  MediaPlayer.setDataSource(context, uri) - no file descriptor juggling needed. */
data class Track(val uri: Uri, val artist: String, val title: String) {
    val path: String get() = uri.toString()
}

class PlayerActivity : AppCompatActivity(),
    CompoundButton.OnCheckedChangeListener, DialogInterface.OnClickListener,
    View.OnLongClickListener {
    private var likeMoved: Boolean = false
    private var dislikeMoved: Boolean = false
    private var playerValue: String = ""
    private var triggersValue: Boolean = false
    private var ignoreUp: Boolean = false
    private var targetLike: String = ""
    private var targetLikeMoreInteresting = ""
    private var targetDislike: String = ""
    private var currentAudio: Track? = null
    private var tracks: List<Track> = emptyList()
    private var currentIndex: Int = -1
    private var prepared: Boolean = false
    private var consecutiveErrors: Int = 0
    private lateinit var directories: List<String>
    private lateinit var bluetoothDevice: BluetoothDevice
    private lateinit var query: LastFMQuery
    private lateinit var preferencesReader: SharedPreferences
    private lateinit var preferencesWriter: SharedPreferences.Editor
    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private lateinit var source: ItemPicker
    private lateinit var vibrator: Vibrator
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerControls: List<View>
    private lateinit var composition: CompositionRoot
    private lateinit var logger: Logger

    private val mediaPlayer = MediaPlayer()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var textTitle: TextView
    private lateinit var textArtist: TextView
    private lateinit var textCurrentTime: TextView
    private lateinit var textTotalTime: TextView

    override fun onPause() {
        super.onPause();
        if (powerManager.isInteractive)
            mute_ifVolumeTrigger()
    }

    override fun onResume() {
        super.onResume();
        CompositionRoot.initialize(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        unmute_ifVolumeTrigger()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        composition = CompositionRoot.initialize(this)
        logger = composition.logger

        try {
            preferencesWriter = composition.preferencesWriter
            preferencesReader = composition.preferencesReader

            composition.changeQuerySource(QuerySource.LastFM)

            query = composition.query as LastFMQuery
            source = composition.picker

            playerControls = listOf(findViewById(R.id.like), findViewById(R.id.dislike))
            playerControls.forEach { it.isEnabled = false }

            bluetoothDevice = BluetoothDevice(this)

            vibrator = applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

            //get views
            val triggers = findViewById<CheckBox>(R.id.volume_button_triggers)
            val description = findViewById<EditText>(R.id.description)
            val like = findViewById<Button>(R.id.like)

            seekBar = findViewById(R.id.seek_bar)
            btnPlayPause = findViewById(R.id.btn_play_pause)
            textTitle = findViewById(R.id.track_title)
            textArtist = findViewById(R.id.track_artist)
            textCurrentTime = findViewById(R.id.current_time)
            textTotalTime = findViewById(R.id.total_time)

            findViewById<ImageButton>(R.id.btn_prev).setOnClickListener { skipToPrev() }
            findViewById<ImageButton>(R.id.btn_backward).setOnClickListener { seekBy(-5000) }
            btnPlayPause.setOnClickListener { togglePlayPause() }
            findViewById<ImageButton>(R.id.btn_forward).setOnClickListener { seekBy(5000) }
            findViewById<ImageButton>(R.id.btn_next).setOnClickListener { skipToNext() }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && prepared) {
                        mediaPlayer.seekTo(progress)
                        textCurrentTime.text = formatTime(progress)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })

            mediaPlayer.setOnPreparedListener { mp ->
                prepared = true
                consecutiveErrors = 0
                seekBar.max = mp.duration
                textTotalTime.text = formatTime(mp.duration)
                mp.start()
                updatePlayPauseButton()
                handler.post(progressUpdater)
            }
            mediaPlayer.setOnCompletionListener {
                // ArgPlayer default: playlist repeats forever
                if (tracks.isNotEmpty())
                    playTrack((currentIndex + 1) % tracks.size)
                else
                    updatePlayPauseButton()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                // continuePlaylistWhenError() equivalent: skip to next track
                logger.error(Exception("MediaPlayer error $what/$extra on ${currentAudio?.path}"))
                consecutiveErrors++
                if (tracks.isNotEmpty() && consecutiveErrors < tracks.size)
                    playTrack((currentIndex + 1) % tracks.size)
                else
                    stopPlayback()
                true
            }

            //get preferences
            triggersValue = preferencesReader.getBoolean("view:${triggers.id}", false)
            playerValue = preferencesReader.getString("view:argmusicplayer", "")!!

            triggers.setOnCheckedChangeListener(this)
            triggers.isChecked = triggersValue

            description.setVerticalScrollBarEnabled(true)
            description.setMovementMethod(ScrollingMovementMethod())

            like.setOnLongClickListener(this)

            setUpCallBack()

            if (playerValue.isNotEmpty())
                AlertDialog.Builder(this).let {
                    //vice-versa so we can instantly press "Load" without waiting for the volume-slider to disappear
                    it.setPositiveButton("Ignore", this)
                    it.setNegativeButton("Load", this)
                    it.setMessage("Should I load the last station for you?")
                    it.setTitle("Load last station")
                    it.show()
                }

        } catch (e: Exception) {
            logger.error(e)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressUpdater)
        mediaPlayer.release()
        super.onDestroy()
    }

    //region <playback>

    private fun playTrack(index: Int) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        currentIndex = index
        val track = tracks[index]
        currentAudio = track
        prepared = false
        updateTrackViews(track)
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(this, track.uri)
            mediaPlayer.prepareAsync()
        } catch (e: Exception) {
            logger.error(Exception("Couldn't load track: ${track.uri}", e))
            consecutiveErrors++
            playTrack((index + 1) % tracks.size)
            return
        }
        onTrackChanged(track)
    }

    private fun togglePlayPause() {
        if (!prepared) return
        if (mediaPlayer.isPlaying)
            mediaPlayer.pause()
        else {
            mediaPlayer.start()
            handler.post(progressUpdater)
        }
        updatePlayPauseButton()
    }

    private fun pausePlayback() {
        if (prepared && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            updatePlayPauseButton()
        }
    }

    private fun resumePlayback() {
        if (prepared && !mediaPlayer.isPlaying) {
            mediaPlayer.start()
            updatePlayPauseButton()
            handler.post(progressUpdater)
        }
    }

    private fun stopPlayback() {
        if (prepared && mediaPlayer.isPlaying) mediaPlayer.pause()
        if (prepared) try { mediaPlayer.seekTo(0) } catch (e: Exception) {}
        updatePlayPauseButton()
    }

    private fun seekBy(milliseconds: Int) {
        if (!prepared) return
        val target = (mediaPlayer.currentPosition + milliseconds).coerceIn(0, mediaPlayer.duration)
        mediaPlayer.seekTo(target)
        seekBar.progress = target
        textCurrentTime.text = formatTime(target)
    }

    private fun skipToNext() {
        if (tracks.isNotEmpty())
            playTrack((currentIndex + 1) % tracks.size)
    }

    private fun skipToPrev() {
        if (!prepared || tracks.isEmpty()) return
        if (mediaPlayer.currentPosition > 3000)
            mediaPlayer.seekTo(0)
        else
            playTrack(if (currentIndex <= 0) tracks.lastIndex else currentIndex - 1)
    }

    private fun skipToEnd() {
        // seeking to the end triggers onCompletion, which advances to the next track
        if (prepared) mediaPlayer.seekTo(mediaPlayer.duration) else skipToNext()
    }

    private fun updatePlayPauseButton() {
        btnPlayPause.setImageResource(
            if (mediaPlayer.isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun updateTrackViews(track: Track) {
        textTitle.text = track.title
        textArtist.text = track.artist
        seekBar.progress = 0
        textCurrentTime.text = formatTime(0)
        textTotalTime.text = ""
    }

    private fun formatTime(ms: Int): String =
        "%d:%02d".format(ms / 1000 / 60, ms / 1000 % 60)

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (prepared && mediaPlayer.isPlaying) {
                seekBar.progress = mediaPlayer.currentPosition
                textCurrentTime.text = formatTime(mediaPlayer.currentPosition)
                handler.postDelayed(this, 500)
            }
        }
    }

    //endregion <playback>

    private fun setDirectories(options: CompositionCompassOptions, playlistPath: String) {
        if (playlistPath.startsWith("${options.stationsDirectoryPath}/!Artists")) {
            targetDislike = "$playlistPath/Less Interesting"
            targetLike = "$playlistPath/More Interesting"
            targetLikeMoreInteresting = "$playlistPath/More Interesting"
        }

        else if (playlistPath.startsWith(options.favoritesDirectoryPath)) {
            targetDislike = options.lessInterestingDirectoryPath
            targetLike = options.moreInterestingDirectoryPath
            targetLikeMoreInteresting = options.moreInterestingDirectoryPath
        }

        else {
            targetDislike = options.recyclebinDirectoryPath
            targetLike = options.favoritesDirectoryPath
            targetLikeMoreInteresting = options.moreInterestingDirectoryPath
        }

        listOf(targetDislike, targetLike, targetLikeMoreInteresting)
            .forEach { source.storage.getOrCreateDirectory(it) }
    }

    private fun setUpCallBack() {
        bluetoothDevice.mediaSession.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        bluetoothDevice.mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                super.onPlay()
                resumePlayback()
            }

            override fun onPause() {
                super.onPause()

                // abuse the play button for that feature; it's just a lot more important...
                if (findViewById<CheckBox>(R.id.volume_button_triggers).isChecked)
                    like(findViewById<Button>(R.id.like), true)
                else if (mediaPlayer.isPlaying)
                    pausePlayback()
                else
                    resumePlayback()
            }

            override fun onSkipToNext() {
                super.onSkipToNext()

                if (findViewById<CheckBox>(R.id.volume_button_triggers).isChecked)
                    like(findViewById<Button>(R.id.like))
                else
                    skipToEnd()
            }

            override fun onFastForward() {
                super.onFastForward()

                if (findViewById<CheckBox>(R.id.volume_button_triggers).isChecked)
                    like(findViewById<Button>(R.id.like), true)
                else
                    seekBy(5000)
            }

            override fun onSkipToPrevious() {
                super.onSkipToPrevious()

                if (findViewById<CheckBox>(R.id.volume_button_triggers).isChecked)
                    dislike(findViewById<Button>(R.id.dislike))
                else if (prepared)
                    mediaPlayer.seekTo(0)
            }

            override fun onRewind() {
                super.onRewind()

                if (findViewById<CheckBox>(R.id.volume_button_triggers).isChecked)
                    close()
                else
                    seekBy(-5000)
            }

            override fun onStop() {
                super.onStop()
                close()
            }
        })
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        onKeyPress(keyCode, event, false)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        onKeyPress(keyCode, event, true)

    fun onKeyPress(keyCode: Int, event: KeyEvent?, keyDown: Boolean): Boolean {
        val keyUp = !keyDown

        if (keyDown && keyCode == KeyEvent.KEYCODE_BACK) close()

        //handle volume buttons
        else if (findViewById<CheckBox>(R.id.volume_button_triggers).isChecked) {

            if (keyUp && keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                unmute() //station still open, restore previous volume

                if (ignoreUp) ignoreUp = false
                else like(findViewById(R.id.like))

                return true

            } else if (keyUp && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (ignoreUp) ignoreUp = false
                else {
                    unmute() //station is closed anyway, no reason to restore previous volume
                    dislike(findViewById(R.id.dislike))
                }

                return true

            } else if (keyDown && keyCode == KeyEvent.KEYCODE_VOLUME_UP && event!!.repeatCount == 5) {
                ignoreUp = true
                unmute()
                like(findViewById(R.id.like), true)

                return true

            } else if (keyDown && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event!!.repeatCount == 5) {
                ignoreUp = true
                close()

                return true

            }
        }

        return false
    }

    override fun onLongClick(button: View?): Boolean {
        like(findViewById(R.id.like), true)
        return true
    }

    fun like(view: View) = like(view, false)

    fun like(view: View, toMoreInteresting: Boolean = false) {

        //only skip track on second click, so we can listen to the current one until the end
        if (!likeMoved) {
            val sourceUri = Uri.parse(currentAudio!!.path)
            val target = if (toMoreInteresting) targetLikeMoreInteresting else targetLike

            likeMoved = source.storage.moveFile(sourceUri, target)

            if (likeMoved && findViewById<CheckBox>(R.id.volume_button_triggers).isChecked) {
                vibrator.vibrateLong()

                //acoustical indicator that track was moved.
                //this isn't obvious to the listener, as we're not skipping to the next yet
                GlobalScope.launch {
                    runOnUiThread { pausePlayback() }
                    delay(500)
                    runOnUiThread { resumePlayback() }
                }

            }
        }

        else {
            skipToEnd()
            likeMoved = false
        }
    }

    fun dislike(view: View) {

        //only skip track on second click, so we can listen to the current one until the end
        if (!dislikeMoved) {

            val sourceUri = Uri.parse(currentAudio!!.path)

            dislikeMoved = source.storage.moveFile(sourceUri, targetDislike)

            if (dislikeMoved && findViewById<CheckBox>(R.id.volume_button_triggers).isChecked) {
                vibrator.vibrateLong()

                //acoustical indicator that track was moved.
                //this isn't obvious to the listener, as we're not skipping to the next yet
                GlobalScope.launch {
                    runOnUiThread { pausePlayback() }
                    delay(500)
                    runOnUiThread { resumePlayback() }
                }

            }
        }

        else {
            skipToEnd()
            dislikeMoved = true
        }
    }

    fun browse(view: View) {
        GlobalScope.launch {
            val relativePath = source.folder()

            runOnUiThread {
                if(relativePath == null) sourceError()
                else sourceSuccess(relativePath)
            }
        }
    }

    fun close(view: View) = close()

    fun close() {
        mute()
        stopPlayback()
        logger.notifier.cancelAll()

        if (findViewById<CheckBox>(R.id.volume_button_triggers).isChecked)
            vibrator.vibrateVeryLong()

        finish()
    }

    private fun mute(showUI: Int = AudioManager.FLAG_SHOW_UI) =
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, showUI)

    private fun unmute(showUI: Int = AudioManager.FLAG_SHOW_UI) =
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, composition.options.playerVolumeLevel, showUI)

    //path is relative to the app's main storage tree (e.g. "Stations/!Similar (...)"),
    //same convention as options.stationsDirectoryPath etc. Lists via SafStorage, not
    //raw File - scoped storage blocks direct filesystem reads of the SAF tree even with
    //a valid grant, which is why this used to silently produce an empty playlist.
    private fun playFolder(path: String) {
        val newTracks = mutableListOf<Track>()

        source.storage.listFiles(path)
            .sortedBy { (it.name ?: "").endsWith(".part") }
            .forEach { file ->
                val name = file.name ?: return@forEach
                val nameWithoutExtension = name.substringBeforeLast('.')
                val uri = Uri.parse(file.uri.toString())

                // MediaPlayer opens its own handle via setDataSource(context, uri);
                // we only open one here to validate the grant before adding the track.
                val fileDescriptor: ParcelFileDescriptor? = try {
                    contentResolver.openFileDescriptor(uri, "r")
                } catch (e: Exception) {
                    logger.error(Exception("Failed to open file descriptor for $uri", e))
                    return@forEach
                }

                if (fileDescriptor == null) {
                    logger.error(Exception("File descriptor is null for $uri"))
                    return@forEach
                }
                fileDescriptor.close()

                nameWithoutExtension.split(" - ").let { fileParts ->
                    val regex = Regex("(\\(|\\{|\\<|\\[| ft\\.? | feat\\.? )")
                    var singer = ""
                    var track = ""

                    if (fileParts.count() > 2) {
                        singer = fileParts.first()
                        track = fileParts.drop(1).joinToString()
                    } else if (fileParts.count() > 1) {
                        singer = fileParts.first().split(regex).first()
                        track = fileParts.last()
                    } else if (fileParts.count() > 0) {
                        singer = fileParts.first().split(regex).first()
                        track = fileParts.first().split(regex).last()
                    } else {
                        singer = "Unknown Artist"
                        track = "Unknown Track"
                    }

                    singer = singer.trimStart('!')
                    track = track.replace("\\.(webm|mp3|mp4|m4a|opus|wav)$".toRegex(), "")

                    newTracks.add(Track(uri, singer, track))
                }
            }

        tracks = newTracks
        stopPlayback()
        playTrack(0)

        unmute_ifVolumeTrigger()
        preferencesWriter.putString("view:argmusicplayer", path)
        preferencesWriter.apply()
    }

    private fun unmute_ifVolumeTrigger(showUI: Int = AudioManager.FLAG_SHOW_UI) {
        val triggers = findViewById<CheckBox>(R.id.volume_button_triggers)
        if (triggers.isChecked) unmute(showUI)
    }

    private fun mute_ifVolumeTrigger(showUI: Int = AudioManager.FLAG_SHOW_UI) {
        val triggers = findViewById<CheckBox>(R.id.volume_button_triggers)
        if (triggers.isChecked) mute(showUI)
    }

    private fun sourceSuccess(filePath: String) {
        setDirectories(composition.options, filePath)
        playFolder(filePath)
        playerControls.forEach { it.isEnabled = true }
    }

    private fun sourceError() {
        logger.error(Exception("Couldn't select folder!"))
    }

    private fun onTrackChanged(track: Track) {
        try {
            likeMoved = false
            dislikeMoved = false

            bluetoothDevice.sendAVRCP(track.title, track.artist, track.artist, track.artist)

            GlobalScope.launch(newSingleThreadContext("search-artist")) {
                try {
                    runOnUiThread {
                        findViewById<TextView>(R.id.description_title).text = "Artist"
                        findViewById<TextView>(R.id.description).text = ""
                        findViewById<TextView>(R.id.genres).text = ""
                    }

                    query.searchArtist(track.artist, true).first().let {
                        runOnUiThread {
                            findViewById<TextView>(R.id.description_title).text = it.name
                            findViewById<TextView>(R.id.description).text = it.biography
                            findViewById<TextView>(R.id.genres).text = it.genres.joinToString()
                        }
                    }
                } catch (e: Exception) {
                    logger.warn(Exception("Couldn't find artist info", e))
                }
            }
        }
        catch (e: Exception) {
            logger.error(Exception("Couldn't change playlist audio", e))
        }
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

    override fun onClick(dialog: DialogInterface?, which: Int) {
        when (which) {
            DialogInterface.BUTTON_POSITIVE -> {} // ignore
            DialogInterface.BUTTON_NEGATIVE -> sourceSuccess(playerValue)
        }
    }
}