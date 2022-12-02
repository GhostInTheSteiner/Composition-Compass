package com.gits.compositioncompass.Queries

import DownloadFolder
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import com.gits.compositioncompass.Models.*
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.ItemPicker
import java.io.File

abstract class Query(
    protected var options: CompositionCompassOptions,
    protected var picker: ItemPicker,
) {

    protected var addedArtists: MutableList<ArtistItem> = mutableListOf()
    protected var addedTracks: MutableList<TrackItem> = mutableListOf()
    protected var addedGenres: MutableList<String> = mutableListOf()
    protected var addedAlbums: MutableList<AlbumItem> = mutableListOf()

    protected val resultsSimilarArtists_Tracks: Int = 10 //spotify doesn't allow more than 10

    protected fun getPath(folder: DownloadFolder, subFolderName: String): String {
        return options.rootDirectoryPath + "/" + folder.folderName + "/" + subFolderName
    }

    suspend fun getSpecified(): List<TargetDirectory> {
        val artistsDefined = addedArtists.count() > 0
        val tracksDefined = addedTracks.count() > 0
        val albumsDefined = addedAlbums.count() > 0

        var targetDirectories = listOf<TargetDirectory>()

        if (artistsDefined && tracksDefined) { //fetch specified tracks (single tracks)
            targetDirectories =
                addedArtists.mapIndexed { i, artist ->
                    TargetDirectory(
                        getPath(
                            DownloadFolder.Artists,
                            artist.name),
                        listOf(
                            SearchQuery(
                                addedTracks[i].name,
                                listOf(artist.name))))}}

        else if (artistsDefined && albumsDefined) { //fetch specified albums (whole albums)
            targetDirectories =
                addedAlbums.map { album ->
                    TargetDirectory(
                        getPath(
                            DownloadFolder.Albums,
                            album.name),
                        album.tracks.map { track ->
                            SearchQuery(
                                track!!.name,
                                track.artists.map { it.name })})}}

        else if (artistsDefined) { //fetch specified artists
            targetDirectories =
                addedArtists.map { artist ->
                    TargetDirectory(
                        getPath(
                            DownloadFolder.Artists,
                            artist.name),
                        artist.topTracks.map { track ->
                            SearchQuery(
                                track.name,
                                track.artists.map { it.name })})}}

        else
            throw Exception("Required field 'artist' not found!")

        return targetDirectories
    }

    abstract suspend fun addArtist(name: String) : Boolean

    //downloads the top tracks to each artist found inside the 'More Interesting' folder
    suspend fun getSpecifiedMoreInteresting(): List<TargetDirectory> {

        val moreInteresting = File(options.moreInterestingDirectoryPath).listFiles()
        val moreInterestingArtists = moreInteresting
            .map { it.nameWithoutExtension.split(" - ").first() }
            .toSet().toList() //remove duplicates

        moreInterestingArtists.forEach { addArtist(it) }

        val specifiedDirectories = getSpecified()
        val searchQueries = specifiedDirectories.flatMapIndexed { currentArtist, it ->
            it.searchQueries.take(options.resultsLikedArtists).map {
                //ensure source artist is used, and not one of the "featured" artists
                SearchQuery(it.track, listOf(moreInterestingArtists[currentArtist]), it.album, it.genre) } }

        val targetDirectoriesNames = "!Artists (${moreInterestingArtists.take(3).joinToString(", ")})"
        val targetDirectories = listOf(TargetDirectory(
            getPath(DownloadFolder.Stations, targetDirectoriesNames),
            searchQueries
        ))



        return targetDirectories
    }

    protected fun getSubFolder_Similar() =
        "!Similar (" +
                listOf(
                    addedArtists.map { "'" + it.name + "'" }.joinToString(" & "),
                    addedAlbums.map { "'" + it.name + "'" }.joinToString(" & "),
                    addedTracks.map { "'" + it.name + "'" }.joinToString(" & "),
                    addedGenres.map { "'" + it + "'" }.joinToString(" & ")
                )
                    .filter { it.length > 0 }
                    .joinToString("; ") +
                ")"

    protected fun getSubFolder_Station(): String {
        val genreNames = addedGenres.joinToString("; ")
        val artistNames = addedArtists.map { it.initials }.joinToString("; ")
        val trackNames = addedTracks.map { it.name }.joinToString("; ")

        val subFolderName =
            artistNames + " (" + listOf(trackNames, genreNames).joinToString("; ").trim()
                .trim(';') + ")"
        return subFolderName
    }

    protected open fun filterExceptions(tracks: List<TrackItem>): List<TrackItem> =
        tracks.filter { !(Regex(options.exceptions, RegexOption.IGNORE_CASE).containsMatchIn(it.name + " " + it.artists.joinToString(" "))) }

    //AND connection
    protected open fun filterGenres(tracks: List<TrackItem>): List<TrackItem> =
        if (addedGenres.count() > 0)
            tracks.filter { track ->
                addedGenres.all { addedTag ->
                    track.genres.any { trackTag ->
                        trackTag.contains(addedTag, true)}}}
        else
            tracks
}
