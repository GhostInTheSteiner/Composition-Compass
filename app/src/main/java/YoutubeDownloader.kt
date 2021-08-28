import android.app.Application
import com.adamratzman.spotify.models.Track
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.name

class YoutubeDownloader {
    private var application: Application
    private var options: CompositionCompassOptions
    private var dl: YoutubeDL
    private var ffmpeg: FFmpeg
    private var jobs: List<Job>

    constructor(options: CompositionCompassOptions, application: Application) {
        this.options = options
        this.application = application

        dl = YoutubeDL.getInstance()
        dl.init(application)

        ffmpeg = FFmpeg.getInstance()
        ffmpeg.init(application)

        jobs = listOf()
    }

    suspend fun start(directories: List<TargetDirectory>, onUpdate: (DownloadStatus) -> Unit, onFailure: (String, Exception) -> Unit) {

        val status = DownloadStatus()
        val tracksGrouped: MutableList<MutableList<Pair<String, Track>>> = mutableListOf()
        var j = -1

        directories.forEachIndexed() { index, directory ->
            directory.tracks.forEach { track ->
                if (index % options.maxParallelDownloads == 0) {
                    tracksGrouped.add(mutableListOf())
                    j++
                }

                //use a sufficiently unique key: download path + track name
                var key = Pair(directory.path, track)

                status.updateJob(key, 0.0F)

                //group (to allow for maxParallelDownloads) and flatten directories
                tracksGrouped[j].add(key)
            }
        }

        tracksGrouped.forEach {
            jobs =
                it.map { trackPair ->

                    val trackName = trackPair.second.name
                    val directory = trackPair.first

                    val searchQuery =
                        trackPair.second.name + " " +
                        trackPair.second.artists.map { it.name }.joinToString(" ")

                    GlobalScope.launch(newSingleThreadContext("youtubedl-download")) {
                        runYoutubeDL(
                            searchQuery,
                            directory,
                            onUpdate = { progress ->
                                status.updateJob(trackPair, progress)
                                onUpdate(status)
                            },
                            onFailure = { exception ->
                                onFailure(trackName, exception)
                            })
                    }
                }

            jobs.joinAll()
        }
    }
    
    fun update() {
        dl.updateYoutubeDL(application);
    }

    private fun runYoutubeDL(searchQuery: String, directory: String, onUpdate: (Float) -> Unit, onFailure: (Exception) -> Unit) {
        try {
            val request = YoutubeDLRequest(searchQuery)

            val archiveDir = File(options.archiveDirectory)
            val downloadDir = File(directory)

            downloadDir.mkdirs()
            archiveDir.mkdirs()

            request.addOption("--extract-audio")
            request.addOption("--ignore-errors")

            request.addOption("--output", downloadDir.absolutePath + "/%(title)s.%(ext)s")
            request.addOption("--match-filter", "duration < 600")
            request.addOption("--match-title", "^((?!(${options.exceptions})).)*$")
            //for meta-data keep in mind that adding it might break the CarPlayer in Tasker!

            val directoryName = Path(directory).name

            //expect URL for playlists
            if (directoryName !in listOf(DownloadFolder.Playlists.folderName))
                request.addOption("--default-search", "ytsearch")

            //also fetch already downloaded tracks
            if (directoryName !in listOf(DownloadFolder.Playlists.folderName, DownloadFolder.Artists.folderName, DownloadFolder.Albums.folderName))
                request.addOption("--download-archive", archiveDir.absolutePath + "/downloaded.txt")

            dl.execute(request) { progress, etaInSeconds -> onUpdate(progress) }
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    fun cancel() {
        jobs.forEach { it.cancel() }
    }
}