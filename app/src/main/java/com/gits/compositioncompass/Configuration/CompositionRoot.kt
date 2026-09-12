package com.gits.compositioncompass.Configuration

import com.gits.compositioncompass.Queries.*
import QueryMode
import QuerySource
import android.app.Activity
import com.gits.compositioncompass.Downloader.YoutubeDownloader
import android.content.SharedPreferences
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.ItemPicker
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Logger
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Notifier
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.SafStorage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    val options: CompositionCompassOptions
    val storage: SafStorage
    var activity: Activity
    lateinit var preferencesReader: SharedPreferences
    lateinit var preferencesWriter: SharedPreferences.Editor
    lateinit var query: IQuery //replaceable
    lateinit var downloader: YoutubeDownloader
    lateinit var logger: Logger
    lateinit var picker: ItemPicker

    private constructor(options: CompositionCompassOptions, storage: SafStorage, activity: Activity) {
        this.options = options
        this.storage = storage
        this.activity = activity

        initWithActivity(options, activity)
        initWithoutActivity(options)

        instance = this
    }

    //not dependant on activity (one-time only instantiation)
    private fun initWithoutActivity(options: CompositionCompassOptions) {
        query = SpotifyQuery(options, picker) //default query
    }

    //dependant on activity (need to be re-instantiated or updated once activity changes)
    private fun initWithActivity(options: CompositionCompassOptions, activity: Activity) {
        downloader = YoutubeDownloader(options, activity, storage)
        logger = Logger(options, storage, Notifier(options, activity))
        picker = ItemPicker(options, activity)
        preferencesReader = activity.getSharedPreferences(options.packageName, 0)
        preferencesWriter = preferencesReader.edit()
    }

    fun changeQuerySource(source: QuerySource) {
        query =
            when(source) {
                QuerySource.Spotify -> SpotifyQuery(options, picker)
                QuerySource.LastFM -> LastFMQuery(options, picker)
                QuerySource.Pandora -> PandoraQuery(options, picker)
                QuerySource.PandoraRest -> PandoraRestQuery(options, picker)
                QuerySource.YouTube -> YouTubeQuery(options)
                QuerySource.File -> FileQuery(options, storage)
            }
    }

    fun changeQueryMode(mode: QueryMode) {
        query.changeMode(mode)
    }

    companion object {
        private var instance: CompositionRoot? = null

        //only call once PermissionManager.hasStorageAccess() is true - MainActivity's
        //existing init flow already guarantees that
        fun initialize(newActivity: Activity) : CompositionRoot {
            if (instance == null) {
                val storage = SafStorage(newActivity)
                val options = CompositionCompassOptions(newActivity, storage)

                return CompositionRoot(options, storage, newActivity)
            }

            else if (instance!!.activity != newActivity) {
                //re-init with currently displayed activity
                instance!!.initWithActivity(instance!!.options, newActivity)
                instance!!.activity = newActivity
                return instance!!
            }

            else
                return instance!!
        }
    }
}
