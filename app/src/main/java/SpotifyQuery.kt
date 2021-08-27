import com.adamratzman.spotify.SpotifyAppApi
import com.adamratzman.spotify.models.Artist
import com.adamratzman.spotify.models.PagingObject
import com.adamratzman.spotify.models.RecommendationResponse
import com.adamratzman.spotify.models.Track
import com.adamratzman.spotify.spotifyAppApi
import com.adamratzman.spotify.utils.Market
import java.lang.Exception

class SpotifyQuery: IStreamingServiceQuery {

    private var options: CompositionCompassOptions
    private var addedArtists: MutableList<Artist>
    private var addedTracks: MutableList<Track>
    private var addedGenres: MutableList<String>
    private lateinit var api: SpotifyAppApi

    override val requiredFields: List<Fields> get() = listOf(Fields.Genre, Fields.Track, Fields.Artist)
    override val exclusiveFields: List<Fields> get() = listOf()
    override val supportedFields: List<Fields> get() = listOf(Fields.Genre, Fields.Track, Fields.Artist)

    constructor(options: CompositionCompassOptions) {
        this.options = options

        this.addedArtists = mutableListOf()
        this.addedTracks = mutableListOf()
        this.addedGenres = mutableListOf()
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


    override suspend fun addGenre(name: String) {
        addedGenres.add(name)
    }

    override suspend fun getSimilarTracks(): List<Track> {
        val genreSeeds = addedGenres
        val artistSeeds = addedArtists.map { it.id }
        val trackSeeds = addedTracks.map { it.id }

        val recommendations = api.browse.getRecommendations(artistSeeds, genreSeeds, trackSeeds)

        return recommendations.tracks
    }

    override suspend fun getSimilarAlbums(): List<Track> {
        TODO("Not yet implemented")
    }

    override suspend fun getSimilarArtists(): List<Track> {
        TODO("Not yet implemented")
    }

    override suspend fun getSpecificTracks(): List<Track> {
        TODO("Not yet implemented")
    }

    override suspend fun getSpecificAlbums(): List<Track> {
        TODO("Not yet implemented")
    }

    override suspend fun getSpecificArtists(): List<Track> {
        TODO("Not yet implemented")
    }

    override fun clear() {
        addedGenres.clear()
        addedArtists.clear()
        addedTracks.clear()
    }
}