import com.adamratzman.spotify.SpotifyAppApi
import com.adamratzman.spotify.SpotifyAppApiBuilder
import com.adamratzman.spotify.models.*
import com.adamratzman.spotify.spotifyAppApi
import com.adamratzman.spotify.utils.Market
import kotlinx.coroutines.*
import java.lang.Exception
import kotlin.math.roundToInt

class SpotifyQuery: IStreamingServiceQuery {

    private var addedArtists: MutableList<Artist>
    private var addedTracks: MutableList<Track>
    private var addedGenres: MutableList<String>
    private var addedAlbums: MutableList<Album>

    private var api: SpotifyAppApi?
    private var apiBuilder: SpotifyAppApiBuilder
    private var options: CompositionCompassOptions
    private var mode: QueryMode

    override val requiredFields: List<List<Fields>> get() = when (mode) {
        QueryMode.Specified -> listOf(listOf(Fields.Artist), listOf(Fields.Artist, Fields.Track), listOf(Fields.Artist, Fields.Album))
        else                -> listOf(listOf(Fields.Genre, Fields.Track, Fields.Artist))
    }

    override val supportedFields: List<Fields> get() = when (mode) {
        QueryMode.Specified -> listOf(Fields.Track, Fields.Artist, Fields.Album)
        else                -> listOf(Fields.Track, Fields.Artist, Fields.Genre)
    }

    constructor(options: CompositionCompassOptions) {
        this.options = options

        this.addedArtists = mutableListOf()
        this.addedTracks = mutableListOf()
        this.addedGenres = mutableListOf()
        this.addedAlbums = mutableListOf()

        mode = QueryMode.SimilarTracks

        api = null
        apiBuilder = spotifyAppApi(options.spotifyClientId, options.spotifyClientSecret)
    }

    override fun changeMode(mode: QueryMode) {
        this.mode = mode
    }

    //needs to be called before any other functions!
    override suspend fun prepare() {
        api = api ?: apiBuilder.build()
    }

    override suspend fun searchArtist(name: String): List<Artist> {
        val list = mutableListOf<Artist>()
        val albums = api!!.search.searchAllTypes(name, 10, market = Market.DE).artists

        if (albums != null)
            albums!!.forEach {
                if (it != null)
                    list += it!!
            }

        return list.toList()
    }

    override suspend fun searchTrack(name: String, artist: String): List<Track> {
        val list = mutableListOf<Track>()
        val tracks = api!!.search.searchAllTypes(name + " " + artist, 10, market = Market.DE).tracks

        if (tracks != null)
            tracks!!.forEach {
                if (it != null)
                    list += it!!
            }

        return list.toList()
    }

    override suspend fun searchAlbum(name: String, artist: String): List<Album> {
        val list = mutableListOf<Album>()
        val albums = api!!.search.searchAllTypes(name + " " + artist, 10, market = Market.DE).albums

        if (albums != null)
            albums!!.forEach {
                if (it != null)
                    list += it.toFullAlbum()!!
            }

        return list.toList()
    }

    override suspend fun searchGenre(name: String): List<String> =
        api!!.browse.getAvailableGenreSeeds().filter { it.contains(name) }

    override suspend fun addArtist(name: String) : Boolean {
        val artists = api!!.search.searchArtist(name)

        if (artists.count() > 0) {
            addedArtists.add(artists.get(0))
            return true
        }

        return false
    }


    override suspend fun addTrack(name: String, artist: String) : Boolean {
        val results = api!!.search.searchAllTypes("$name $artist", market = Market.DE)
        val tracksCount = results.tracks?.count() ?: 0

        if (tracksCount > 0) {
            val tracks: PagingObject<Track> = results.tracks!!

            val tracksMatching = tracks.filter {
                it!!.name.contains(name, true) &&
                it.artists.any { it.name.contains(artist, true) }
            }

            val trackMatching = tracksMatching.first()

            if (trackMatching != null) {
                addedTracks.add(trackMatching)
                return true
            }
        }

        return false
    }


    override suspend fun addAlbum(name: String, artist: String) : Boolean {
        val results = api!!.search.searchAllTypes("$name $artist", market = Market.DE)
        val albumsCount = results.albums?.count() ?: 0

        if (albumsCount > 0) {
            val albums: PagingObject<SimpleAlbum> = results.albums!!

            val albumsMatching = albums.filter {
                it!!.name.contains(name, true) &&
                it.artists.any { it.name.contains(artist, true) }
            }

            val albumMatching = albumsMatching.first()

            if (albumMatching != null) {
                addedAlbums.add(albumMatching.toFullAlbum()!!)
                return true
            }
        }

        return false
    }


