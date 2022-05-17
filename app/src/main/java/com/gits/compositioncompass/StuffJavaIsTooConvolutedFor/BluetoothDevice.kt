package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.getSystemService
import com.gits.compositioncompass.PlayerActivity


class BluetoothDevice(private val activity: AppCompatActivity) {

    private var audioManager: AudioManager?
    private var mediaSession: MediaSession

    var _artist: String = ""
    var artist: String
        get() = _artist
        set(value) {
            _artist = value

        }

    init {
        mediaSession = MediaSession(activity, "YourAppName")
        audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        setUpCallBack()
    }

    private fun setUpCallBack() {
        //capture media events like play, stop
        //you don't actually use these callbacks
        //but you have to have this in order to pretend to be a media application
        mediaSession.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                super.onPlay()
            }

            override fun onPause() {
                super.onPause()
            }

            override fun onSkipToNext() {
                super.onSkipToNext()
            }

            override fun onSkipToPrevious() {
                super.onSkipToPrevious()
            }

            override fun onStop() {
                super.onStop()
            }
        })
    }

    fun sendTextOverAVRCP() {
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(PlaybackState.STATE_PLAYING, 1, 1f, SystemClock.elapsedRealtime())
            .build()
        //set the metadata to send, this is the text that will be displayed
        //if the strings are too long they might be cut off
        //you need to experiment with the receiving device to know max length
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Titel")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Künstler")
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, "Album Künstler")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Album")
            .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, 123)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 456)
            .build()
        //setting this active makes the metadata you pass show up
        //other metadata from apps will not be shown
        mediaSession.setActive(true)
        mediaSession.setMetadata(metadata)
        mediaSession.setPlaybackState(state)
    }

    private fun clearText() {
        //if you display text, calling this will stop displaying it
        //if there is another app which is using AVRCP,
        //control will be handed off that app
        if (mediaSession != null) mediaSession.setActive(false)
    }
}