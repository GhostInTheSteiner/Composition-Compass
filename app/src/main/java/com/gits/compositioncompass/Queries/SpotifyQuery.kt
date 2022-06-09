package com.gits.compositioncompass.Queries

import com.gits.compositioncompass.Models.AlbumItem
import com.gits.compositioncompass.Models.ArtistItem
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import DownloadFolder
import Fields
import QueryMode
import com.gits.compositioncompass.Models.SearchQuery
import com.gits.compositioncompass.Models.TargetDirectory
import com.gits.compositioncompass.Models.TrackItem
import com.adamratzman.spotify.SpotifyAppApi
import com.adamratzman.spotify.SpotifyAppApiBuilder
import com.adamratzman.spotify.models.*
import com.adamratzman.spotify.spotifyAppApi
import com.adamratzman.spotify.utils.Market
import com.gits.compositioncompass.R
import contains
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

class SpotifyQuery: IStreamingServiceQuery, Query {

    private var api: SpotifyAppApi?
    private var apiBuilder: SpotifyAppApiBuilder
    private var mode: QueryMode

    override val requiredFields: List<List<Fields>> get() = when (mode) {
        QueryMode.Specified -> listOf(listOf(Fields.Artist), listOf(Fields.Artist, Fields.Track), listOf(Fields.Artist, Fields.Album))
        else                -> listOf(listOf(Fields.Artist), listOf(Fields.Artist, Fields.Track), listOf(Fields.Artist, Fields.Track, Fields.Genre), listOf(Fields.Artist, Fields.Genre))
    }

    override val supportedFields: List<Fields> get() = when (mode) {
        QueryMode.SpecifiedFavorites    -> listOf(Fields.Favorites)
        QueryMode.Specified             -> listOf(Fields.Track, Fields.Artist, Fields.Album)
        else                            -> listOf(Fields.Track, Fields.Artist, Fields.Genre)
    }

