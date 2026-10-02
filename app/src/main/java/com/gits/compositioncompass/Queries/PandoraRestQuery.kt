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
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.TorManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import toList
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.Collections

//Talks to the REST API behind pandora.com (https://6xq.net/pandora-apidoc/rest/). Unlike
//PandoraQuery's JSON-RPC "tuner" API it needs no partner credentials, no Blowfish body
//encryption and no clock sync - plain authenticated JSON over HTTPS, using the account
//credentials from the options.
//
//Every call below was checked against real pandora.com traffic (HAR captures of the web
//player: login, search, album page, artist page, station start and playback), except:
//  - createStation for AR:/GE: ids : the captures only ever create a station from a TR: id.
//                                    The docs show artist stations exist, but not which id
//                                    form is accepted, so there is a fallback (see addSeed)
//  - the csrf cookie bootstrap      : the captures start with the login request that already
//                                    carries the cookie; HEAD / comes from the docs
//  - re-login on HTTP 401           : no capture ever saw an expired session
//  - the JSON error body of login   : only the success response was captured
//
//Captured calls:
//  login         POST /api/v1/auth/login                      {existingAuthToken:null, username, password,
//                                                              keepLoggedIn:true} -> {authToken, listenerId, ...}
//                (no X-AuthToken header; X-CsrfToken header == csrftoken cookie; the response sets the
//                 cookies wrt / at which the web player sends back on every later request)
//  search        POST /api/v3/sod/search                      {query, types, listener, start, count,
//                                                              annotate, searchTime, annotationRecipe}
//                -> {searchToken, results:[ids], annotations:{id:{name, artistName, albumName, tracks...}}}
//  genres        POST /api/v1/search/getStationRecommendations {}  -> {genreStations:[{name, pandoraId...}]}
//  artist page   POST /api/v4/catalog/getDetails              {pandoraId:"AR:..."} -> {annotations, artistDetails.topTracks}
//  album page    POST /api/v4/catalog/getDetails              {pandoraId:"AL:..."} -> {annotations (album + ALL its
//                                                              tracks: name, artistName, trackNumber), albumDetails}
//  new station   POST /api/v1/station/createStation           {pandoraId, other fields null} -> {stationId...}
//  playlist      POST /api/v1/playlist/getFragment            {stationId, isStationStart, ...} -> {tracks:[...]}
//
//The captures contain NO call for adding a seed to an existing station (the endpoint list in
//the docs names /v1/station/addSeed but there is no schema, and the web client never calls it
//while starting playback). So, instead of one station accumulating all seeds like PandoraQuery
//does, every seed gets its OWN station here and the playlists of all of them are sampled
//round-robin and merged.
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

    //session state - populated by prepare(). Volatile because prepare() can be entered
    //from several threads (autocomplete + download), and callApi reads these.
    @Volatile private var csrfToken: String? = null
    @Volatile private var authToken: String? = null

    //Cookies the server hands out (the login response sets wrt / at, ...). The web player sends
    //them back on every later request, so we do too. csrftoken is kept separately above.
    private val cookies: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())

    //MainActivity fires prepare() on EVERY keystroke in the autocomplete fields, and again
    //from download(), all on separate coroutines - serialise the login so they can't interleave.
    private val loginMutex = Mutex()

    //One Pandora station per seed (see class comment). MainActivity.download() calls the
    //add* functions from separate concurrent coroutines, hence the synchronised collections.
    private val seedStationIds: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val seededPandoraIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    //The first getFragment call of a station is flagged isStationStart=true (as the web
    //client does, and the docs say); every later one is a plain continuation.
    private val startedStations: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    //getFragment returns 4-5 entries per call (of which one can be a non-song "ArtistMessage").
    //Sampling is done by calling it repeatedly, capped for the same reason as PandoraQuery:
    //options.samples* defaults are tuned for Spotify/LastFM's much higher-throughput endpoints.
    private val maxPlaylistCalls = 40
    private val tracksPerPlaylistCall = 4

    //An album seed is represented by this many of its tracks. Every seed costs one station
    //(created in the user's Pandora account), so seeding all tracks of an album is not an option.
    private val maxSeedsPerAlbum = 3

    //Tor circuits are slow; be generous, but never hang forever.
    private val connectTimeoutMs = 60_000
    private val readTimeoutMs = 60_000

    private val apiHost = "www.pandora.com"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    //the exact type list the web player's search box sends
    private val searchTypes = listOf("AL", "AR", "CO", "TR", "SF", "PL", "ST", "PC", "PE")

    //one entry of a search response, taken from the "annotations" of the matching id
    private class Hit(
        val pandoraId: String,
        val name: String,
        val artistName: String,
        val albumName: String,
        val trackIds: List<String>, //only filled for albums
        val rank: Int //higher = better match
    ) {
        val type: String get() = pandoraId.substringBefore(":")
    }

    //"Passive Me, Aggressive You" must match a search for "passive me aggressive you"
    private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    //JSON null and missing keys both come back as "" (plain optString would return "null")
    private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

    override fun changeMode(mode: QueryMode) {
        this.mode = mode
    }

    override fun clear() {
        addedGenres.clear()
        addedArtists.clear()
        addedTracks.clear()
        addedAlbums.clear()
        seedStationIds.clear()
        seededPandoraIds.clear()
        startedStations.clear()
    }

    //needs to be called before any other functions!
    //Safe to call concurrently: only one caller performs the login, the rest wait and
    //then see the finished session.
    override suspend fun prepare() {
        if (authToken != null) return //fast path, no lock needed

        loginMutex.withLock {
            if (authToken != null) return //another caller finished while we waited

            try {
                fetchCsrfToken()
                userLogin()
            } catch (e: Exception) {
                //don't leave a half-initialised session behind: the next prepare()
                //must start again from a clean login
                csrfToken = null
                authToken = null
                cookies.clear()
                throw e
            }
        }
    }

    // ─── Search ─────────────────────────────────────────────────────────────

    override suspend fun searchArtist(name: String, completeData: Boolean): List<ArtistItem> {
        return searchHits(name)
            .filter { it.type == "AR" && it.name.isNotEmpty() }
            .mapIndexed { index, hit ->
                ArtistItem(
                    id = hit.pandoraId,
                    name = hit.name,
                    //results are ranked best-first; the top hit plays the role of
                    //the JSON API's "likelyMatch" flag
                    popularity = if (index == 0) 50 else 100
                )
            }
    }

    override suspend fun searchTrack(name: String, artist: String, album: String): List<TrackItem> {
        return searchHits(listOf(name, artist).filter { it.isNotEmpty() }.joinToString(" "))
            .filter { it.type == "TR" && it.name.isNotEmpty() }
            .map { hitToTrack(it) }
    }

    override suspend fun searchAlbum(name: String, artist: String): List<AlbumItem> =
        findAlbumHits(name, artist).map { hitToAlbum(it, listOf()) }

    override suspend fun searchGenre(name: String, artist: String): List<String> =
        findGenreStations(name).map { it.first }

    // ─── Add ────────────────────────────────────────────────────────────────

    override suspend fun addArtist(name: String): Boolean {
        val artist = searchArtist(name).firstOrNull() ?: return false
        addSeed(artist.id)

        //Query.getSpecified()'s artist-only branch reads the top tracks, so they are only
        //needed in the Specified modes; every other mode just needs the seed above.
        val topTracks =
            if (mode == QueryMode.Specified || mode == QueryMode.SpecifiedMoreInteresting)
                fetchTopTracks(artist)
            else
                listOf()

        addedArtists.add(
            ArtistItem(
                id = artist.id,
                name = artist.name,
                topTracks = topTracks,
                popularity = artist.popularity
            )
        )
        return true
    }

    override suspend fun addTrack(name: String, artist: String): Boolean {
        val tracks = searchTrack(name, artist, "")
        if (tracks.isEmpty()) return false

        val match = tracks.firstOrNull {
            normalize(it.name) == normalize(name) && it.artists.any { a -> a.name.contains(artist, true) }
        } ?: tracks.firstOrNull {
            it.name.contains(name, true) && it.artists.any { a -> a.name.contains(artist, true) }
        } ?: tracks.first()

        addSeed(match.id)
        addedTracks.add(match)
        return true
    }

    override suspend fun addAlbum(name: String, artist: String): Boolean {
        val hit = findAlbumHits(name, artist).let { hits ->
            hits.firstOrNull { normalize(it.name) == normalize(name) } ?: hits.firstOrNull()
        } ?: return false
        if (hit.trackIds.isEmpty()) return false

        hit.trackIds.take(maxSeedsPerAlbum).forEach { addSeed(it) }

        //track names are only needed when whole albums are downloaded
        val tracks = if (mode == QueryMode.Specified) resolveAlbumTracks(hit) else listOf()
        addedAlbums.add(hitToAlbum(hit, tracks))
        return true
    }

    override suspend fun addGenre(name: String): Boolean {
        val genre = findGenreStations(name).firstOrNull() ?: return false
        addSeed(genre.second)
        addedGenres.add(name)
        return true
    }

    // ─── Similarity ─────────────────────────────────────────────────────────

    override suspend fun getSimilarTracks(): List<TargetDirectory> {
        val stations = requireSeedStations()
        val tracks = filterExceptions(sampleStations(stations, 10.coerceAtLeast(stations.size)))

        val path = getPath(DownloadFolder.Stations, getSubFolder_Station())
        val searchQueries = tracks.map { SearchQuery(it.name, it.artists.map { a -> a.name }) }

        return listOf(TargetDirectory(path, searchQueries))
    }

    override suspend fun getSimilarAlbums(): List<TargetDirectory> {
        val stations = requireSeedStations()
        val tracks = filterExceptions(sampleStations(stations, callsFor(options.samplesSimilarAlbums, stations)))

        val albumGroups = tracks
            .filter { it.album != null }
            .groupBy { it.album!!.name }
            .toList()
            .sortedByDescending { (_, groupTracks) -> groupTracks.size }
            .take(options.resultsSimilarAlbums)

        return albumGroups.map { (albumName, albumTracks) ->
            val path = getPath(DownloadFolder.Albums, getSubFolder_Similar() + "/" + albumName)
            val searchQueries = albumTracks.map { SearchQuery(it.name, it.artists.map { a -> a.name }, albumName) }
            TargetDirectory(path, searchQueries)
        }
    }

    override suspend fun getSimilarArtists(): List<TargetDirectory> {
        val stations = requireSeedStations()
        val tracks = filterExceptions(sampleStations(stations, callsFor(options.samplesSimilarArtists, stations)))

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

    // ─── Catalog ────────────────────────────────────────────────────────────

    //POST /api/v3/sod/search - the web player's search. Always asks for every entity type and
    //annotated results; callers filter on Hit.type. Returns hits in Pandora's ranking order.
    private suspend fun searchHits(text: String): List<Hit> {
        if (text.isBlank()) return listOf()

        val resp = callApi(
            "/api/v3/sod/search",
            mapOf(
                "query" to text,
                "types" to searchTypes,
                "listener" to null,
                "start" to 0,
                "count" to 20,
                "annotate" to true,
                "searchTime" to 0,
                "annotationRecipe" to "CLASS_OF_2019"
            )
        )

        val ids = resp.optJSONArray("results") ?: return listOf()
        val annotations = resp.optJSONObject("annotations") ?: return listOf()

        return (0 until ids.length()).mapNotNull { index ->
            val id = ids.optString(index)
            val info = annotations.optJSONObject(id) ?: return@mapNotNull null

            val trackIds = info.optJSONArray("tracks")
                ?.let { array -> (0 until array.length()).map { array.optString(it) } }
                ?: listOf()

            Hit(
                pandoraId = id,
                name = info.text("name"),
                artistName = info.text("artistName"),
                albumName = info.text("albumName"),
                trackIds = trackIds,
                rank = ids.length() - index
            )
        }
    }

    private fun hitToTrack(hit: Hit): TrackItem =
        TrackItem(
            id = hit.pandoraId,
            name = hit.name,
            artists = listOf(ArtistItem(id = "", name = hit.artistName)),
            popularity = hit.rank
        )

    private fun hitToAlbum(hit: Hit, tracks: List<TrackItem>): AlbumItem =
        AlbumItem(
            id = hit.pandoraId,
            name = hit.name,
            tracks = tracks,
            artists = listOf(ArtistItem(id = "", name = hit.artistName)),
            popularity = 0
        )

    //albums by the requested artist first, otherwise in Pandora's ranking order
    private suspend fun findAlbumHits(name: String, artist: String): List<Hit> {
        val hits = searchHits(listOf(name, artist).filter { it.isNotEmpty() }.joinToString(" "))
            .filter { it.type == "AL" && it.name.isNotEmpty() }

        return if (artist.isEmpty()) hits
        else hits.sortedByDescending { it.artistName.contains(artist, ignoreCase = true) } //stable sort
    }

    //POST /api/v4/catalog/getDetails {pandoraId} on an artist returns artistDetails.topTracks
    //(ids, best first) and an "annotations" map holding name / artistName of each track.
    //Only if the response has no top tracks at all, fall back to a plain name search like
    //PandoraQuery does.
    private suspend fun fetchTopTracks(artist: ArtistItem): List<TrackItem> {
        val resp = callApi("/api/v4/catalog/getDetails", mapOf("pandoraId" to artist.id))
        val annotations = resp.optJSONObject("annotations") ?: JSONObject()
        val ids = resp.optJSONObject("artistDetails")?.optJSONArray("topTracks")

        val fromDetails: List<TrackItem> =
            if (ids == null) listOf()
            else (0 until ids.length()).mapNotNull { index ->
                val id = ids.optString(index)
                val info = annotations.optJSONObject(id) ?: return@mapNotNull null
                val title = info.text("name")
                if (title.isEmpty()) return@mapNotNull null

                TrackItem(
                    id = id,
                    name = title,
                    artists = listOf(ArtistItem(id = artist.id, name = info.text("artistName").ifEmpty { artist.name })),
                    popularity = ids.length() - index
                )
            }

        val tracks = fromDetails.ifEmpty {
            searchTrack("", artist.name, "")
                .filter { it.artists.any { a -> a.name.equals(artist.name, ignoreCase = true) } }
        }

        return tracks
            .distinctBy { it.name.trim().lowercase() }
            .take(resultsSimilarArtists_Tracks)
    }

    //The album annotation in search results lists its tracks only as ids. POST
    ///api/v4/catalog/getDetails {pandoraId:"AL:..."} (the web player's album page) answers an
    //"annotations" map holding the album AND every one of its tracks (name, artistName,
    //albumName, trackNumber). Refuses to return a partial album.
    private suspend fun resolveAlbumTracks(hit: Hit): List<TrackItem> {
        val resp = callApi("/api/v4/catalog/getDetails", mapOf("pandoraId" to hit.pandoraId))
        val annotations = resp.optJSONObject("annotations") ?: JSONObject()

        val tracks = hit.trackIds.mapNotNull { id ->
            val info = annotations.optJSONObject(id) ?: return@mapNotNull null
            val title = info.text("name")
            if (title.isEmpty()) return@mapNotNull null

            TrackItem(
                id = id,
                name = title,
                artists = listOf(ArtistItem(id = "", name = info.text("artistName").ifEmpty { hit.artistName }))
            )
        }

        if (tracks.size != hit.trackIds.size)
            throw Exception("Only ${tracks.size} of ${hit.trackIds.size} tracks of album '${hit.name}' could be looked up; refusing to download an incomplete album.")

        return tracks
    }

    //POST /api/v1/search/getStationRecommendations {} answers {artists, genreStations:[{name,
    //token, musicId, pandoraId ("GE:5"), ...}]}. That is a curated list, not a search, so it is
    //filtered by name here. Returns (name, pandoraId) pairs.
    private suspend fun findGenreStations(name: String): List<Pair<String, String>> {
        val resp = callApi("/api/v1/search/getStationRecommendations", mapOf())

        return (resp.optJSONArray("genreStations")?.toList<JSONObject>() ?: listOf())
            .filter { it.text("name").isNotEmpty() && it.text("pandoraId").isNotEmpty() }
            .map { it.text("name") to it.text("pandoraId") }
            .filter { name.isEmpty() || it.first.contains(name, ignoreCase = true) }
    }

    // ─── Stations ───────────────────────────────────────────────────────────

    //Seeds only matter for the "similar" modes. The Specified modes work purely from catalog
    //data, so they must not create (real, visible) stations in the user's Pandora account.
    //The parameter is a pandoraId ("AR:123", "TR:456", "GE:789").
    private suspend fun addSeed(pandoraId: String) {
        if (mode == QueryMode.Specified || mode == QueryMode.SpecifiedMoreInteresting) return
        if (pandoraId.isEmpty()) return
        if (!seededPandoraIds.add(pandoraId)) return //already seeded

        try {
            seedStationIds.add(createStationWithFallback(pandoraId))
        } catch (e: Exception) {
            seededPandoraIds.remove(pandoraId) //allow a retry
            throw e
        }
    }

    //Every capture creates stations from a TR: id only. Artist ids are expected to work the same
    //way but are unproven; if Pandora rejects one, the artist's most popular track (which IS the
    //captured case) seeds the station instead. Genre ids have no such substitute.
    private suspend fun createStationWithFallback(pandoraId: String): String {
        try {
            return createStation(pandoraId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!pandoraId.startsWith("AR:")) throw e

            val topTrackId =
                try {
                    callApi("/api/v4/catalog/getDetails", mapOf("pandoraId" to pandoraId))
                        .optJSONObject("artistDetails")?.optJSONArray("topTracks")?.optString(0)
                } catch (inner: CancellationException) {
                    throw inner
                } catch (inner: Exception) {
                    null
                }

            if (topTrackId.isNullOrEmpty()) throw e
            return createStation(topTrackId)
        }
    }

    //POST /api/v1/station/createStation - body exactly as the web player sends it when
    //starting a station from a song: only pandoraId is set. Answers the new stationId.
    private suspend fun createStation(pandoraId: String): String {
        val result = callApi(
            "/api/v1/station/createStation",
            mapOf(
                "creationSource" to null,
                "creativeId" to null,
                "lineId" to null,
                "pandoraId" to pandoraId,
                "searchQuery" to null,
                "stationCode" to null,
                "stationName" to null
            )
        )
        return result.getString("stationId")
    }

    private fun requireSeedStations(): List<String> {
        // The stations were already created during the addArtist/addTrack/addAlbum/addGenre
        // calls via addSeed(). If there are none, no valid seeds were found or added.
        val stations = synchronized(seedStationIds) { seedStationIds.toList() }
        if (stations.isEmpty())
            throw Exception("No valid seeds were added to create a station! Required field 'artist' or 'track' not found.")
        return stations
    }

    //number of getFragment calls needed for roughly sampleSize tracks, at least one per station
    private fun callsFor(sampleSize: Int, stations: List<String>): Int =
        ((sampleSize + tracksPerPlaylistCall - 1) / tracksPerPlaylistCall)
            .coerceAtMost(maxPlaylistCalls)
            .coerceAtLeast(stations.size)

    //POST /api/v1/playlist/getFragment - isStationStart is true for the first fragment of a
    //station and false afterwards. Entries that are no songs (the capture shows an
    //"ArtistMessage" with no pandoraId between the tracks) are dropped.
    private suspend fun getPlaylist(stationId: String): List<JSONObject> {
        val isFirst = startedStations.add(stationId)

        val result = callApi(
            "/api/v1/playlist/getFragment",
            mapOf(
                "audioFormat" to "aacplus",
                "fragmentRequestReason" to "Normal",
                "isStationStart" to isFirst,
                "onDemandArtistMessageArtistUidHex" to null,
                "onDemandArtistMessageIdHex" to null,
                "startingAtTrackId" to null,
                "stationId" to stationId
            )
        )

        return (result.optJSONArray("tracks")?.toList<JSONObject>() ?: listOf())
            .filter { it.text("pandoraId").isNotEmpty() && it.text("songTitle").isNotEmpty() }
    }

    //pulls `calls` fragments, round-robin over the stations, into one list of tracks - the
    //Pandora equivalent of Spotify/LastFM's "get N recommendations"
    private suspend fun sampleStations(stations: List<String>, calls: Int): List<TrackItem> {
        val tracks = mutableListOf<TrackItem>()
        for (i in 0 until calls)
            tracks += getPlaylist(stations[i % stations.size]).map { playlistItemToTrack(it) }
        return tracks
    }

    private fun playlistItemToTrack(item: JSONObject): TrackItem {
        val artistName = item.text("artistName")
        val albumName = item.text("albumTitle")

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
            id = item.text("pandoraId"),
            name = item.text("songTitle"),
            artists = listOf(ArtistItem(id = "", name = artistName)),
            album = album
        )
    }

    // ─── Session / HTTP ─────────────────────────────────────────────────────

    //Opens a connection through Tor when the user enabled it. Same fail-closed policy as
    //PandoraQuery.callApi(): with Tor enabled, never fall back to a direct connection.
    private suspend fun openConnection(url: String, method: String): HttpURLConnection {
        val proxy: Proxy =
            if (TorManager.enabled) {
                //awaitReady() only returns true once TorManager has loaded GeoIP AND
                //restricted exits to the US (Pandora rejects non-US IPs)
                if (!TorManager.awaitReady())
                    throw Exception(
                        "Tor is not available; refusing to contact Pandora directly." +
                                (TorManager.lastConfigError?.let { " Last Tor error: $it" } ?: "")
                    )

                //Read the port once, and re-check it: tor may have gone down since awaitReady().
                val port = TorManager.socksPort()
                if (port < 0)
                    throw Exception("Tor went down before the request could be sent; refusing to contact Pandora directly.")

                Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            } else {
                Proxy.NO_PROXY //user explicitly turned Tor off
            }

        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", userAgent)
        return connection
    }

    //Per https://6xq.net/pandora-apidoc/rest/: every request needs an X-CsrfToken header and
    //a matching csrftoken cookie. The API only validates that the two match, but clients
    //SHOULD get the value from a HEAD request to the root domain. If that doesn't hand out a
    //cookie we make one up, which the docs explicitly allow - a missing cookie must not make
    //login impossible.
    private suspend fun fetchCsrfToken() = withContext(Dispatchers.IO) {
        val fromServer =
            try {
                val connection = openConnection("https://$apiHost/", "HEAD")
                try {
                    connection.connect()
                    storeCookies(connection)
                    csrfToken //set by storeCookies() if the server handed one out
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                null //network hiccup on the HEAD request: use a made-up token instead
            }

        csrfToken = fromServer ?: randomHex(16)
    }

    //Remembers the cookies of a response ("name=value; Path=..." per Set-Cookie header). Header
    //names are matched case-insensitively. A new csrftoken replaces the one we use.
    private fun storeCookies(connection: HttpURLConnection) {
        connection.headerFields.entries
            .filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .forEach { header ->
                val pair = header.substringBefore(";")
                val name = pair.substringBefore("=").trim()
                val value = pair.substringAfter("=", "").trim()
                if (name.isEmpty()) return@forEach

                if (name == "csrftoken") {
                    if (value.isNotEmpty()) csrfToken = value
                } else if (value.isEmpty()) {
                    cookies.remove(name) //server deleted it
                } else {
                    cookies[name] = value
                }
            }
    }

    private fun cookieHeader(): String =
        (synchronized(cookies) { cookies.entries.map { it.key + "=" + it.value } } +
                ("csrftoken=" + (csrfToken ?: "")))
            .joinToString("; ")

    private fun randomHex(length: Int): String {
        val random = SecureRandom()
        val chars = "0123456789abcdef"
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    //https://6xq.net/pandora-apidoc/rest/authentication/#user-login
    private suspend fun userLogin() {
        val result = callApi(
            "/api/v1/auth/login",
            mapOf(
                "username" to options.pandoraUsername,
                "password" to options.pandoraPassword,
                "existingAuthToken" to null,
                "keepLoggedIn" to true
            ),
            requiresAuth = false
        )

        authToken = result.getString("authToken")
    }

    //Re-authenticates after the server rejected our auth token (HTTP 401). Only the first of
    //several concurrent failing callers actually logs in again.
    private suspend fun relogin(rejectedToken: String?) {
        loginMutex.withLock {
            if (authToken != rejectedToken) return //someone else already refreshed it
            authToken = null
            cookies.clear()
            fetchCsrfToken()
            userLogin()
        }
    }

    private class ApiResponse(val status: Int, val text: String)

    //Errors come back as a non-2xx status with {"errorCode", "errorString", "message"}, e.g.
    //400 INVALID_REQUEST "Both stationCode and pandoraId are missing ..." or
    //1028 COUNTRY_CODE_INVALID when the request comes from a non-US address.
    private suspend fun callApi(
        endpoint: String,
        data: Map<String, Any?>,
        requiresAuth: Boolean = true
    ): JSONObject {
        val tokenUsed = authToken

        var response = sendRequest(endpoint, data, requiresAuth)
        if (response.status == 401 && requiresAuth) {
            relogin(tokenUsed)
            response = sendRequest(endpoint, data, requiresAuth)
        }

        if (response.status !in 200..299) {
            val error = try { JSONObject(response.text) } catch (e: Exception) { JSONObject() }
            throw Exception(
                "Pandora API error " + response.status + " on '" + endpoint + "' " +
                        error.optString("errorCode") + " (" + error.optString("errorString") + "): " +
                        error.optString("message") +
                        //raw body only when it wasn't the documented JSON error, defensively truncated
                        (if (error.length() == 0) " [response " + response.text.take(300) + "]" else "")
            )
        }

        return if (response.text.isBlank()) JSONObject() else JSONObject(response.text)
    }

    private suspend fun sendRequest(
        endpoint: String,
        data: Map<String, Any?>,
        requiresAuth: Boolean
    ): ApiResponse = withContext(Dispatchers.IO) {
        val body = JSONObject()
        //org.json on Android stores a Kotlin List as-is and would serialise it as the STRING
        //"[AL, AR]", so collections have to be converted to real JSON arrays first
        data.forEach { (key, value) ->
            body.put(
                key,
                when (value) {
                    null -> JSONObject.NULL
                    is Collection<*> -> JSONArray(value)
                    else -> value
                }
            )
        }

        val connection = openConnection("https://$apiHost$endpoint", "POST")
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json;charset=utf-8")
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Origin", "https://$apiHost")
            connection.setRequestProperty("Referer", "https://$apiHost/")
            //the login request carries no X-AuthToken at all (only the csrf header + cookie)
            if (requiresAuth) connection.setRequestProperty("X-AuthToken", authToken ?: "")
            //header and cookie must carry the same csrf token
            connection.setRequestProperty("X-CsrfToken", csrfToken ?: "")
            connection.setRequestProperty("Cookie", cookieHeader())

            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            storeCookies(connection) //login hands out the session cookies here
            //Pandora returns error details on the error stream, not the input stream
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream
            ApiResponse(code, stream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}