import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.io.File

class YoutubeDownloader {
    private var application: Application
    private var options: CompositionCompassOptions
    private var dl: YoutubeDL
    private var ffmpeg: FFmpeg

    constructor(options: CompositionCompassOptions, application: Application) {
        this.options = options
        this.application = application

        dl = YoutubeDL.getInstance()
        dl.init(application)

        ffmpeg = FFmpeg.getInstance()
        ffmpeg.init(application)
    }

    suspend fun download(tracks: List<String>, onUpdate: (DownloadStatus) -> Unit, onFailure: (String, Exception) -> Unit) {

        val status = DownloadStatus()
        val listGrouped: MutableList<MutableList<String>> = mutableListOf()
        var j = -1

        tracks.forEachIndexed() { index, track ->
            if (index % options.maxParallelDownloads == 0) {
                listGrouped.add(mutableListOf())
                j++
            }

            status.updateJob(track, 0.0F)
            listGrouped[j].add(track)
        }

        listGrouped.forEach {
            val jobs =
                it.map { track ->
                    GlobalScope.launch(newSingleThreadContext("youtubedl-download")) {
                        startDownload(
                            track,
                            onUpdate = { track, progress ->
                                status.updateJob(track, progress)
                                onUpdate(status)
                            },
                            onFailure = { track, exception ->
                                onFailure(track, exception)
                            })
                    }
                }

            jobs.joinAll()
        }
    }
    
    suspend fun update() {
        dl.updateYoutubeDL(application);
    }

    private fun startDownload(track: String, onUpdate: (String, Float) -> Unit, onFailure: (String, Exception) -> Unit) {
        try {
            val request = YoutubeDLRequest(track)

            val downloadDir = File(options.downloadDirectory)
            val archiveDir = File(options.archiveDirectory)

            downloadDir.mkdirs()
            archiveDir.mkdirs()

            request.addOption("--extract-audio")
            request.addOption("--ignore-errors")

            request.addOption("--default-search", "ytsearch")
            request.addOption("--output", downloadDir.absolutePath + "/%(title)s.%(ext)s")
            request.addOption("--download-archive", archiveDir.absolutePath + "/downloaded.txt")
            request.addOption("--match-filter", "duration < 600")
            request.addOption("--match-title", "^((?!(${options.exceptions})).)*$")
            //for meta-data keep in mind that adding it might break the CarPlayer in Tasker!

            dl.execute(request) { progress, etaInSeconds -> onUpdate(track, progress) }
        } catch (e: Exception) {
            onFailure(track, e)
        }
    }
}