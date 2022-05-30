package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.getSystemService
import com.arges.sepan.argmusicplayer.PlayerViews.ArgPlayerLargeView
import com.gits.compositioncompass.PlayerActivity


class BluetoothDevice(private val activity: Activity) {

    var audioManager: AudioManager?
    var mediaSession: MediaSession

    init {
        mediaSession = MediaSession(activity, "YourAppName")
        audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
    }

    fun sendAVRCP(title: String, artist: String, albumArtist: String, album: String) {
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
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, albumArtist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, 10)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 300)
            .build()
        //setting this active makes the metadata you pass show up
        //other metadata from apps will not be shown
        mediaSession.setActive(true)
        mediaSession.setMetadata(metadata)
        mediaSession.setPlaybackState(state)
    }

    fun sendAVRCP2(title: String, artist: String, albumArtist: String, album: String) {
        val avrcp = Intent("com.android.music.metachanged")
        avrcp.putExtra("track", title)
        avrcp.putExtra("artist", artist)
        avrcp.putExtra("album", album)
        activity.sendBroadcast(avrcp)
    }

    private fun clearText() {
        //if you display text, calling this will stop displaying it
        //if there is another app which is using AVRCP,
        //control will be handed off that app
        if (mediaSession != null) mediaSession.setActive(false)
    }
}