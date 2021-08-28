import android.app.Application
import android.os.Environment

class CompositionRoot {

    val options: CompositionCompassOptions
    var query: IQuery //replaceable
    val downloader: YoutubeDownloader

    private constructor(options: CompositionCompassOptions, application: Application) {
        this.options = options

        downloader = YoutubeDownloader(options, application)
        query = SpotifyQuery(options) //default query
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

    companion object {
        fun getInstance(application: Application): CompositionRoot {
            val extStoragePath = Environment.getExternalStorageDirectory().absolutePath
            val configFile = extStoragePath + "/youtubedl-download/composition-compass.ini"
            val options = CompositionCompassOptions(configFile)

            return CompositionRoot(options, application)
        }
    }
}