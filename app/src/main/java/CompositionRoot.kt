import android.app.Application
import android.os.Environment

class CompositionRoot {

    val options: CompositionCompassOptions
    var query: IQuery //replaceable
    val youtube: YoutubeDownloader

    private constructor(options: CompositionCompassOptions, application: Application) {
        this.options = options

        youtube = YoutubeDownloader(options, application)
        query = SpotifyQuery(options) //default query
    }

    fun changeQuery(source: QuerySource) {
        query =
            when(source) {
                QuerySource.Spotify -> SpotifyQuery(options)
                QuerySource.LastFM -> LastFMQuery(options)
                QuerySource.YouTube -> YouTubeQuery(options)
                QuerySource.File -> FileQuery(options)
            }
    }

    companion object {
        fun getInstance(application: Application): CompositionRoot {
            val extStoragePath = Environment.getExternalStorageDirectory().absolutePath
            val configFile = extStoragePath + "/youtubedl-download/composition-compass.ini"
            val options = CompositionCompassOptions(configFile)

            return CompositionRoot(options, application)
        }
    }
}