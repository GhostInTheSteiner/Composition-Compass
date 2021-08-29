import com.adamratzman.spotify.SpotifyAppApi
import com.adamratzman.spotify.models.*
import com.adamratzman.spotify.spotifyAppApi
import com.adamratzman.spotify.utils.Market
import kotlinx.coroutines.*
import kotlin.math.roundToInt

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

        val genreNames = addedGenres.joinToString("; ")
        val artistNames = addedArtists.map { it.name }.joinToString("; ")
        val trackNames = addedTracks.map { it.name }.joinToString("; ")

        val subFolderName = artistNames + " (" + listOf(trackNames, genreNames).joinToString("; ") + ")"
        val path = getPath(DownloadFolder.Stations, subFolderName)

        val recommendations = api.browse.getRecommendations(artistSeeds, genreSeeds, trackSeeds)
        val queries = recommendations.tracks.map { SearchQuery(it.name, it.artists.map { it.name } ) }

        return listOf(TargetDirectory(path, queries))
    }

    private fun getPath(folder: DownloadFolder, subFolderName: String): String {
        return options.rootDirectory + "/" + folder.folderName + "/" + subFolderName
    }

    override suspend fun getSimilarAlbums(): List<TargetDirectory> {
        TODO("Not yet implemented")
    }

    override suspend fun getSimilarArtists(): List<TargetDirectory> {

        //get recommendations
        var recommendations = getRecommendations(options.samplesSimilarArtists)

        //get occurences
        val artistOccurrences = mutableMapOf<String, Int>()

        recommendations.forEach { it.tracks.forEach { it.artists.forEach {
            var currentOccurences = artistOccurrences.get(it.id) ?: 0
            artistOccurrences[it.id] = ++currentOccurences
        }}}

        //get top artist tracks

        val artistOccurrencesSorted = artistOccurrences.toList().sortedByDescending { (id, occurences) -> occurences }

        val topArtistIds = artistOccurrencesSorted.take(5).map { it.first }
        var topArtistFolders = listOf<Pair<String, List<Track>>>() //folders (with artist name) -> tracks

        var jobs = listOf<Deferred<Pair<String, List<Track>>>>()

        runBlocking {
            jobs = topArtistIds.map { async { Pair(api.artists.getArtist(it)!!.name, api.artists.getArtistTopTracks(it)) } }
        }

        topArtistFolders = jobs.map { it.await() }

        val targetDirectories = mutableListOf<TargetDirectory>()

        //set download paths (one folder for each artist)
        topArtistFolders.forEach { (artistFolder, tracks) ->
            val subFolderName = artistFolder //artist-name
            val path = getPath(DownloadFolder.Artists, subFolderName)

            val searchQueries = tracks.map { SearchQuery(it.name, it.artists.map { it.name }) }

            targetDirectories += TargetDirectory(path, searchQueries)
        }

        return targetDirectories
    }

    //amount in steps of 50, 100, 150, 200, ...
    private suspend fun getRecommendations(amount: Int): List<RecommendationResponse> {
        val genreSeeds = addedGenres
        val artistSeeds = addedArtists.map { it.id }
        val trackSeeds = addedTracks.map { it.id }

        var recommendations: List<RecommendationResponse>
        var jobs = mutableListOf<Deferred<RecommendationResponse>>()

        var amount = (amount / 50).toDouble().roundToInt()

        runBlocking {
            for (i in 1..amount)
                jobs += async { api.browse.getRecommendations(artistSeeds, genreSeeds, trackSeeds) }
        }

        recommendations = jobs.map { it.await() }

        return recommendations
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