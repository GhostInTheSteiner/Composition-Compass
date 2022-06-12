package com.gits.compositioncompass.Queries

import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import DownloadFolder
import Fields
import QueryMode
import com.gits.compositioncompass.Models.*
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.ItemPicker
import java.lang.Exception

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

//Spotify / LastFM
interface IStreamingServiceQuery: IQuery {
    suspend fun searchArtist(name: String, completeData: Boolean = false) : List<ArtistItem>
    suspend fun searchTrack(name: String, artist: String, album: String) : List<TrackItem>
    suspend fun searchAlbum(name: String, artist: String) : List<AlbumItem>
    suspend fun searchGenre(name: String, artist: String = "") : List<String>

    suspend fun addArtist(name: String) : Boolean
    suspend fun addTrack(name: String, artist: String) : Boolean
    suspend fun addAlbum(name: String, artist: String) : Boolean
    suspend fun addGenre(name: String) : Boolean //might not work for LastFM!

    //Similar results to the comma-separated keywords
    suspend fun getSimilarTracks(): List<TargetDirectory> //contains only one directory
    suspend fun getSimilarAlbums(): List<TargetDirectory>
    suspend fun getSimilarArtists(): List<TargetDirectory>

    //Only for the comma-separated keywords
    suspend fun getSpecified(): List<TargetDirectory> //contains only one directory
    suspend fun getSpecifiedMoreInteresting(): List<TargetDirectory> //contains only one directory
    //suspend fun getSpecifiedLessInteresting(): List<TargetDirectory> //contains only one directory
    //suspend fun getSpecifiedFavorites(): List<TargetDirectory> //contains only one directory
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