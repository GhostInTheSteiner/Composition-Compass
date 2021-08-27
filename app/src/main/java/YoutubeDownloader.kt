import android.app.Application
import android.os.Environment
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
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

    suspend fun download(tracks: List<String>, onUpdate: (DownloadStatus) -> Unit, onFailure: (Exception) -> Unit) {

        val status = DownloadStatus()
        val listGrouped: MutableList<MutableList<String>> = mutableListOf()
        var j = -1

        tracks.forEachIndexed() { index, track ->
            if (index % 10 == 0) {
                listGrouped.add(mutableListOf())
                j++
            }

            listGrouped[j].add(track)
        }

        listGrouped.forEach {
            val jobs = it.map { track ->

                GlobalScope.launch(newSingleThreadContext("youtubedl-download")) {
                    startDownload(
                        track,
                        onUpdate = { track, progress ->
                            status.addJob(track, progress)
                            onUpdate(status)
                        },
                        onFailure = { exception ->
                            onFailure(exception)
                    })
                }

            }

            jobs.joinAll()
        }
    }
    
    suspend fun update() {
        dl.updateYoutubeDL(application);
    }

    private fun startDownload(track: String, onUpdate: (String, Float) -> Unit, onFailure: (Exception) -> Unit) {
        try {
            val request = YoutubeDLRequest("Photomaton jabberwocky")

            val youtubeDLDir =
                File(Environment.getExternalStorageDirectory().absolutePath + "/youtubedl-download")

            youtubeDLDir.mkdirs()

            request.addOption("--default-search", "ytsearch")
            request.addOption("-o", youtubeDLDir.absolutePath.toString() + "/%(title)s.%(ext)s")
            request.addOption("--extract-audio")

            dl.execute(request) { progress, etaInSeconds -> onUpdate(track, progress) }
        } catch (e: Exception) {
            println(e.message)
        }
    }
}