package com.gits.compositioncompass.Configuration

import com.gits.compositioncompass.Queries.*
import QueryMode
import QuerySource
import android.app.Activity
import com.gits.compositioncompass.Downloader.YoutubeDownloader
import android.content.SharedPreferences
import android.os.Environment
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Logger
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Notifier

//CompositionRoot:  Configures its own components based on CompositionRootOptions
//Activities:       Merely acquire a reference to CompositionRoot via getInstance()
//                  Each Activity knows ALL functions the CompositionRoot offers
//                  CompositionRoot itself is "passed down" to all Activities,
//                  along with its contained CompositionRootOptions
//
//                  WE ACQUIRE A REFERENCE TO THE "BACK-END COMPOSITIONROOT" VIA getInstance()
//                  AND PASS IT DOWN TO ALL ACTIVITIES / MASKS. THE FRONT-END CAN AND SHOULD HAVE
//                  ITS OWN COMPOSITIONROOT. THE FRONT-END SHOULD MERELY "USE" THE BACK-END COMPOSITIONROOT,
//                  BUT NOT BE A PART OF IT.

class CompositionRoot {

    val preferencesReader: SharedPreferences
    var preferencesWriter: SharedPreferences.Editor
    val options: CompositionCompassOptions
    var query: IQuery //replaceable
    val downloader: YoutubeDownloader

    private constructor(options: CompositionCompassOptions, activity: Activity) {
        this.options = options

        downloader = YoutubeDownloader(options, activity)
        query = SpotifyQuery(options) //default query
        preferencesReader = activity.getSharedPreferences(options.packageName, 0)
        preferencesWriter = preferencesReader.edit();
    }

    fun changeQuerySource(source: QuerySource) {
        query =
            when(source) {
                QuerySource.Spotify -> SpotifyQuery(options)
                QuerySource.LastFM -> LastFMQuery(options)
                QuerySource.YouTube -> YouTubeQuery(options)
                QuerySource.File -> FileQuery(options)
            }
    }

    fun changeQueryMode(mode: QueryMode) {
        query.changeMode(mode)
    }

    fun logger(activity: Activity) : Logger {
        return Logger(options, Notifier(options, activity))
    }

    fun activity(activity: Activity) {

    }

    companion object {
        private var compositionRoot: CompositionRoot? = null

        fun getInstance(activity: Activity): CompositionRoot {
            if (compositionRoot == null) {
                val extStoragePath = Environment.getExternalStorageDirectory().absolutePath
                val configFile = extStoragePath + "/Music/Pandora/config.ini"
                val options = CompositionCompassOptions(configFile, activity)

                return CompositionRoot(options, activity)
            }

            else {
                return compositionRoot!!
            }
        }
    }
}