package com.gits.compositioncompass.Models

class TrackItem(
    val id: String,
    val name: String,
    val artists: List<ArtistItem>,
    val popularity: Int = 0,
    val genres: List<String> = listOf(),
    val album: AlbumItem? = null
)

class ArtistItem(
    val id: String,
    val name: String,
    val topTracks: List<TrackItem> = listOf(), //most popular tracks
    val popularity: Int = 0,
    val genres: List<String> = listOf(),
    val biography: String = ""
) {
    //Only supposed to be used for similar tracks.
    //Since most of the time you are already in your car (and perhaps even driving... o.O)
    //you need to be able to quickly read the station name.
    val initials: String
        get() = name.split(' ').map { it.first().uppercaseChar() }.joinToString("")
}

class AlbumItem(
    val id: String,
    val name: String,
    val tracks: List<TrackItem>,
    val artists: List<ArtistItem>,
    val popularity: Int = 0
)
//
//fun getArtists(artists: List<SimpleArtist>?) =
//    if (artists == null)
//        listOf()
//    else
//        runBlocking { artists.map { async { Artist(it.toFullArtist()!!) } }.awaitAll() }
//
//fun getTracks(tracks: PagingObject<SimpleTrack>?) =
//    if (tracks == null)
//        listOf()
//    else
//        runBlocking { tracks.map { async { Track(it!!.toFullTrack()!!) } }.awaitAll() }
