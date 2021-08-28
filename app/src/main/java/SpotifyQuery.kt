import com.adamratzman.spotify.SpotifyAppApi
import com.adamratzman.spotify.models.*
import com.adamratzman.spotify.spotifyAppApi
import com.adamratzman.spotify.utils.Market

class SpotifyQuery: IStreamingServiceQuery {

    private var options: CompositionCompassOptions
    private var addedArtists: MutableList<Artist>
    private var addedTracks: MutableList<Track>
    private var addedGenres: MutableList<String>
    private var addedAlbums: MutableList<Album>
    private lateinit var api: SpotifyAppApi

    private var mode: QueryMode

    override val requiredFields: List<List<Fields>> get() = when (mode) {
        QueryMode.Specified -> listOf(listOf(Fields.Artist), listOf(Fields.Genre), listOf(Fields.Track), listOf(Fields.Album))
        else                -> listOf(listOf(Fields.Genre, Fields.Track, Fields.Artist))
    }

    override val supportedFields: List<Fields> get() =
        listOf(Fields.Genre, Fields.Track, Fields.Artist, Fields.Album)

    constructor(options: CompositionCompassOptions) {
        this.options = options

        this.addedArtists = mutableListOf()
        this.addedTracks = mutableListOf()
        this.addedGenres = mutableListOf()
        this.addedAlbums = mutableListOf()

        mode = QueryMode.SimilarTracks
    }

    override fun changeMode(mode: QueryMode) {
        this.mode = mode
    }

    //needs to be called before any other functions!
    override suspend fun prepare() {
        api = spotifyAppApi(options.spotifyClientId, options.spotifyClientSecret).build()
    }

    override suspend fun addArtist(name: String) {
        val artists = api.search.searchArtist(name)

        if (artists.count() > 0)
            addedArtists.add(artists.get(0))
    }


    override suspend fun addTrack(name: String, artist: String) {
        val results = api.search.searchAllTypes("$name $artist", market = Market.DE)
        val tracksCount = results.tracks?.count() ?: 0

        var tracksMatching = listOf<Track?>()

        if (tracksCount > 0) {
            val tracks: PagingObject<Track> = results.tracks!!

            tracksMatching = tracks.filter {
                it!!.name.contains(name, true) &&
                it.artists.filter { it.name.contains(artist, true) }.count() > 0
            }

            val trackMatching = tracksMatching.first()

            if (trackMatching != null) {
                addedTracks.add(trackMatching)
                //return true
            }

            //else
                //return false
        }

        //else
            //return false
    }


    override suspend fun addAlbum(name: String, artist: String) {
        //TODO
    }


    override suspend fun addGenre(name: String) {
        addedGenres.add(name)
    }

    override suspend fun getSimilarTracks(): List<TargetDirectory> {
        val genreSeeds = addedGenres
        val artistSeeds = addedArtists.map { it.id }
        val trackSeeds = addedTracks.map { it.id }

        val recommendations = api.browse.getRecommendations(artistSeeds, genreSeeds, trackSeeds)

        return listOf(TargetDirectory(options.rootDirectory + "/" + DownloadFolder.Downloads.folderName, recommendations.tracks))
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

    override fun clear() {
        addedGenres.clear()
        addedArtists.clear()
        addedTracks.clear()
    }
}