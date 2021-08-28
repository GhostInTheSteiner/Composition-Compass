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
    suspend fun addArtist(name: String)
    suspend fun addTrack(name: String, artist: String)
    suspend fun addAlbum(name: String, artist: String)
    suspend fun addGenre(name: String) //might not work for LastFM!

    //Similar results to the comma-separated keywords
    suspend fun getSimilarTracks(): List<TargetDirectory> //contains only one group
    suspend fun getSimilarAlbums(): List<TargetDirectory>
    suspend fun getSimilarArtists(): List<TargetDirectory>

    //Only for the comma-separated keywords
    suspend fun getSpecified(): List<TargetDirectory> //contains only one group
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