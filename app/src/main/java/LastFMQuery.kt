import com.adamratzman.spotify.models.Track

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

    override suspend fun addArtist(name: String) {
        TODO("Not yet implemented")
    }

    override suspend fun addTrack(name: String, artist: String) {
        TODO("Not yet implemented")
    }

    override suspend fun addAlbum(name: String, artist: String) {
        TODO("Not yet implemented")
    }

    override suspend fun addGenre(name: String) {
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
