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
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.ItemPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import toList
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

//Talks to the modern REST API that backs pandora.com and the current mobile apps,
//documented (loosely) at https://6xq.net/pandora-apidoc/rest/. Unlike PandoraQuery's
//JSON-RPC v5 "tuner" API, this API needs no partner credentials, no Blowfish body
//encryption and no clock sync - it's plain authenticated JSON-over-HTTPS, closer in
//spirit to a normal REST client. Station creation + playlist/getFragment stands in
//for Spotify/LastFM's "similar tracks" recommendation endpoints, same as PandoraQuery.
//
//Only requires the user's own Pandora account credentials (via CompositionCompassOptions) -
//there is no separate "partner" login step in this API.
//
//A couple of endpoints this class relies on (station.addSeed, search.fullSearch) are
//listed by name in 6xq.net's endpoint index but have no documented request/response
//schema. Those spots are called out below with the assumption being made and the
//documented endpoints their shape was inferred from (getStationRecommendations,
//getStationDetails, playlist.getFragment, station.createStation all ARE documented
//and were used as the basis for field names like musicId/pandoraId/stationId).
class PandoraRestQuery(options: CompositionCompassOptions, picker: ItemPicker) : Query(options, picker), IStreamingServiceQuery {

    override val requiredFields: List<List<Fields>> get() = when (mode) {
        QueryMode.SpecifiedMoreInteresting -> listOf(listOf())
        QueryMode.Specified -> listOf(
                listOf(Fields.Artist),
                listOf(Fields.Artist, Fields.Track),
                listOf(Fields.Artist, Fields.Album)
        )
        else -> listOf(
                listOf(Fields.Artist),
                listOf(Fields.Artist, Fields.Track)
        )
    }

    override val supportedFields: List<Fields> get() = when (mode) {
        QueryMode.SpecifiedMoreInteresting -> listOf(Fields.Favorites)
        else -> listOf(Fields.Track, Fields.Artist, Fields.Album)
    }

    private var mode: QueryMode = QueryMode.SimilarTracks

    //session state - populated by prepare()
    private var csrfToken: String? = null
    private var authToken: String? = null
    private var listenerId: String? = null

    //shared seed station that accumulates all addArtist / addTrack / addAlbum / addGenre calls
    private var seedStationId: String? = null

    //MainActivity.download() calls addArtist/addTrack/addAlbum/addGenre from separate
    //concurrent coroutines (one launch{} per field). addSeed()'s "create the station if
    //none exists yet, else add to it" logic is a check-then-act on seedStationId -
    //without this lock, two concurrent seeds can both see it as null, both call
    //createStation(), and whichever assignment lands last silently wins, orphaning the
    //other seed's station (it's created, but never queried again). Mirrors PandoraQuery.
    private val seedMutex = Mutex()

    //playlist/getFragment returns a handful of tracks per call, same shape as the JSON
    //v5 API's station.getPlaylist. Sampling a station is done by calling it repeatedly,
    //capped for the same reason as PandoraQuery: options.samplesSimilar* defaults are
    //tuned for Spotify/LastFM's much higher-throughput endpoints.
    private val maxPlaylistCalls = 40
    private val tracksPerPlaylistCall = 4

    private val apiHost = "www.pandora.com"
    private val apiBase = "https://$apiHost/api"

    override fun changeMode(mode: QueryMode) {
        this.mode = mode
    }

    override fun clear() {
        addedGenres.clear()
        addedArtists.clear()
        addedTracks.clear()
        addedAlbums.clear()
        seedStationId = null
    }

    //needs to be called before any other functions!
    override suspend fun prepare() {
        if (authToken == null) {
            fetchCsrfToken()
            userLogin()
        }
    }

    // ─── Search ─────────────────────────────────────────────────────────────

    override suspend fun searchArtist(name: String, completeData: Boolean): List<ArtistItem> {
        if (name.isEmpty()) return listOf()

        val resp = searchPandora(name)
        val artists = resp.optJSONArray("artists")?.toList<JSONObject>() ?: listOf()

        return artists.map {
            ArtistItem(
                    id = it.getString("pandoraId"),
                    name = it.getString("name"),
                    popularity = if (it.optBoolean("likelyMatch")) 50 else 100
            )
        }
    }

    override suspend fun searchTrack(name: String, artist: String, album: String): List<TrackItem> {
        val searchText = listOf(name, artist).filter { it.isNotEmpty() }.joinToString(" ")
        if (searchText.isEmpty()) return listOf()

        val resp = searchPandora(searchText)
        val songs = resp.optJSONArray("tracks")?.toList<JSONObject>() ?: listOf()

        return songs.map {
            TrackItem(
                    id = it.getString("pandoraId"),
                    name = it.getString("songTitle"),
                    artists = listOf(ArtistItem(id = "", name = it.getString("artistName"))),
                    popularity = it.optInt("score", 0)
            )
        }
    }

