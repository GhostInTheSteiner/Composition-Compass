package com.gits.compositioncompass.Queries

import com.gits.compositioncompass.Models.AlbumItem
import com.gits.compositioncompass.Models.ArtistItem
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import DownloadFolder
import Fields
import QueryMode
import com.gits.compositioncompass.Models.TargetDirectory
import com.gits.compositioncompass.Models.TrackItem

interface IQuery {
    //check if all fields of any group are set
    val requiredFields: List<List<Fields>>
    val supportedFields: List<Fields>

    fun changeMode(mode: QueryMode)

    //clears all query contents
    fun clear()

    //connects to the service
    suspend fun prepare()
}

abstract class Query(protected var options: CompositionCompassOptions) {
    protected fun getPath(folder: DownloadFolder, subFolderName: String): String {
        return options.rootDirectory + "/" + folder.folderName + "/" + subFolderName
    }
}

//Spotify / LastFM
interface IStreamingServiceQuery: IQuery {
    suspend fun searchArtist(name: String) : List<ArtistItem>
    suspend fun searchTrack(name: String, artist: String, album: String) : List<TrackItem>
    suspend fun searchAlbum(name: String, artist: String) : List<AlbumItem>
    suspend fun searchGenre(name: String) : List<String>

    suspend fun addArtist(name: String) : Boolean
    suspend fun addTrack(name: String, artist: String) : Boolean
    suspend fun addAlbum(name: String, artist: String) : Boolean
    suspend fun addGenre(name: String) : Boolean //might not work for LastFM!

    //Similar results to the comma-separated keywords
    suspend fun getSimilarTracks(): List<TargetDirectory> //contains only one group
    suspend fun getSimilarAlbums(): List<TargetDirectory>
    suspend fun getSimilarArtists(): List<TargetDirectory>

    //Only for the comma-separated keywords
    suspend fun getSpecified(): List<TargetDirectory> //contains only one group
}

interface IYoutubeQuery: IQuery {
    fun addSearchQuery(query: String) : Boolean

    //Results for the search query specified
    fun getSearchQueryResults(): List<TargetDirectory>
}

interface IFileQuery: IQuery {
    //Results for the tracks specified in the file
    fun getSpecifiedTracks(): List<TargetDirectory>
}