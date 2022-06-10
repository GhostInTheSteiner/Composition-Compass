package com.gits.compositioncompass.Queries

import DownloadFolder
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import com.gits.compositioncompass.Models.*
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.ItemPicker
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
        return options.rootDirectory + "/" + folder.folderName + "/" + subFolderName
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

    suspend fun getSpecifiedFavorites(): List<TargetDirectory> {
        val folder = picker.folder()

        if (folder == null)
            throw Exception("No folder selected!")
        else if (!folder.absolutePath.startsWith(options.favoritesBase))
            throw Exception("No Favorites folder selected!")
        else
            {
            val favorites = folder.listFiles().filter { it.isFile } //use folder picker here
            val artists = favorites.map { it.nameWithoutExtension.split(" - ").first() }
            artists.forEach { addArtist(it) }

            val specifiedDirectories = getSpecified()
            val searchQueries = specifiedDirectories.flatMap { it.searchQueries }

            val folderName =
                if (folder.name == File(options.favoritesMoreInteresting).name)
                    folder.parentFile.name + " [MI]" //"More Interesting"
                else
                    folder.name

            val targetDirectories = listOf(TargetDirectory(
                getPath(DownloadFolder.Stations, "!${folderName}"),
                searchQueries
            ))

            return targetDirectories
        }
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
