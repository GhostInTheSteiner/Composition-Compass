package com.gits.compositioncompass.Queries

import com.gits.compositioncompass.Models.AlbumItem
import com.gits.compositioncompass.Models.ArtistItem
import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import DownloadFolder
import Fields
import QueryMode
import com.gits.compositioncompass.Models.SearchQuery
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.WebRequest
import com.gits.compositioncompass.Models.TargetDirectory
import com.gits.compositioncompass.Models.TrackItem
import org.json.JSONObject
import toList
import java.util.*

class LastFMQuery
: IStreamingServiceQuery, Query {

    private var addedArtist: ArtistItem
    private var addedTrack: TrackItem
    private var addedGenre: String
    private var addedAlbum: AlbumItem

    override val requiredFields: List<List<Fields>> get() = when (QueryMode.SimilarTracks) {
        QueryMode.Specified -> listOf(listOf(Fields.Artist), listOf(Fields.Genre))
        else                -> listOf(listOf(Fields.Genre, Fields.Track, Fields.Artist))
    }

    override val supportedFields: List<Fields> =
        listOf(Fields.Genre, Fields.Track, Fields.Artist, Fields.Album)

    private var mode: QueryMode
    private var api: WebRequest

    constructor(options: CompositionCompassOptions): super(options) {
        this.mode = QueryMode.SimilarTracks
        this.api = WebRequest("https://ws.audioscrobbler.com").addEndpoint("2.0")
        this.options = options

        addedTrack = TrackItem("", "", listOf(), 0)
        addedGenre = ""
        addedArtist = ArtistItem("", "", 0)
        addedAlbum = AlbumItem("", "", listOf(), listOf(), 0)
    }

    override suspend fun searchArtist(name: String): List<ArtistItem> {
        val params = mutableListOf(
            "method", "artist.search",
            "artist", name,
            "limit", "100"
        )

        api.addParameters(*getParameters(*params.toTypedArray()))

        val json = api.get()

        val artists = JSONObject(json)
            .getJSONObject("results")
            .getJSONObject("artistmatches")
            .getJSONArray("artist")
            .toList<JSONObject>()
            .map {
                ArtistItem(
                    getId(it),
                    it.getString("name"),
                    it.getString("listeners").toInt()
                )
            }

        return artists
    }

    //TODO: use different approach for album
    override suspend fun searchTrack(name: String, artist: String, album: String): List<TrackItem> {
        val params = mutableListOf(
            "method", "track.search",
            "track", name,
            "limit", "100"
        )

        if (artist.length > 0)
            params.addAll(listOf("artist", artist))

        api.addParameters(*getParameters(*params.toTypedArray()))

        val json = api.get()

        val tracks = JSONObject(json)
            .getJSONObject("results")
            .getJSONObject("trackmatches")
            .getJSONArray("track")
            .toList<JSONObject>()
            .map {
                TrackItem(
                    getId(it),
                    it.getString("name"),
                    listOf(
                        ArtistItem(
                            UUID.randomUUID().toString(),
                            it.getString("artist")
                        )
                    ),
                    it.getString("listeners").toInt()
                )
            }

        return tracks
    }

    override suspend fun searchAlbum(name: String, artist: String): List<AlbumItem> {
        val params = mutableListOf(
            "method", "album.search",
            "album", name,
            "limit", "100"
        )

        api.addParameters(*getParameters(*params.toTypedArray()))

        val json = api.get()

        val albums = JSONObject(json)
            .getJSONObject("results")
            .getJSONObject("albummatches")
            .getJSONArray("album")
            .toList<JSONObject>()
            .map {
                AlbumItem(
                    getId(it),
                    it.getString("name"),
                    listOf(),
                    listOf(
                        ArtistItem(
                            UUID.randomUUID().toString(),
                            it.getString("artist"),
                            0
                        )
                    )
                )
            }
            .filter {
                it.artists.any {
                    it.name.contains(artist) }}

        return albums
    }

    override suspend fun searchGenre(name: String): List<String> {
        val params = mutableListOf(
            "method", "tag.getTopTags",
            "limit", "100"
        )

        api.addParameters(*getParameters(*params.toTypedArray()))

        val json = api.get()

        val tags = JSONObject(json)
            .getJSONObject("toptags")
            .getJSONArray("tag")
            .toList<JSONObject>()
            .map { it.getString("name") }

        return tags
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
        val params = mutableListOf(
            "method", "track.getsimilar",
            "track", addedTrack.name,
            "artist", addedArtist.name,
            "limit", "100"
        )

        api.addParameters(*getParameters(*params.toTypedArray()))

        val json = api.get()

        val tracks = JSONObject(json)
            .getJSONObject("similartracks")
            .getJSONArray("track")
            .toList<JSONObject>()
            .map {
                TrackItem(
                    getId(it),
                    it.getString("name"),
                    listOf(
                        ArtistItem(
                            getId(it.getJSONObject("artist")),
                            it.getJSONObject("artist").getString("name")
                        )
                    )
                )
            }

        val subFolderName = addedArtist.initials + " (" + listOf(addedTrack, addedGenre).joinToString("; ").trim().trim(';') + ")"
        val searchQueries = tracks.map { SearchQuery(it.name, it.artists.map { it.name }) }
        val directory =
            TargetDirectory(getPath(DownloadFolder.Stations, subFolderName), searchQueries)

        return listOf(directory)
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
        addedTrack = TrackItem("", "", listOf(), 0)
        addedGenre = ""
        addedArtist = ArtistItem("", "", 0)
        addedAlbum = AlbumItem("", "", listOf(), listOf(), 0)
    }

    override suspend fun prepare() {
        //pass
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

    private fun getId(jsonObject: JSONObject): String {
            if (!jsonObject.has("mbid"))
                return UUID.randomUUID().toString()

            else if (jsonObject.getString("mbid").length == 0)
                return UUID.randomUUID().toString()

            else
                return jsonObject.getString("mbid")
    }
}
