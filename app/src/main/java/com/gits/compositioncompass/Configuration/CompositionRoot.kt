package com.gits.compositioncompass.Configuration

import com.gits.compositioncompass.Queries.*
import QueryMode
import QuerySource
import android.app.Activity
import com.gits.compositioncompass.Downloader.YoutubeDownloader
import android.app.Application
import android.os.Environment
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Notifier

class CompositionRoot {

    val options: CompositionCompassOptions
    var query: IQuery //replaceable
    val downloader: YoutubeDownloader
    val appName: String

    private constructor(options: CompositionCompassOptions, activity: Activity, application: Application) {
        this.options = options
        
        downloader = YoutubeDownloader(options, application)
        query = SpotifyQuery(options) //default query
        appName = "Composition Compass"
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

    fun notifier(activity: Activity) : Notifier {
        return Notifier(activity, appName)
    }

    companion object {
        private var compositionRoot: CompositionRoot? = null

        fun getInstance(activity: Activity, application: Application): CompositionRoot {
            val extStoragePath = Environment.getExternalStorageDirectory().absolutePath
            val configFile = extStoragePath + "/Music/Pandora/config.ini"
            val options = CompositionCompassOptions(configFile)

            compositionRoot = compositionRoot ?: CompositionRoot(options, activity, application)
            return compositionRoot!!
        }

        fun getInstance(): CompositionRoot {
            return compositionRoot!!
        }
    }
}