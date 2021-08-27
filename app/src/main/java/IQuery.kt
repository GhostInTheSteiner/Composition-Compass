import com.adamratzman.spotify.models.Track

interface IQuery {
    val requiredFields: List<Fields>
    val exclusiveFields: List<Fields>
    val supportedFields: List<Fields>

    //clears all query contents
    fun clear()

    //connects to the service
    suspend fun prepare()
}

//Spotify / LastFM
interface IStreamingServiceQuery: IQuery {
    suspend fun addArtist(name: String)
    suspend fun addTrack(name: String, artist: String)
    suspend fun addGenre(name: String) //might not work for LastFM!

    //Similar results to the comma-separated keywords
    suspend fun getSimilarTracks(): List<Track>
    suspend fun getSimilarAlbums(): List<Track>
    suspend fun getSimilarArtists(): List<Track>

    //Only for the comma-separated keywords
    suspend fun getSpecificTracks(): List<Track>
    suspend fun getSpecificAlbums(): List<Track>
    suspend fun getSpecificArtists(): List<Track>
}

interface IYoutubeQuery: IQuery {
    suspend fun addSearchQuery()

    //Results for the search query specified
    suspend fun getSearchQueryResults()
}

interface IFileQuery: IQuery {
    //Results for the tracks specified in the file
    suspend fun getSpecifiedTracks()
}