    //As with PandoraQuery: this REST API has no native "album" search either - we
    //approximate an album as the set of songs matched by a combined title+artist search.
    override suspend fun searchAlbum(name: String, artist: String): List<AlbumItem> =
    searchAlbumApproximate(name, artist)

    override suspend fun searchGenre(name: String, artist: String): List<String> {
        val resp = searchPandora(name.ifEmpty { "genre" })
        val genreStations = resp.optJSONArray("genreStations")?.toList<JSONObject>() ?: listOf()

        return genreStations
                .map { it.getString("name") }
            .filter { name.isEmpty() || it.contains(name, ignoreCase = true) }
    }

    // ─── Add ────────────────────────────────────────────────────────────────

    override suspend fun addArtist(name: String): Boolean {
        val artist = searchArtist(name).firstOrNull() ?: return false
        addSeed(artist.id)
        addedArtists.add(
                ArtistItem(
                        id = artist.id,
                        name = artist.name,
                        topTracks = listOf(),
                        popularity = artist.popularity
                )
        )
        return true
    }

    override suspend fun addTrack(name: String, artist: String): Boolean {
        val tracks = searchTrack(name, artist, "")
        if (tracks.isEmpty()) return false

        val match = tracks.firstOrNull {
            it.name.equals(name, true) && it.artists.any { a -> a.name.contains(artist, true) }
        } ?: tracks.firstOrNull {
            it.name.contains(name, true) && it.artists.any { a -> a.name.contains(artist, true) }
        } ?: tracks.first()

        addSeed(match.id)
        addedTracks.add(match)
        return true
    }

    override suspend fun addAlbum(name: String, artist: String): Boolean {
        val album = searchAlbumApproximate(name, artist).firstOrNull() ?: return false
        album.tracks
                .distinctBy { it.id }
            .filter { it.id.isNotEmpty() }
            .forEach { addSeed(it.id) }
        addedAlbums.add(album)
        return true
    }

    override suspend fun addGenre(name: String): Boolean {
        val genrePandoraId = findGenreMusicToken(name) ?: return false
        addSeed(genrePandoraId)
        addedGenres.add(name)
        return true
    }

    // ─── Similarity ─────────────────────────────────────────────────────────

    override suspend fun getSimilarTracks(): List<TargetDirectory> {
        val stationId = createSeedStation()

        val tracks = (1..10)
            .flatMap { getPlaylist(stationId) }
            .let { filterExceptions(it.map { item -> playlistItemToTrack(item) }) }

        val subFolderName = getSubFolder_Station()

        val path = getPath(DownloadFolder.Stations, subFolderName)
        val searchQueries = tracks.map { SearchQuery(it.name, it.artists.map { a -> a.name }) }

        return listOf(TargetDirectory(path, searchQueries))
    }

    override suspend fun getSimilarAlbums(): List<TargetDirectory> {
        val stationId = createSeedStation()
        val sampleSize = options.samplesSimilarAlbums.coerceAtMost(maxPlaylistCalls * tracksPerPlaylistCall)
        val tracks = filterExceptions(sampleStation(stationId, sampleSize))

        val albumGroups = tracks
                .filter { it.album != null }
            .groupBy { it.album!!.name }
            .toList()
                .sortedByDescending { (_, groupTracks) -> groupTracks.size }
            .take(options.resultsSimilarAlbums)

        return albumGroups.map { (albumName, albumTracks) ->
                val path = getPath(DownloadFolder.Albums, getSubFolder_Similar() + "/" + albumName)
            val searchQueries = albumTracks.map { SearchQuery(it.name, albumTracks.firstOrNull { it.album?.name == albumName }?.artists?.map { it.name } ?: listOf(albumTracks.first().artists.first().name), albumName) }
            TargetDirectory(path, searchQueries)
        }
    }

    override suspend fun getSimilarArtists(): List<TargetDirectory> {
        val stationId = createSeedStation()
        val sampleSize = options.samplesSimilarArtists.coerceAtMost(maxPlaylistCalls * tracksPerPlaylistCall)
        val tracks = filterExceptions(sampleStation(stationId, sampleSize))

        val artistGroups = tracks
                .filter { it.artists.isNotEmpty() }
            .groupBy { it.artists.first().name }
            .toList()
                .sortedByDescending { (_, groupTracks) -> groupTracks.size }
            .take(options.resultsSimilarArtists)

        return artistGroups.map { (artistName, artistTracks) ->
                val path = getPath(DownloadFolder.Artists, getSubFolder_Similar() + "/" + artistName)
            val topTracks = artistTracks.distinctBy { it.name }.take(resultsSimilarArtists_Tracks)
            val searchQueries = topTracks.map { SearchQuery(it.name, listOf(artistName)) }
            TargetDirectory(path, searchQueries)
        }
    }

