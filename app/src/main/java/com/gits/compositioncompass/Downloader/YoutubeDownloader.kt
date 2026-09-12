package com.gits.compositioncompass.Downloader

import DownloadFolder
import android.app.Activity
import com.gits.compositioncompass.Models.SearchQuery
import com.gits.compositioncompass.Models.TargetDirectory
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.SafStorage
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class YoutubeDownloader {
    private var activity: Activity
    private var options: CompositionCompassOptions
    private var storage: SafStorage
    private var dl: YoutubeDL
    private var ffmpeg: FFmpeg
    private var jobs: MutableList<Job>
    private var isArtists: Boolean = false

    //yt-dlp is a native process and can only write to a real filesystem path - it has no
    //notion of SAF/content Uris. Each search query downloads into this private, always-
    //accessible scratch directory (mirroring the same relative folder structure as the
    //final SAF destination), and flushStagingToTree() copies the finished file(s) into
    //the user's picked folder right after, deleting the staged copy. Guarded by a Mutex
    //since multiple downloads can run in parallel (options.maxParallelDownloads) and may
    //share the same staging directory.
    private val stagingRoot: File
    private val copyMutex = Mutex()

    constructor(options: CompositionCompassOptions, activity: Activity, storage: SafStorage) {
        dl = YoutubeDL.getInstance()
        dl.init(activity)

        ffmpeg = FFmpeg.getInstance()
        ffmpeg.init(activity)

        jobs = mutableListOf()

        this.options = options
        this.activity = activity
        this.storage = storage
        this.stagingRoot = File(activity.cacheDir, "download-staging")
    }

    //needs to be blocking for as long as download runs!
    suspend fun start(targetDirectories: List<TargetDirectory>, onUpdate: (DownloadStatus) -> Unit, onFailure: (String, Exception) -> Unit) {
    //targetDirectories only contains one element (LP-path)
        isArtists = false

        val status = DownloadStatus()

        targetDirectories.forEach { directory -> directory.searchQueries
            .map { search ->

                //use a sufficiently unique key: download path + search
                var trackPair = Pair(directory.targetPath, search)

                status.updateJob(trackPair, 0.0F)

                trackPair
            }

            // TODO: No compound artist / album names are created (no CL;LP if Clara Luzia, Linkin Park was passed in the artists field),
            // likely due to trackPairs getting processed individually?
            //(/Stations/LP (Papercut), Blue on Black, Five Finger Death Punch)
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
            val targetRelative = targetDirectories.first().targetPath

            storage.listFileNames(options.moreInterestingDirectoryPath).forEach { name ->
                storage.moveFile(options.moreInterestingDirectoryPath, name, targetRelative, "!$name")
            }
        }

    }
    
    fun update() {
        dl.updateYoutubeDL(activity);
    }

    private suspend fun runYoutubeDL(searchQuery: SearchQuery, directory: String, onUpdate: (Float) -> Unit, onFailure: (Exception) -> Unit) {
        try {
            val request = YoutubeDLRequest(searchQuery.toString())
            val formatTitle = "%(title)s"

            //directory is a SAF-tree-relative path (may have a leading "/" - harmless,
            //File(parent, child) resolves a leading separator in child as relative to
            //parent regardless). This is where yt-dlp actually writes; the finished
            //file(s) get copied into the real SAF destination in flushStagingToTree().
            val downloadDir = File(stagingRoot, directory)

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

            else if (storage.readLines(options.rootDirectoryPath, "downloaded.txt").contains(searchQuery.toString())) {
                onFailure(Exception("Ignoring item, as it has already been downloaded. Delete record in downloaded.txt to allow redownloads."))
                return
            }

            else {
                storage.appendText(options.rootDirectoryPath, "downloaded.txt", searchQuery.toString() + "\n")
                request.addOption("--match-title", "^((?!(${options.exceptions})).)*$")
            }

            if (isArtists)
                this.isArtists = true

            dl.execute(request) { progress, etaInSeconds, _ -> onUpdate(progress) }

            flushStagingToTree(downloadDir, directory)

        } catch (e: Exception) {
            onFailure(e)
        }
    }

    //copies every file yt-dlp just staged into the real SAF-tree destination, then
    //deletes the staged copy. Mutex-guarded since parallel downloads can share a
    //staging directory (e.g. several tracks going into the same station).
    private suspend fun flushStagingToTree(stagingDir: File, targetRelativePath: String) {
        copyMutex.withLock {
            stagingDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    storage.copyFileInto(file, targetRelativePath, file.name)
                    file.delete()
                }
            }
        }
    }

    suspend fun cancel() {
        jobs.forEach { it.cancel() }
    }
}
