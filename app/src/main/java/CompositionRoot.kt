import android.app.Application
import android.os.Environment

class CompositionRoot {

    var query: IQuery //replaceable
    val youtube: YoutubeDownloader

    private constructor(options: CompositionCompassOptions, application: Application) {
        youtube = YoutubeDownloader(options, application)
        query = SpotifyQuery(options) //default query
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