    // ─── Pandora session / low-level API ───────────────────────────────────

    //Per https://6xq.net/pandora-apidoc/rest/#csrf-token-cookie: every request needs a
    //matching X-CsrfToken header and csrftoken cookie. The docs note the API only checks
    //that the two match (so the value itself is arbitrary), but they still SHOULD be
    //obtained via a HEAD request to the root domain - we do that rather than relying on
    //the looser undocumented behaviour, since Pandora "will not serve cookies until after
    //authentication" and a HEAD to '/' is the one place that's documented to hand one out.
    private suspend fun fetchCsrfToken() = withContext(Dispatchers.IO) {
        val connection = URL("https://$apiHost/").openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connect()

        val cookies = connection.headerFields["Set-Cookie"] ?: listOf()
        csrfToken = cookies
                .firstNotNullOfOrNull { Regex("csrftoken=([^;]+)").find(it)?.groupValues?.get(1) }

        connection.disconnect()

        if (csrfToken == null)
            throw Exception("Could not obtain a Pandora csrftoken cookie from a HEAD request to https://$apiHost/")
    }

    private suspend fun userLogin() {
        val result = callApi(
                "/v1/auth/login",
                mapOf(
                        "username" to options.pandoraUsername,
                        "password" to options.pandoraPassword,
                        "existingAuthToken" to null,
                "keepLoggedIn" to true
            ),
        requiresAuth = false
        )

        authToken = result.getString("authToken")
        listenerId = result.optString("listenerId").ifEmpty { null }
    }

    //search.fullSearch is listed at https://6xq.net/pandora-apidoc/rest/endpoints/ but has
    //no documented request/response page. The request body below and the "artists" /
    //"tracks" / "genreStations" response keys read elsewhere in this class follow the shape
    //actually used by pandora.com's web client, and line up with the field names (musicId,
    //pandoraId, name, songTitle, artistName) that ARE documented on the Search/Music/
    //Playlist pages for the same entities.
    private suspend fun searchPandora(text: String): JSONObject =
            callApi(
                    "/v1/search/fullSearch",
                    mapOf(
                            "query" to text,
                            "types" to listOf("AR", "TR", "GE"),
                            "listener" to null,
            "start" to 0,
            "count" to 50,
            "annotate" to false
            )
        )

    //Create Station: https://6xq.net/pandora-apidoc/rest/stations/#create-station.
    //The documented sample request carries both a "stationCode" and a "pandoraId" field
    //(with the former example oddly reading "mcR750856"); we send the plain musicId as
    //stationCode plus the fully-qualified pandoraId, which is accepted for every seed type
    //(AR:/TR:/GE:) we create from.
    private suspend fun createStation(musicId: String, pandoraId: String): String {
        val result = callApi(
                "/v1/station/createStation",
                mapOf(
                        "stationCode" to musicId,
                        "stationName" to "",
                        "searchQuery" to null,
                "pandoraId" to pandoraId,
        "creativeId" to null,
                "lineId" to null,
                "creationSource" to null
            )
        )
        return result.getString("stationId")
    }

    //station.addSeed is listed at https://6xq.net/pandora-apidoc/rest/endpoints/ with no
    //documented schema. stationId/pandoraId are the identifiers this API uses consistently
    //everywhere else (getStationDetails, createStation's response, playlist.getFragment),
    //so that's what's sent here.
    private suspend fun addSeedToStation(stationId: String, pandoraId: String) {
        callApi(
                "/v1/station/addSeed",
                mapOf(
                        "stationId" to stationId,
                        "pandoraId" to pandoraId
                )
        )
    }

    //Accumulates seeds on a single shared station, same check-then-act-under-lock pattern
    //as PandoraQuery.addSeed(). The parameter here is a pandoraId (e.g. "AR:123",
    //"TR:456", "GE:789") rather than the JSON v5 API's bare musicToken.
    private suspend fun addSeed(pandoraId: String) {
        if (pandoraId.isEmpty()) return

                seedMutex.withLock {
            if (seedStationId == null) {
                val musicId = pandoraId.substringAfter(":")
                seedStationId = createStation(musicId, pandoraId)
            } else {
                addSeedToStation(seedStationId!!, pandoraId)
            }
        }
    }

    private suspend fun findGenreMusicToken(name: String): String? {
            val resp = searchPandora(name.ifEmpty { "genre" })
    val genreStations = resp.optJSONArray("genreStations")?.toList<JSONObject>() ?: listOf()
    return genreStations
            .firstOrNull { name.isEmpty() || it.getString("name").contains(name, ignoreCase = true) }
            ?.getString("pandoraId")
    }

