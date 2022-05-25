package com.gits.compositioncompass.Configuration

import com.gits.compositioncompass.Queries.*
import QueryMode
import QuerySource
import com.gits.compositioncompass.Downloader.YoutubeDownloader
import android.app.Application
import android.os.Environment
import androidx.appcompat.app.AppCompatActivity
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Logger
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Notifier
import java.io.File

class CompositionRoot {

    val options: CompositionCompassOptions
    var query: IQuery //replaceable
    val downloader: YoutubeDownloader
    val appName: String

    private constructor(options: CompositionCompassOptions, application: Application) {
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

    fun notifier(activity: AppCompatActivity) : Notifier {
        return Notifier(activity, appName)
    }

    fun logger(notifier: Notifier) : Logger {
        return Logger(notifier, File(options.rootDirectory  + "/" + options.logName))
    }

    companion object {
        private var compositionRoot: CompositionRoot? = null

        fun getInstance(application: Application): CompositionRoot {
            val extStoragePath = Environment.getExternalStorageDirectory().absolutePath
            val configFile = extStoragePath + "/Music/Pandora/config.ini"
            val options = CompositionCompassOptions(configFile)

            compositionRoot = compositionRoot ?: CompositionRoot(options, application)
            return compositionRoot!!
        }

        fun getInstance(): CompositionRoot {
            return compositionRoot!!
        }
    }
}