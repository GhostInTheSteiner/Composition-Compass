import android.app.Application
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

    suspend fun start(targetDirectories: List<TargetDirectory>, onUpdate: (DownloadStatus) -> Unit, onFailure: (String, Exception) -> Unit) {

        val status = DownloadStatus()
        val queriesGrouped: MutableList<MutableList<Pair<String, SearchQuery>>> = mutableListOf()
        var indexGroup = -1
        var indexTotal = 0

        targetDirectories.forEach() { directory ->
            directory.searchQueries.forEach { search ->
                if (indexTotal % options.maxParallelDownloads == 0) {
                    queriesGrouped.add(mutableListOf())
                    indexGroup++
                }

                //use a sufficiently unique key: download path + search
                var key = Pair(directory.targetPath, search)

                status.updateJob(key, 0.0F)

                //group (to allow for maxParallelDownloads) and flatten
                queriesGrouped[indexGroup].add(key)
                indexTotal++
            }
        }

        queriesGrouped.forEach {
            jobs =
                it.map { trackPair ->

                    val searchQuery = trackPair.second.toString()
                    val directory = trackPair.first

                    GlobalScope.launch(newSingleThreadContext("youtubedl-download")) {
                        runYoutubeDL(
                            searchQuery,
                            directory,
                            onUpdate = { progress ->
                                status.updateJob(trackPair, progress)
                                onUpdate(status)
                            },
                            onFailure = { exception ->
                                onFailure(searchQuery, exception)
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

            val directoryNames = directory.split("/").reversed().take(2)

            /*
            Single Video:   /Playlists/!Single Downloads/Avicii - Levels HQ Upload.opus
            PLaylist:       /Playlists/Complete Discography Digital Daggers/<list_of_tracks>
            Search Query:   /Playlists/best techno songs 2021/<list_of_tracks>
            */

            if (searchQuery.trim().startsWith("http://") || searchQuery.trim().startsWith("https://")) { }
                //pass =>   video or playlist; search with URL

            else if (directoryNames.any { it.equals(DownloadFolder.Playlists.folderName) })
                //          search query; download first 50 results
                request.addOption("--default-search", "ytsearch50")
            else
                //          other download mode; download single track only
                request.addOption("--default-search", "ytsearch")

            if (directoryNames.any { it in listOf(DownloadFolder.Playlists.folderName, DownloadFolder.Artists.folderName, DownloadFolder.Albums.folderName)}) { }
                //pass => redownloads allowed
            else
                request.addOption("--download-archive", archiveDir.absolutePath + "/downloaded.txt")

            dl.execute(request) { progress, etaInSeconds -> onUpdate(progress) }

        } catch (e: Exception) {
            onFailure(e)
        }
    }

    suspend fun cancel() {
        jobs.forEach { it.cancel() }
    }
}