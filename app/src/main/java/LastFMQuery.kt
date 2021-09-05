class LastFMQuery : IStreamingServiceQuery {
    override val requiredFields: List<List<Fields>> get() = when (QueryMode.SimilarTracks) {
        QueryMode.Specified -> listOf(listOf(Fields.Artist), listOf(Fields.Genre))
        else                -> listOf(listOf(Fields.Genre, Fields.Track, Fields.Artist))
    }
    override val supportedFields: List<Fields> =
        listOf(Fields.Genre, Fields.Track, Fields.Artist, Fields.Album)

    private var mode: QueryMode

    constructor(options: CompositionCompassOptions) {
        this.mode = QueryMode.SimilarTracks
    }

    override suspend fun searchArtist(name: String): List<ArtistItem> {
        TODO("Not yet implemented")
    }

    override suspend fun searchTrack(name: String, artist: String, album: String): List<TrackItem> {
        TODO("Not yet implemented")
    }

    override suspend fun searchAlbum(name: String, artist: String): List<AlbumItem> {
        TODO("Not yet implemented")
    }

    override suspend fun searchGenre(name: String): List<String> {
        TODO("Not yet implemented")
    }

    override suspend fun addArtist(name: String) : Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun addTrack(name: String, artist: String) : Boolean  {
        TODO("Not yet implemented")
    }

    override suspend fun addAlbum(name: String, artist: String) : Boolean  {
        TODO("Not yet implemented")
    }

    override suspend fun addGenre(name: String) : Boolean  {
        TODO("Not yet implemented")
    }

    override suspend fun getSimilarTracks(): List<TargetDirectory> {
        TODO("Not yet implemented")
    }

    override suspend fun getSimilarAlbums(): List<TargetDirectory> {
        TODO("Not yet implemented")
    }

    override suspend fun getSimilarArtists(): List<TargetDirectory> {
        TODO("Not yet implemented")
    }

    override suspend fun getSpecified(): List<TargetDirectory> {
        TODO("Not yet implemented")
    }

    override fun changeMode(mode: QueryMode) {
        this.mode = mode
    }

    override fun clear() {
        TODO("Not yet implemented")
    }

    override suspend fun prepare() {
        TODO("Not yet implemented")
    }
}