    private suspend fun createSeedStation(): String {
        // The station was already created and populated dynamically during the
        // addArtist/addTrack/addAlbum/addGenre calls via addSeed().
        // If it's null here, it means no valid seeds were found or added.
        return seedStationId ?: throw Exception("No valid seeds were added to create a station! Required field 'artist' or 'track' not found.")
    }

    //Get Fragment: https://6xq.net/pandora-apidoc/rest/playlist/#get-fragment
    private suspend fun getPlaylist(stationId: String): List<JSONObject> {
        val result = callApi(
                "/v1/playlist/getFragment",
                mapOf(
                        "stationId" to stationId,
                        "isStationStart" to false,
                "fragmentRequestReason" to "Normal",
                "audioFormat" to "aacplus",
                "startingAtTrackId" to null,
                "onDemandArtistMessageArtistUidHex" to null,
                "onDemandArtistMessageIdHex" to null
            )
        )

        val tracks = result.optJSONArray("tracks")?.toList<JSONObject>() ?: listOf()
        return tracks.filter { it.optString("trackType", "Track") == "Track" } //drop ad breaks
    }

    //repeatedly pulls from a station's continuous playlist to build up a larger sample,
    //the Pandora equivalent of Spotify/LastFM's "get N recommendations" loop
    private suspend fun sampleStation(stationId: String, minimumSize: Int): List<TrackItem> {
        val tracks = mutableListOf<TrackItem>()
        var calls = 0

        while (tracks.size < minimumSize && calls < maxPlaylistCalls) {
            tracks += getPlaylist(stationId).map { playlistItemToTrack(it) }
            calls++
        }

        return tracks
    }

    private fun playlistItemToTrack(item: JSONObject): TrackItem {
        val artistName = item.optString("artistName")
        val albumName = item.optString("albumTitle")

        val album =
        if (albumName.isNotEmpty())
            AlbumItem(
                    id = "",
                    name = albumName,
                    tracks = listOf(),
                    artists = listOf(ArtistItem(id = "", name = artistName))
            )
        else null

        return TrackItem(
                id = item.optString("trackToken"),
                name = item.getString("songTitle"),
                artists = listOf(ArtistItem(id = "", name = artistName)),
                album = album
        )
    }

    private suspend fun searchAlbumApproximate(name: String, artist: String): List<AlbumItem> {
        val searchText = listOf(name, artist).filter { it.isNotEmpty() }.joinToString(" ")
        if (searchText.isEmpty()) return listOf()

        val resp = searchPandora(searchText)
        val songs = resp.optJSONArray("tracks")?.toList<JSONObject>() ?: listOf()
        if (songs.isEmpty()) return listOf()

        val tracks = songs.map {
            TrackItem(
                    id = it.getString("pandoraId"),
                    name = it.getString("songTitle"),
                    artists = listOf(ArtistItem(id = "", name = it.getString("artistName"))),
                    popularity = it.optInt("score", 0)
            )
        }

        return listOf(
                AlbumItem(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        tracks = tracks,
                        artists = listOf(ArtistItem(id = "", name = artist)),
                        popularity = 0
                )
        )
    }

    //Pandora's REST API returns error details in the same body regardless of status
    //code, but the body still only shows up on the input stream for 2xx responses.
    private fun readResponseBody(connection: HttpURLConnection): String {
        val stream =
        if (connection.responseCode in 200..299) connection.inputStream
            else connection.errorStream ?: connection.inputStream

        return stream.bufferedReader().use { it.readText() }
    }

    //builds, sends and parses a single REST API call. Mirrors PandoraQuery.callApi(),
    //minus the JSON-RPC "method" query param, syncTime and Blowfish encryption this
    //API doesn't use - auth and CSRF are carried in headers instead.
    private suspend fun callApi(
            endpoint: String,
            data: Map<String, Any?>,
            requiresAuth: Boolean = true
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
        data.forEach { (key, value) -> body.put(key, value ?: JSONObject.NULL) }

        val connection = URL("$apiBase$endpoint").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json;charset=utf-8")
        connection.setRequestProperty("X-CsrfToken", csrfToken ?: "")
        connection.setRequestProperty("Cookie", "csrftoken=${csrfToken ?: ""}")

        if (requiresAuth)
            connection.setRequestProperty("X-AuthToken", authToken ?: "")
        else
        connection.setRequestProperty("X-AuthToken", "") //acceptable empty value during login, per docs

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = connection.responseCode
        val responseText = readResponseBody(connection)
        connection.disconnect()

        val json = if (responseText.isNotBlank()) JSONObject(responseText) else JSONObject()

        //"Any responses with a 200 status code are successful" - errors carry an
        //errorCode/errorString/message body instead. https://6xq.net/pandora-apidoc/rest/#errors
        if (responseCode !in 200..299)
        throw Exception(
                "Pandora REST API error " + json.optInt("errorCode") + " (" + json.optString("errorString") + "): " +
                        json.optString("message")
        )

        json
    }
}