    override suspend fun addGenre(name: String) : Boolean {
        addedGenres.add(name)
        return true
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

        val recommendations = api!!.browse.getRecommendations(artistSeeds, genreSeeds, trackSeeds)
        val queries = recommendations.tracks.map { SearchQuery(it.name, it.artists.map { it.name } ) }

        return listOf(TargetDirectory(path, queries))
    }

    private fun getPath(folder: DownloadFolder, subFolderName: String): String {
        return options.rootDirectory + "/" + folder.folderName + "/" + subFolderName
    }

    override suspend fun getSimilarAlbums(): List<TargetDirectory> {
        //get recommendations
        var recommendations = getRecommendations(options.samplesSimilarAlbums)

        //get occurences
        val albumOccurrences = mutableMapOf<String, Int>()

        recommendations.forEach { it.tracks.forEach {
            var currentOccurences = albumOccurrences.get(it.album.id) ?: 0
            albumOccurrences[it.album.id] = ++currentOccurences
        }}

        //get top album tracks
        val albumOccurrencesSorted = albumOccurrences.toList().sortedByDescending { (id, occurences) -> occurences }
        val topAlbumIds = albumOccurrencesSorted.take(5).map { it.first }

        var jobs = listOf<Deferred<Pair<String, PagingObject<SimpleTrack>>>>()

        runBlocking {
            jobs = topAlbumIds.map { async {
                val album = api!!.albums.getAlbum(it)!!
                Pair(album.name, album.tracks)
            }
        }}

        val topAlbumFolders = jobs.map { it.await() }
        val targetDirectories = mutableListOf<TargetDirectory>()

        //set download paths (one folder for each album)
        topAlbumFolders.forEach { (albumFolder, tracks) ->
            val path = getPath(DownloadFolder.Albums, albumFolder)
            val searchQueries = tracks.map { SearchQuery(it!!.name, it.artists.map { it.name }) }

            targetDirectories += TargetDirectory(path, searchQueries)
        }

        return targetDirectories
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

        var jobs = listOf<Deferred<Pair<String, List<Track>>>>()

        runBlocking {
            jobs = topArtistIds.map { async { Pair(api!!.artists.getArtist(it)!!.name, api!!.artists.getArtistTopTracks(it)) } }
        }

        val topArtistFolders = jobs.map { it.await() }
        val targetDirectories = mutableListOf<TargetDirectory>()

        //set download paths (one folder for each artist)
        topArtistFolders.forEach { (artistFolder, tracks) ->
            val path = getPath(DownloadFolder.Artists, artistFolder)
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
                jobs += async { api!!.browse.getRecommendations(artistSeeds, genreSeeds, trackSeeds) }
        }

        recommendations = jobs.map { it.await() }

        return recommendations
    }

    override suspend fun getSpecified(): List<TargetDirectory> {
        val artistsDefined = addedArtists.count() > 0
        val tracksDefined = addedTracks.count() > 0
        val albumsDefined = addedAlbums.count() > 0

        var targetDirectories = listOf<TargetDirectory>()

        if (artistsDefined && albumsDefined) { //fetch specified albums (whole albums)
            targetDirectories =
                addedAlbums.map { album ->
                    TargetDirectory(
                        getPath(
                            DownloadFolder.Albums,
                            album.name),
                        album.tracks.map { track ->
                            SearchQuery(
                                track!!.name,
                                track.artists.map { it.name })})}

        }
        else if (artistsDefined && tracksDefined) { //fetch specified tracks (single tracks)
            targetDirectories =
                addedArtists.mapIndexed { i, artist ->
                    TargetDirectory(
                        getPath(
                            DownloadFolder.Artists,
                            artist.name),
                        listOf(
                            SearchQuery(
                                addedTracks[i].name,
                                listOf(artist.name))))}

        }
        else if (artistsDefined) { //fetch specified artists (top tracks for each artist)
            //api!!.artists.getArtistTopTracks(addedArtists[0].id)

            targetDirectories =
                addedArtists.map { artist ->
                    TargetDirectory(
                        getPath(
                            DownloadFolder.Artists,
                            artist.name),
                        api!!.artists.getArtistTopTracks(artist.id).map { track ->
                            SearchQuery(
                                track.name,
                                track.artists.map { it.name })})}

        }
        else
            throw Exception("Required field 'artist' not found!")

        return targetDirectories
    }

    override fun clear() {
        addedGenres.clear()
        addedArtists.clear()
        addedTracks.clear()
    }
}