    constructor(options: CompositionCompassOptions): super(options) {
        this.options = options

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

    override suspend fun searchArtist(name: String, completeData: Boolean): List<ArtistItem> {
        val list = mutableListOf<ArtistItem>()
        val artists =
            if (name == "") null
            else api!!.search.searchArtist(name)

        if (artists != null)
            artists!!.forEach {
                if (it != null) {
                    list += ArtistItem(it!!.id, it.name, listOf(), it.popularity)
                }
            }

        return list.toList()
    }

    override suspend fun searchTrack(name: String, artist: String, album: String): List<TrackItem> {
        val list = mutableListOf<TrackItem>()
        val searchString = listOf(name, artist, album).filter { it.length > 0 }.joinToString(" ")
        val tracks = api!!.search.searchAllTypes(searchString, 10, market = Market.DE).tracks

        if (tracks != null)
            tracks!!.forEach {
                if (it != null)
                    list += TrackItem(
                        it.id,
                        it.name,
                        it.artists.map { ArtistItem(it.id, it.name) },
                        it.popularity
                    )
            }

        return list.toList()
    }

    override suspend fun searchAlbum(name: String, artist: String): List<AlbumItem> {
        val list = mutableListOf<AlbumItem>()
        val albums = api!!.search.searchAllTypes(name + " " + artist, 10, market = Market.DE).albums

        if (albums != null)
            albums!!.forEach {
                if (it != null) {
                    val fullAlbum = it.toFullAlbum()
                    val popularity = fullAlbum?.popularity ?: 0
                    val tracks =
                        fullAlbum!!.tracks.map {
                            TrackItem(it!!.id, it.name, it.artists.map {
                                ArtistItem(it.id, it.name)
                            }, it.popularity ?: 0)}

                    list += AlbumItem(it.id, it.name, tracks, listOf(), popularity)
                }
            }

        return list.toList()
    }

    override suspend fun searchGenre(name: String, artist: String): List<String> =
        api!!.browse.getAvailableGenreSeeds().filter { it.contains(name) }

    override suspend fun addArtist(name: String) : Boolean {
        val artists = api!!.search.searchArtist(name)

        if (artists.count() > 0) {
            val artist = artists.get(0)
            addedArtists.add(ArtistItem(
                artist.id,
                artist.name,
                api!!.artists.getArtistTopTracks(artist.id).map { TrackItem(
                    it.id,
                    it.name,
                    it.artists.map { ArtistItem(it.id, it.name)})},
                    artist.popularity))
            return true
        }

        return false
    }


    override suspend fun addTrack(name: String, artist: String) : Boolean {
        val results = api!!.search.searchAllTypes("$name $artist", market = Market.DE)
        val tracksCount = results.tracks?.count() ?: 0

        if (tracksCount > 0) {
            val tracks: List<TrackItem> =
                results.tracks!!.map {
                    TrackItem(it!!.id, it.name, it.artists.map {
                        ArtistItem(it.id, it.name)
                    }, it.popularity)
                }

            var tracksMatching = mutableListOf<TrackItem>()

            tracksMatching.addAll(tracks.filter {
                it!!.name.equals(name, true) &&
                it.artists.any { it.name.contains(artist, true, true) }
            })

            if (tracksMatching.count() == 0) {
                tracksMatching.addAll(tracks.filter {
                    it!!.name.contains(name, true) &&
                    it.artists.any { it.name.contains(artist, true, true) }
                })
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
                it!!.name.contains(name, true, true) &&
                it.artists.any { it.name.contains(artist, true, true) }
            }

            val albumMatching = albumsMatching.first()

            if (albumMatching != null) {
                val fullAlbum = albumMatching.toFullAlbum()!!
                val popularity = fullAlbum.popularity ?: 0
                val tracks =
                    fullAlbum.tracks.map {
                        TrackItem(it!!.id, it.name, it.artists.map {
                            ArtistItem(it.id, it.name)
                        }, popularity)
                    }

                addedAlbums.add(
                    AlbumItem(
                        albumMatching.id,
                        albumMatching.name,
                        tracks,
                        listOf(),
                        popularity
                    )
                )
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

        val subFolderName = getSubFolder_Station()
        val path = getPath(DownloadFolder.Stations, subFolderName)

        val recommendations = api!!.browse.getRecommendations(artistSeeds, genreSeeds, trackSeeds)
        val tracks = removeExceptions_(recommendations.tracks)

        val queries = tracks.map { SearchQuery(it.name, it.artists.map { it.name }) }

        return listOf(TargetDirectory(path, queries))
    }

    override suspend fun getSimilarAlbums(): List<TargetDirectory> {
        //get recommendations
        var recommendations = getRecommendations(options.samplesSimilarAlbums)

        //get occurences
        val albumOccurrences = mutableMapOf<String, Int>()

        recommendations.forEach { removeExceptions_(it.tracks).forEach {
            var currentOccurences = albumOccurrences.get(it.album.id) ?: 0
            albumOccurrences[it.album.id] = ++currentOccurences
        }}

        //get top album tracks
        val albumOccurrencesSorted = albumOccurrences.toList().sortedByDescending { (id, occurences) -> occurences }
        val topAlbumIds = albumOccurrencesSorted.take(options.resultsSimilarAlbums).map { it.first }

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
            val path = getPath(DownloadFolder.Albums, getSubFolder_Similar() + "/" + albumFolder)
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

        recommendations.forEach { removeExceptions_(it.tracks).forEach { it.artists.forEach {
            var currentOccurences = artistOccurrences.get(it.id) ?: 0
            artistOccurrences[it.id] = ++currentOccurences
        }}}

        //get top artist tracks
        val artistOccurrencesSorted = artistOccurrences.toList().sortedByDescending { (id, occurences) -> occurences }
        val topArtistIds = artistOccurrencesSorted.take(options.resultsSimilarArtists).map { it.first }

        var jobs = listOf<Deferred<Pair<String, List<TrackItem>>>>()

        runBlocking {
            jobs =
                topArtistIds.map { async { Pair(
                    api!!.artists.getArtist(it)!!.name,
                    api!!.artists.getArtistTopTracks(it).take(resultsSimilarArtists_Tracks).map {
                        TrackItem(it.id, it.name, it.artists.map {
                            ArtistItem(it.id, it.name)
                        }, it.popularity)
                    })}}
        }

        val topArtistFolders = jobs.map { it.await() }
        val targetDirectories = mutableListOf<TargetDirectory>()

        //set download paths (one folder for each artist)
        topArtistFolders.forEach { (artistFolder, tracks) ->
            val path = getPath(DownloadFolder.Artists, getSubFolder_Similar() + "/" + artistFolder)
            val searchQueries = tracks.map { SearchQuery(it.name, it.artists.map { it.name }) }

            targetDirectories += TargetDirectory(path, searchQueries)
        }

        return targetDirectories
    }

    fun removeExceptions_(tracks: List<Track>): List<Track> =
        tracks.filter { !(Regex(options.exceptions, RegexOption.IGNORE_CASE).containsMatchIn(it.name + " " + it.artists.joinToString(" "))) }

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

    override fun clear() {
        addedGenres.clear()
        addedArtists.clear()
        addedTracks.clear()
    }
}