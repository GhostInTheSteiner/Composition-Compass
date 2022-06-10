package com.gits.compositioncompass.Queries

import com.gits.compositioncompass.Models.AlbumItem
import com.gits.compositioncompass.Models.ArtistItem
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import DownloadFolder
import Fields
import MetaItem
import QueryMode
import com.gits.compositioncompass.Models.SearchQuery
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.WebService
import com.gits.compositioncompass.Models.TargetDirectory
import com.gits.compositioncompass.Models.TrackItem
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.ItemPicker
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.WebRequestErrorException
import kotlinx.coroutines.*
import org.json.JSONObject
import toList
import java.util.*

class LastFMQuery
: IStreamingServiceQuery, Query {

    override val requiredFields: List<List<Fields>> get() = when (mode) {
        QueryMode.Specified         -> listOf(listOf(Fields.Artist), listOf(Fields.Artist, Fields.Track), listOf(Fields.Artist, Fields.Album))
        QueryMode.SimilarTracks     -> listOf(listOf(Fields.Artist, Fields.Track), listOf(Fields.Artist, Fields.Track, Fields.Genre))
        else                        -> listOf(listOf(Fields.Artist), listOf(Fields.Artist, Fields.Track), listOf(Fields.Artist, Fields.Track, Fields.Genre), listOf(Fields.Artist, Fields.Genre))
    }

    override val supportedFields: List<Fields> get() = when (mode) {
        QueryMode.Specified -> listOf(Fields.Track, Fields.Artist, Fields.Album)
        else                -> listOf(Fields.Track, Fields.Artist, Fields.Genre)
    }

    private var mode: QueryMode
    private var service: WebService

    constructor(options: CompositionCompassOptions, picker: ItemPicker): super(options, picker) {
        this.mode = QueryMode.SimilarTracks
        this.options = options
        this.service = WebService("https://ws.audioscrobbler.com").setEndpoint("2.0")

        addedTracks = mutableListOf()
        addedGenres = mutableListOf()
        addedArtists = mutableListOf()
        addedAlbums = mutableListOf()
    }

    override suspend fun searchArtist(name: String, completeData: Boolean): List<ArtistItem> {
        val params = mutableListOf(
            "method", "artist.search",
            "artist", name,
            "limit", "50"
        )

        val json = sendRequest(params)

        var biography = ""
        var genres = listOf<String>()

        if (completeData) {
            val info = getArtistInfo(name)
            val tags = info.getJSONObject("artist").getJSONObject("tags")
            val bio = info.getJSONObject("artist").getJSONObject("bio")
            val content = bio.getString("content")
            genres = tags.getJSONArray("tag").toList<JSONObject>().map { it.getString("name") }
            biography = content.split("<a ").first()
        }

        val artists = json
            .getJSONObject("results")
            .getJSONObject("artistmatches")
            .getJSONArray("artist")
            .toList<JSONObject>()
            .map {
                ArtistItem(getId(), it.getString("name"),
                    popularity = it.getString("listeners").toInt(),
                    genres = genres, biography = biography)}

        return artists
    }

    //TODO: use different approach for album
    override suspend fun searchTrack(name: String, artist: String, album: String): List<TrackItem> {
        //artist is obligatory
        if (artist.isEmpty()) return listOf()

        //album needs different approach
        else if (album.isNotEmpty())
            return getAlbumTracks(album, artist).map { TrackItem(getId(), it.getString("name"), listOf(ArtistItem(getId(), artist))) }

        val params = mutableListOf(
            "method", "track.search",
            "track", name,
            "limit", "50"
        )

        if (artist.isNotEmpty())
            params.addAll(listOf("artist", artist))

        val json = sendRequest(params)

        var tracks = json
            .getJSONObject("results")
            .getJSONObject("trackmatches")
            .getJSONArray("track")
            .toList<JSONObject>()
            .map {
                TrackItem(getId(), it.getString("name"), listOf(
                    ArtistItem(getId(), it.getString("artist"))),
                    it.getString("listeners").toInt())}

        tracks = tracks

        return tracks
    }

    override suspend fun searchAlbum(name: String, artist: String): List<AlbumItem> {
        //artist is obligatory
        if (artist.isEmpty()) return listOf()

        val params = mutableListOf(
            "method", "artist.gettopalbums",
            "artist", artist,
            "limit", "50"
        )

        val json = sendRequest(params)

        var albums = json
            .getJSONObject("topalbums")
            .getJSONArray("album")
            .toList<JSONObject>()
            .map {
                AlbumItem(getId(), it.getString("name"), listOf(), listOf(
                    ArtistItem(getId(), artist)), 0)}

        return albums
    }

    override suspend fun searchGenre(name: String, artist: String): List<String> {
        //artist is obligatory
        if (artist.isEmpty()) return listOf()

        val params = mutableListOf(
            "method", "artist.getTopTags",
            "artist", artist,
            "limit", "50"
        )

        val json = sendRequest(params)

        val tags = json
            .getJSONObject("toptags")
            .getJSONArray("tag")
            .toList<JSONObject>()
            .map { it.getString("name") }

        return tags
    }

    override suspend fun addArtist(name: String) : Boolean {
        val params = mutableListOf(
            "method", "artist.search",
            "artist", name,
            "limit", "50"
        )

        val json = sendRequest(params)

        val artist = json
            .getJSONObject("results")
            .getJSONObject("artistmatches")
            .getJSONArray("artist")
            .toList<JSONObject>()

        val artistItem = runBlocking { artist
            .map { artist -> async {
                ArtistItem(getId(), artist.getString("name"), getArtistTopTracks(artist.getString("name")).map { topTrack ->
                    TrackItem(getId(), topTrack.getString("name"), listOf(ArtistItem(getId(), artist.getString("name"))),
                        genres = getGenres(topTrack, MetaItem.Artist)) })}}}.awaitAll()
            .firstOrNull()

        if (artistItem == null)
            return false
        else {
            addedArtists.add(artistItem)
            return true
        }
    }

    override suspend fun addTrack(name: String, artist: String) : Boolean  {
        val params = mutableListOf(
            "method", "track.search",
            "track", name,
            "artist", artist,
            "limit", "50"
        )

        val json = sendRequest(params)

        val track = json
            .getJSONObject("results")
            .getJSONObject("trackmatches")
            .getJSONArray("track")
            .toList<JSONObject>().map {
                TrackItem(getId(), it.getString("name"), listOf(
                    ArtistItem(getId(), it.getString("artist"))))}
            .firstOrNull()

        if (track == null)
            return false
        else {
            addedTracks.add(track)
            return true
        }
    }

    override suspend fun addAlbum(name: String, artist: String) : Boolean  {
        val params = mutableListOf(
            "method", "album.search",
            "album", name,
            "limit", "50"
        )

        val json = sendRequest(params)

        val album = runBlocking { json
            .getJSONObject("results")
            .getJSONObject("albummatches")
            .getJSONArray("album")
            .toList<JSONObject>()
            .map { async {
                try {
                    val albumInfo =  getAlbumInfo(it.getString("name"), it.getString("artist"))
                    val albumInfo_track = albumInfo.getJSONObject("album").getJSONObject("tracks").getJSONArray("track")
                    val albumInfo_trackJson = albumInfo_track.toList<JSONObject>()
                    AlbumItem(getId(), it.getString("name"), albumInfo_trackJson.map {
                        TrackItem(getId(), it.getString("name"), listOf(
                            ArtistItem(getId(), it.getJSONObject("artist").getString("name"))))},
                    listOf(ArtistItem(getId(), it.getString("artist"))))}
                catch(e: Exception) {
                    null
                }}}.awaitAll().filterNotNull()
            .firstOrNull {
                it.artists.any {
                    it.name.contains(artist, true)}}}

        if (album == null)
            return false
        else {
            addedAlbums.add(album)
            return true
        }
    }

    override suspend fun addGenre(name: String) : Boolean  {
        addedGenres.add(name)
        return true
    }

    override suspend fun getSimilarTracks(): List<TargetDirectory> {
        var tracks = getSimilarTrackItems()

        val subFolderName = getSubFolder_Station()
        val searchQueries = tracks.map { SearchQuery(it.name, it.artists.map { it.name }) }
        val directory = TargetDirectory(getPath(DownloadFolder.Stations, subFolderName), searchQueries)

        return listOf(directory)
    }

    override suspend fun getSimilarAlbums(): List<TargetDirectory> {
        var tracks = getSimilarTrackItems(true)

        val trackGroups = tracks
            .groupBy { it.album!!.name }
            .map { Pair(it.key, it.value) }

        val directories = getTargetDirectories(DownloadFolder.Albums, trackGroups, options.resultsSimilarAlbums)

        return directories
    }

    override suspend fun getSimilarArtists(): List<TargetDirectory> {
        val params = mutableListOf(
            "method", "artist.getsimilar",
            "artist", addedArtists.first().name,
            //apparently results get capped at 250; if the value is higher than 1000 LastFM will default to 100 though
            //we'll always cap at 100 here because this function would surpass the rate limit on any higher values
            "limit", options.samplesSimilarArtists.coerceAtMost(100).toString()
        )

        val json = sendRequest(params)

        var artistsJson = json
            .getJSONObject("similarartists")
            .getJSONArray("artist")
            .toList<JSONObject>()
            .sortedByDescending { it.getInt("match") }

        val trackGroups = runBlocking { artistsJson.map {
            artist: JSONObject -> async {
                val topTrackJSONs = getArtistTopTracks(artist.getString("name")).take(resultsSimilarArtists_Tracks)
                val topTrackItems = topTrackJSONs.map { topTrack -> async {
                    TrackItem(getId(), topTrack.getString("name"), listOf(ArtistItem(getId(), artist.getString("name"))),
                        genres = getGenres(topTrack, MetaItem.Artist))}}.awaitAll()

                Pair(artist.getString("name"), topTrackItems)}}}.awaitAll()

        var directories = getTargetDirectories(DownloadFolder.Artists, trackGroups, options.resultsSimilarArtists)

        return directories
    }

    override fun changeMode(mode: QueryMode) {
        this.mode = mode
    }

    override fun clear() {
        addedTracks = mutableListOf()
        addedGenres = mutableListOf()
        addedArtists = mutableListOf()
        addedAlbums = mutableListOf()
    }

    override suspend fun prepare() {
        //pass
    }

    //target directories to trackGroups (for either similar artists or similar albums)
    private fun getTargetDirectories(folder: DownloadFolder, subFolders: List<Pair<String, List<TrackItem>>>, subFoldersLimit: Int) = subFolders
        .mapNotNull { (subFolderName, tracks__) ->
            var tracks_ = filterExceptions(tracks__)
            var tracks = filterGenres(tracks_)

            if (tracks.count() > 0) {
                tracks = tracks_ //download all tracks, as others might just lack a proper label...
                val subFolder = getSubFolder_Similar() + "/" + subFolderName
                val searchQueries = tracks.map { SearchQuery(it.name, it.artists.map { it.name }) }
                TargetDirectory(getPath(folder, subFolder), searchQueries)}
            else
                null }
        .take(subFoldersLimit)

    private suspend fun LastFMQuery.getSimilarTrackItems(includeAlbums: Boolean = false): List<TrackItem> {
        val params = mutableListOf(
            "method", "track.getsimilar",
            "artist", addedArtists.first().name,
            //apparently results get capped at 250; if the value is higher than 1000 LastFM will default to 100 though
            "limit", "1000"
        )

        if (addedTracks.count() > 0)
            params.addAll(listOf("track", addedTracks.first().name))

        val json = sendRequest(params)

        var tracksJson = json
            .getJSONObject("similartracks")
            .getJSONArray("track")
            .toList<JSONObject>()
            .sortedByDescending { it.getInt("match") }

        if (addedGenres.count() == 0) //if genres are added the results will already be reduced enough...
            tracksJson = tracksJson.take(50) //limit results to the most similar tracks

        var tracks = runBlocking { tracksJson
            .map { async {
                val trackName = it.getString("name")
                val artistName = it.getJSONObject("artist").getString("name")
                var albumItem: AlbumItem? = null

                try {
                    if (includeAlbums) {
                        val albumInfo = getAlbumInfo(trackName, artistName)
                        val albumInfo_album = albumInfo.getJSONObject("album")
                        if (albumInfo_album.has("tracks")) {
                            val albumName = albumInfo_album.getString("name")
                            val albumTracksJson = albumInfo_album.getJSONObject("tracks").getJSONArray("track").toList<JSONObject>()
                            val albumTracks = albumTracksJson.map { TrackItem( getId(), it.getString("name"), listOf(ArtistItem(getId(), artistName))) }
                            albumItem = AlbumItem(getId(), albumName, albumTracks, listOf(ArtistItem(getId(), artistName)))}}

                    if (includeAlbums && albumItem == null) //includeAlbums == true, but no tracks were found
                        null
                    else //either no albums should be included in the first place, or albums should be AND tracks were found
                        TrackItem(getId(), trackName, listOf(ArtistItem(getId(), artistName)),
                            genres = getGenres(it, MetaItem.Track),
                            album = albumItem)}
                catch (e: Exception) {
                    null
                }}}}.awaitAll()
            .filterNotNull()

        tracks = filterGenres(tracks)
        tracks = filterExceptions(tracks)
        return tracks
    }

    private suspend fun getTrackInfo(name: String, artist: String): JSONObject {
        val params = mutableListOf(
            "method", "track.getInfo",
            "track", name,
            "artist", artist,
            "limit", "1"
        )

        val jsonObject = sendRequest(params)
        return jsonObject
    }

    private suspend fun getArtistInfo(name: String): JSONObject {
        val params = mutableListOf(
            "method", "artist.getInfo",
            "artist", name,
            "limit", "1"
        )

        val jsonObject = sendRequest(params)
        return jsonObject
    }

    private suspend fun getAlbumInfo(name: String, artist: String): JSONObject {
        val params = mutableListOf(
            "method", "album.getInfo",
            "album", name,
            "artist", artist,
            "limit", "1"
        )

        val jsonObject = sendRequest(params)
        return jsonObject
    }

    private suspend fun getArtistTopTracks(name: String): List<JSONObject> {
        val params = mutableListOf(
            "method", "artist.getTopTracks",
            "artist", name,
            "limit", "10"
        )

        val jsonObject = sendRequest(params)
        val jsonObjects = jsonObject.getJSONObject("toptracks").getJSONArray("track").toList<JSONObject>()
        return jsonObjects
    }

    private suspend fun getAlbumTracks(name: String, artist: String): List<JSONObject> {
        val tracks = getAlbumInfo(name, artist)
            .getJSONObject("album")
            .getJSONObject("tracks")
            .getJSONArray("track")
            .toList<JSONObject>()

        return tracks
    }

    private suspend fun sendRequest(params: MutableList<String>, default: String? = null): JSONObject {
        val response = service.createRequest().setError { JSONObject(it).has("error") }.setParameters(*getParameters(*params.toTypedArray())).get()
        val json = if (default == null) response.getContent() else response.getContentOrDefault(default!!)
        val jsonObject = JSONObject(json)
        return jsonObject
    }

    //itemsJson: tracks, artists or albums
    private suspend fun getGenres(track: JSONObject, metaItem: MetaItem): List<String> {
        var genres =
            try {
                val tagsRoot =
                    when (metaItem) {
                        MetaItem.Track -> getTrackInfo(track.getString("name"), track.getJSONObject("artist").getString("name"))
                            .getJSONObject("track").getJSONObject("toptags")
                        MetaItem.Artist -> getArtistInfo(track.getString("name"))
                            .getJSONObject("artist").getJSONObject("tags")
                        //                            MetaItem.Album -> getAlbumInfo(it.getString("name"), it.getString("artist"))
                        //                                .getJSONObject...
                        else -> throw Exception("Unknown type passed!")
                    }

                val tags = tagsRoot.getJSONArray("tag").toList<JSONObject>().map { it.getString("name") }
                tags
            }
            catch (e: WebRequestErrorException) {
                println(e); listOf()
            }

        return genres
    }

    private fun getParameters(vararg parameters: String): Array<String> {
        val result = mutableListOf<String>()

        result.addAll(listOf(
            "api_key", options.lastfmApiKey,
            "format", "json"
        ))

        result.addAll(parameters)

        return result.toTypedArray()
    }

    private fun getId(): String =
        UUID.randomUUID().toString()
}
