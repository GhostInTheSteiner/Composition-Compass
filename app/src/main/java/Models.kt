class TrackItem(
    val id: String,
    val name: String,
    val popularity: Int = 0,
    val artists: List<ArtistItem>
)

class ArtistItem(
    val id: String,
    val name: String,
    val popularity: Int = 0
)

class AlbumItem(
    val id: String,
    val name: String,
    val popularity: Int = 0,
    val tracks: List<TrackItem>
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
