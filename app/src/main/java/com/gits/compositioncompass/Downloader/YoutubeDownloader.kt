package com.gits.compositioncompass.Downloader

import DownloadFolder
import android.app.Activity
import com.gits.compositioncompass.Models.SearchQuery
import com.gits.compositioncompass.Models.TargetDirectory
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.io.File

class YoutubeDownloader {
    private var activity: Activity
    private var options: CompositionCompassOptions
    private var dl: YoutubeDL
    private var ffmpeg: FFmpeg
    private var jobs: MutableList<Job>
    private var isArtists: Boolean = false

    constructor(options: CompositionCompassOptions, activity: Activity) {
        dl = YoutubeDL.getInstance()
        dl.init(activity)

        ffmpeg = FFmpeg.getInstance()
        ffmpeg.init(activity)

        jobs = mutableListOf()

        this.options = options
        this.activity = activity
    }

    //needs to be blocking for as long as download runs!
    suspend fun start(targetDirectories: List<TargetDirectory>, onUpdate: (DownloadStatus) -> Unit, onFailure: (String, Exception) -> Unit) {

        isArtists = false

        val status = DownloadStatus()

        targetDirectories.forEach { directory -> directory.searchQueries
            .map { search ->

                //use a sufficiently unique key: download path + search
                var trackPair = Pair(directory.targetPath, search)

                status.updateJob(trackPair, 0.0F)

                trackPair
            }
            .forEach { trackPair ->
                while (true) {
                    val targetPath = trackPair.first
                    val search = trackPair.second

                    val removed = jobs.removeAll { it.isCompleted }

                    if (jobs.count() < options.maxParallelDownloads) {

                        //once returned job completes the download is finished
                        val job = GlobalScope.launch(newSingleThreadContext("youtubedl-download")) {
                            runYoutubeDL(
                                search,
                                targetPath,
                                onUpdate = { progress ->
                                    status.updateJob(trackPair, progress)
                                    onUpdate(status)
                                },
                                onFailure = { exception ->
                                    status.updateJob(trackPair, 100.0F)
                                    onFailure(search.toString(), exception)
                                })
                        }

                        jobs.add(job)
                        break
                    }
                    else
                        delay(100)
                }

                val x = ""
            }
        }

        jobs.joinAll() //after this line all downloads are completed

        //move tracks whose artists have already been 'explored' to another directory, to keep the 'More Interesting' folder clean
        if (isArtists) {
            File(options.moreInterestingDirectoryPath).listFiles().forEach { source ->
                val target = File("${targetDirectories.first().targetPath}/${source.name}")
                val targetRenamed = File("${targetDirectories.first().targetPath}/!${source.name}")

                source.copyTo(target, true)
                source.delete()

                target.renameTo(targetRenamed)
            }
        }

    }
    
    fun update() {
        dl.updateYoutubeDL(activity);
    }

    private fun runYoutubeDL(searchQuery: SearchQuery, directory: String, onUpdate: (Float) -> Unit, onFailure: (Exception) -> Unit) {
        try {
            val request = YoutubeDLRequest(searchQuery.toString())
            val formatTitle = "%(title)s"
            val downloadDir = File(directory)
            val downloadArchiv = File(options.rootDirectoryPath + "/downloaded.txt")

            if (!downloadArchiv.exists())
                downloadArchiv.createNewFile()

            var searchQueryArtist = searchQuery.artists.firstOrNull() ?: ""
            var searchQueryTrack = searchQuery.track

            if (searchQuery.artists.count() > 1)
                searchQueryTrack += " (feat. ${searchQuery.artists.drop(1).joinToString(", ")})"

            var searchQueryText = ""

            if(searchQueryArtist.length > 0 && searchQueryTrack.length > 0)
                searchQueryText = "$searchQueryArtist - $searchQueryTrack"

            else
                searchQueryText = formatTitle

            downloadDir.mkdirs()

            request.addOption("--extract-audio")
            request.addOption("--ignore-errors")
            request.addOption("--match-filter", "duration < 600")

            //TODO: perhaps add meta-data now, that tasker / vlc is no longer required?
            //previously meta-data broke the tasker implementation

            val directoryParts = directory.split("/").reversed().take(2)
            val subFolder = directoryParts.first()

            val isURL = subFolder.startsWith("!Singles") || subFolder.startsWith("!Playlist")
            val isSearch = subFolder.startsWith("!Search")
            val isFile = subFolder.startsWith("!File")
            val isArtists = subFolder.startsWith("!Artists")
            val isSpecified = directoryParts.any { it in listOf(DownloadFolder.Artists.folderName, DownloadFolder.Albums.folderName)}

            /*
            Single Video:   /Stations/!Singles/Avicii - Levels HQ Upload.opus
            PLaylist:       /Stations/!Playlist (<UUID>)/<list_of_tracks>
            Search Query:   /Stations/!Search (best techno songs 2021)/<list_of_tracks>
            */

            if (isURL) {
                //Single Video or Playlist; search with URL; get title from YouTube
                request.addOption("--output", downloadDir.absolutePath + "/$formatTitle.%(ext)s")
            }

            else if (isSearch) {
                //Search query; search with unstructured text; download first 50 results; get title from YouTube
                request.addOption("--default-search", "ytsearch50")
                request.addOption("--output", downloadDir.absolutePath + "/$formatTitle.%(ext)s")
            }

            else if (isFile) {
                //Search query; search with unstructured text; download single track only; get title from YouTube
                request.addOption("--default-search", "ytsearch")
                request.addOption("--output", downloadDir.absolutePath + "/$formatTitle.%(ext)s")
            }

            else {
                //Other download mode; search with structured text; download single track only; get title from SearchQuery
                request.addOption("--default-search", "ytsearch")
                request.addOption("--output", downloadDir.absolutePath + "/$searchQueryText.%(ext)s")
            }

            if (isSpecified || isURL|| isSearch || isFile || isArtists)
                //pass => redownloads allowed

            else if (downloadArchiv.readLines().contains(searchQuery.toString())) {
                onFailure(Exception("Ignoring item, as it has already been downloaded. Delete record in downloaded.txt to allow redownloads."))
                return
            }

            else {
                downloadArchiv.appendText(searchQuery.toString()+ "\n")
                request.addOption("--match-title", "^((?!(${options.exceptions})).)*$")
            }

            if (isArtists)
                this.isArtists = true

            dl.execute(request) { progress, etaInSeconds, _ -> onUpdate(progress) }

        } catch (e: Exception) {
            onFailure(e)
        }
    }

    suspend fun cancel() {
        jobs.forEach { it.cancel() }
    }
}