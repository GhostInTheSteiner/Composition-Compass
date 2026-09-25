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
import org.json.JSONObject
import toList
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.TorManager
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

//Talks to Pandora's undocumented JSON-RPC "tuner" API, the same one used by the
//official apps and reimplemented by open-source clients like pianobar and pydora
//(https://github.com/mcrute/pydora). Station creation + station.getPlaylist stands
//in for Spotify/LastFM's "similar tracks" recommendation endpoints.
//
//Requires the user to supply their own Pandora account credentials AND a set of
//"partner" credentials (partner username/password, device model, encryption key,
//decryption key) via CompositionCompassOptions. These identify the client to
//Pandora's API the same way the official apps do; they are not tied to any one
//user account and are not shipped with this project - see pydora's documentation
//for how to source a working set for personal use.
class PandoraQuery : IStreamingServiceQuery, Query {

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

    private var mode: QueryMode
    private val cryptor: PandoraCryptor

    //partner/session state - populated by prepare(). Volatile because prepare() can be
    //entered from several threads (autocomplete + download), and callApi reads these.
    @Volatile private var partnerAuthToken: String? = null
    @Volatile private var partnerId: String? = null
    @Volatile private var userAuthToken: String? = null
    @Volatile private var userId: String? = null
    @Volatile private var serverSyncTime: Long = 0
    @Volatile private var startTime: Long = 0

    //MainActivity fires prepare() on EVERY keystroke in the autocomplete fields, and again
    //from download(), all on separate coroutines. Without this lock two logins interleave:
    //login A finishes and sets userAuthToken, then login B's userLogin goes out carrying
    //userAuthToken instead of partnerAuthToken -> Pandora error 9 (parameter missing).
    private val loginMutex = Mutex()

    //shared seed station that accumulates all addArtist / addTrack / addAlbum / addGenre calls
    private var seedStationToken: String? = null

    //MainActivity.download() calls addArtist/addTrack/addAlbum/addGenre from separate
    //concurrent coroutines (one launch{} per field). addSeed()'s "create the station if
    //none exists yet, else add to it" logic is a check-then-act on seedStationToken -
    //without this lock, two concurrent seeds can both see it as null, both call
    //createStation(), and whichever assignment lands last silently wins, orphaning the
    //other seed's station (it's created, but never queried again).
    //
    //Also guards the throwaway single-purpose stations created by fetchTopTracks() and
    //sampleAlbumFromArtistStation(). In Specified mode with both an artist and an album
    //filled in, MainActivity launches those two fields as separate coroutines, so both
    //helpers can call station.createStation/station.getPlaylist within moments of each
    //other. Pandora's undocumented API returned a generic "code 0" error under that
    //overlap (station.getPlaylist right after station.createStation) - serializing every
    //station lifecycle behind one lock avoids putting two of them in flight at once.
    private val seedMutex = Mutex()

    //Pandora returns roughly 4 tracks per station.getPlaylist call. Sampling a
    //station is done by calling it repeatedly, but we cap the number of calls
    //regardless of options.samplesSimilar*, since those defaults (in the
    //thousands) are tuned for Spotify/LastFM's much higher-throughput endpoints
    //and would otherwise hammer Pandora's API far more than is reasonable.
    private val maxPlaylistCalls = 40
    private val tracksPerPlaylistCall = 4

    //Cap on playlist calls when sampling a throwaway single-artist station for
    //fetchTopTracks() (see addArtist). Kept low: this runs once per addArtist() call
    //in Specified/SpecifiedMoreInteresting mode, not once per download.
    private val topTracksSampleCalls = 8

    //Same idea, but for fetchAlbum()'s station sampling (see addAlbum). Higher than
    //topTracksSampleCalls: a full album is a much smaller slice of what an artist
    //station plays than "any track by this artist" is, so it needs more samples to
    //have a decent chance of surfacing the whole album.
    private val albumTracksSampleCalls = 20

    private val apiHost = "tuner.pandora.com/services/json/"
    private val apiVersion = "5"

    //Tor circuits are slow; be generous, but never hang forever.
    private val connectTimeoutMs = 60_000
    private val readTimeoutMs = 60_000

    constructor(options: CompositionCompassOptions, picker: ItemPicker) : super(options, picker) {
        this.mode = QueryMode.SimilarTracks
        this.options = options
        this.cryptor = PandoraCryptor(
            options.pandoraDecryptionKey,
            options.pandoraEncryptionKey
        )
    }

    override fun changeMode(mode: QueryMode) {
        this.mode = mode
    }

    override fun clear() {
        addedGenres.clear()
        addedArtists.clear()
        addedTracks.clear()
        addedAlbums.clear()
        seedStationToken = null
    }

    //needs to be called before any other functions!
    //Safe to call concurrently: only one caller performs the login, the rest wait and
    //then see the finished session.
    override suspend fun prepare() {
        if (userAuthToken != null) return //fast path, no lock needed

        loginMutex.withLock {
            if (userAuthToken != null) return //another caller finished while we waited

            try {
                partnerLogin()
                userLogin()
            } catch (e: Exception) {
                //don't leave a half-initialised session behind: the next prepare()
                //must start again from a clean partnerLogin
                partnerAuthToken = null
                partnerId = null
                userAuthToken = null
                userId = null
                throw e
            }
        }
    }

    // ─── Search ─────────────────────────────────────────────────────────────

    override suspend fun searchArtist(name: String, completeData: Boolean): List<ArtistItem> {
        if (name.isEmpty()) return listOf()

        val resp = searchPandora(name)
        val artists = resp.optJSONArray("artists")?.toList<JSONObject>() ?: listOf()

        return artists.map {
            ArtistItem(
                id = it.getString("musicToken"),
                name = it.getString("artistName"),
                popularity = if (it.optBoolean("likelyMatch")) 50 else 100
            )
        }
    }

    override suspend fun searchTrack(name: String, artist: String, album: String): List<TrackItem> {
        val searchText = listOf(name, artist).filter { it.isNotEmpty() }.joinToString(" ")
        if (searchText.isEmpty()) return listOf()

        val resp = searchPandora(searchText)
        val songs = resp.optJSONArray("songs")?.toList<JSONObject>() ?: listOf()

        return songs.map {
            TrackItem(
                id = it.getString("musicToken"),
                name = it.getString("songName"),
                artists = listOf(ArtistItem(id = "", name = it.getString("artistName"))),
                popularity = it.optInt("score", 0)
            )
        }
    }

    //Pandora's music.search endpoint only returns songs/artists/genre stations - there
    //is no native "album" entity to search for. We approximate an album as the set of
    //songs matched by a combined title+artist search, same as addAlbum() below.
    override suspend fun searchAlbum(name: String, artist: String): List<AlbumItem> =
        searchAlbumApproximate(name, artist)

    override suspend fun searchGenre(name: String, artist: String): List<String> {
        val resp = callApi("station.getGenreStations", emptyMap())
        val categories = resp.optJSONArray("categories")?.toList<JSONObject>() ?: listOf()

        return categories
            .flatMap { it.optJSONArray("stations")?.toList<JSONObject>() ?: listOf() }
            .map { it.getString("stationName") }
            .filter { name.isEmpty() || it.contains(name, ignoreCase = true) }
    }

    // ─── Add ────────────────────────────────────────────────────────────────

    override suspend fun addArtist(name: String): Boolean {
        val artist = searchArtist(name).firstOrNull() ?: return false
        addSeed(artist.id)

        //Query.getSpecified()'s artist-only branch reads artist.topTracks, and so does
        //SpecifiedMoreInteresting ("Liked Artists") indirectly, since it calls
        //getSpecified() after adding each artist. Every other mode only needs the seed
        //added above, so skip the extra station + sampling there - it would otherwise
        //run on every addArtist() call for Similar Tracks/Artists/Albums too.
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

    //Pandora has no "top tracks" endpoint (unlike Spotify/Last.fm), so this approximates
    //one the same way getSimilarArtists() approximates similar-artist tracks elsewhere in
    //this file: seed a station with just this artist and sample what it actually plays.
    //An artist station isn't 100% the seed artist - Pandora mixes in similar artists too -
    //so results are filtered down to tracks actually credited to this artist.
    //
    //The station is deleted afterwards. Unlike seedStationToken (which accumulates the
    //user's real seeds for the rest of this query and is meant to be sampled from), this
    //is a one-off lookup station that shouldn't linger in the user's Pandora account.
    private suspend fun fetchTopTracks(artist: ArtistItem): List<TrackItem> = seedMutex.withLock {
        val stationToken = try {
            createStation(artist.id, "artist")
        } catch (e: Exception) {
            return@withLock listOf() //no top tracks is better than failing the whole addArtist() call
        }

        try {
            val tracks = mutableListOf<TrackItem>()
            var calls = 0

            while (tracks.size < resultsSimilarArtists_Tracks && calls < topTracksSampleCalls) {
                tracks += getPlaylist(stationToken)
                    .map { playlistItemToTrack(it) }
                    .filter { it.artists.any { a -> a.name.equals(artist.name, ignoreCase = true) } }
                calls++
            }

            filterExceptions(tracks).distinctBy { it.name }.take(resultsSimilarArtists_Tracks)
        } finally {
            //Best-effort cleanup; a failed delete shouldn't fail the download.
            runCatching { callApi("station.deleteStation", mapOf("stationToken" to stationToken)) }
        }
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
        val album = fetchAlbum(name, artist) ?: return false
        album.tracks
            .distinctBy { it.id }
            .filter { it.id.isNotEmpty() }
            .forEach { addSeed(it.id) }
        addedAlbums.add(album)
        return true
    }

    //Pandora has no per-album lookup (unlike Spotify/Last.fm), so this combines two
    //approximations and merges their results, the same way fetchTopTracks() fills in
    //for the missing "top tracks" endpoint:
    // 1. searchAlbumApproximate(): a music.search for "<album> <artist>". Fast, but
    //    Pandora ranks by text relevance to the search string, not "every track on
    //    this album" - it can miss legitimate tracks, especially on longer albums,
    //    or surface an unrelated same-named track instead.
    // 2. sampleAlbumFromArtistStation(): seeds a throwaway single-artist station (the
    //    same technique fetchTopTracks() uses) and samples its playlist, keeping only
    //    tracks whose reported albumName matches. This can surface tracks the text
    //    search missed, though it depends on Pandora actually rotating that album.
    //Neither is a real album listing, so completeness still isn't guaranteed - see the
    //README's "Limitations" section.
    private suspend fun fetchAlbum(name: String, artist: String): AlbumItem? {
        val searched = searchAlbumApproximate(name, artist).firstOrNull()
        val sampled = sampleAlbumFromArtistStation(name, artist)

        //dedupe by title: searchAlbumApproximate's music.search tokens and the sampled
        //station's trackTokens live in different Pandora token namespaces, so the same
        //song can carry two different ids - deduping by id would let it through twice
        //and download it twice.
        val tracks = ((searched?.tracks ?: listOf()) + sampled)
            .distinctBy { it.name.trim().lowercase() }

        if (tracks.isEmpty()) return null

        return AlbumItem(
            id = searched?.id ?: UUID.randomUUID().toString(),
            name = name,
            tracks = tracks,
            artists = listOf(ArtistItem(id = "", name = artist)),
            popularity = 0
        )
    }

    private suspend fun sampleAlbumFromArtistStation(albumName: String, artistName: String): List<TrackItem> {
        val artistItem = searchArtist(artistName).firstOrNull() ?: return listOf()

        return seedMutex.withLock {
            val stationToken = try {
                createStation(artistItem.id, "artist")
            } catch (e: Exception) {
                return@withLock listOf() //fall back to whatever searchAlbumApproximate() found
            }

            try {
                val tracks = mutableListOf<TrackItem>()
                var calls = 0

                while (calls < albumTracksSampleCalls) {
                    tracks += getPlaylist(stationToken)
                        .map { playlistItemToTrack(it) }
                        .filter { it.album?.name?.equals(albumName, ignoreCase = true) == true }
                    calls++
                }

                filterExceptions(tracks).distinctBy { it.name.trim().lowercase() }
            } finally {
                //Best-effort cleanup; a failed delete shouldn't fail the download.
                runCatching { callApi("station.deleteStation", mapOf("stationToken" to stationToken)) }
            }
        }
    }

    override suspend fun addGenre(name: String): Boolean {
        val genreToken = findGenreMusicToken(name) ?: return false
        addSeed(genreToken)
        addedGenres.add(name)
        return true
    }

    // ─── Similarity ─────────────────────────────────────────────────────────

    override suspend fun getSimilarTracks(): List<TargetDirectory> {
        val stationToken = createSeedStation()

        val tracks = (1..10)
            .flatMap { getPlaylist(stationToken) }
            .let { filterExceptions(it.map { item -> playlistItemToTrack(item) }) }

        val subFolderName = getSubFolder_Station()

        // TODO: Target path set here!

        val path = getPath(DownloadFolder.Stations, subFolderName)
        val searchQueries = tracks.map { SearchQuery(it.name, it.artists.map { a -> a.name }) }

        return listOf(TargetDirectory(path, searchQueries))
    }
    override suspend fun getSimilarAlbums(): List<TargetDirectory> {
        val stationToken = createSeedStation()
        val sampleSize = options.samplesSimilarAlbums.coerceAtMost(maxPlaylistCalls * tracksPerPlaylistCall)
        val tracks = filterExceptions(sampleStation(stationToken, sampleSize))

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
        val stationToken = createSeedStation()
        val sampleSize = options.samplesSimilarArtists.coerceAtMost(maxPlaylistCalls * tracksPerPlaylistCall)
        val tracks = filterExceptions(sampleStation(stationToken, sampleSize))

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

    private suspend fun partnerLogin() {
        if (startTime == 0L) startTime = System.currentTimeMillis() / 1000

        val result = callApi(
            "auth.partnerLogin",
            mapOf(
                "username" to options.pandoraPartnerUsername,
                "password" to options.pandoraPartnerPassword,
                "deviceModel" to options.pandoraDeviceModel,
                "version" to apiVersion
            ),
            encryptBody = false,
            requiresAuth = false
        )

        partnerAuthToken = result.getString("partnerAuthToken")
        partnerId = result.getString("partnerId")
        serverSyncTime = cryptor.decryptSyncTime(result.getString("syncTime"))
    }

    private suspend fun userLogin() {
        val result = callApi(
            "auth.userLogin",
            mapOf(
                "loginType" to "user",
                "username" to options.pandoraUsername,
                "password" to options.pandoraPassword,
                "includePandoraOneInfo" to true
            )
        )

        userAuthToken = result.getString("userAuthToken")
        userId = result.getString("userId")
    }

    private suspend fun searchPandora(text: String): JSONObject =
        callApi("music.search", mapOf("searchText" to text, "includeNearMatches" to true))

    private suspend fun createStation(musicToken: String, musicType: String): String {
        val result = callApi(
            "station.createStation",
            mapOf("musicToken" to musicToken, "musicType" to musicType)
        )
        return result.getString("stationToken")
    }

    //Accumulates seeds on a single shared station. If no station exists yet, the first
    //seed creates it; every subsequent seed is added via station.addMusic. Guarded by
    //seedMutex so concurrent callers (see field comment above) can't both create one.
    private suspend fun addSeed(musicToken: String) {
        if (musicToken.isEmpty()) return

        seedMutex.withLock {
            if (seedStationToken == null) {
                val musicType = when {
                    musicToken.startsWith("S") -> "song"
                    musicToken.startsWith("G") -> "song" //genre stations use "song" per docs
                    else -> "artist"
                }
                seedStationToken = createStation(musicToken, musicType)
            } else {
                callApi(
                    "station.addMusic",
                    mapOf(
                        "stationToken" to seedStationToken!!,
                        "musicToken" to musicToken
                    )
                )
            }
        }
    }

    private suspend fun findGenreMusicToken(name: String): String? {
        val resp = callApi("station.getGenreStations", emptyMap())
        val categories = resp.optJSONArray("categories")?.toList<JSONObject>() ?: listOf()
        return categories
            .flatMap { it.optJSONArray("stations")?.toList<JSONObject>() ?: listOf() }
            .firstOrNull {
                name.isEmpty() || it.getString("stationName").contains(name, ignoreCase = true)
            }
            ?.getString("stationToken") //stationToken IS the musicToken for genre stations
    }

    private suspend fun createSeedStation(): String {
        // The station was already created and populated dynamically during the
        // addArtist/addTrack/addAlbum/addGenre calls via addSeed().
        // If it's null here, it means no valid seeds were found or added.
        return seedStationToken ?: throw Exception("No valid seeds were added to create a station! Required field 'artist' or 'track' not found.")
    }

    private suspend fun getPlaylist(stationToken: String): List<JSONObject> {
        val result = callApi(
            "station.getPlaylist",
            mapOf(
                "stationToken" to stationToken,
                "includeTrackLength" to true,
                "xplatformAdCapable" to false
            )
        )

        val items = result.optJSONArray("items")?.toList<JSONObject>() ?: listOf()
        return items.filter { !it.has("adToken") } //drop ad breaks
    }

    //repeatedly pulls from a station's continuous playlist to build up a larger sample,
    //the Pandora equivalent of Spotify/LastFM's "get N recommendations" loop
    private suspend fun sampleStation(stationToken: String, minimumSize: Int): List<TrackItem> {
        val tracks = mutableListOf<TrackItem>()
        var calls = 0

        while (tracks.size < minimumSize && calls < maxPlaylistCalls) {
            tracks += getPlaylist(stationToken).map { playlistItemToTrack(it) }
            calls++
        }

        return tracks
    }

    private fun playlistItemToTrack(item: JSONObject): TrackItem {
        val artistName = item.optString("artistName")
        val albumName = item.optString("albumName")

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
            name = item.getString("songName"),
            artists = listOf(ArtistItem(id = "", name = artistName)),
            album = album
        )
    }

    private suspend fun searchAlbumApproximate(name: String, artist: String): List<AlbumItem> {
        val searchText = listOf(name, artist).filter { it.isNotEmpty() }.joinToString(" ")
        if (searchText.isEmpty()) return listOf()

        val resp = searchPandora(searchText)
        val songs = resp.optJSONArray("songs")?.toList<JSONObject>() ?: listOf()
        if (songs.isEmpty()) return listOf()

        val tracks = songs.map {
            TrackItem(
                id = it.getString("musicToken"),
                name = it.getString("songName"),
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

    //Pandora returns error details on the error stream, not the input stream
    private fun readResponseBody(connection: HttpURLConnection): String {
        val stream =
            if (connection.responseCode in 200..299) connection.inputStream
            else connection.errorStream ?: connection.inputStream

        return stream.bufferedReader().use { it.readText() }
    }

    //current, drifted server time - mirrors pydora's `APITransport.sync_time` property
    private fun currentSyncTime(): Long {
        val elapsed = (System.currentTimeMillis() / 1000) - startTime
        return serverSyncTime + elapsed
    }

    //builds, encrypts (if applicable), sends and parses a single JSON-RPC call.
    //Mirrors pydora's APITransport.__call__ / _build_data / _build_params.
    private suspend fun callApi(
        method: String,
        data: Map<String, Any>,
        encryptBody: Boolean = true,
        requiresAuth: Boolean = true
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
        data.forEach { (key, value) -> body.put(key, value) }

        val queryParams = mutableListOf("method" to method)

        if (requiresAuth) {
            body.put("syncTime", currentSyncTime())

            //auth.userLogin is the call that CREATES the user session, so it must always
            //authenticate with the partner token, never with a userAuthToken that may
            //already exist. (Pandora answers error 9 "parameter missing" otherwise.)
            val isUserLogin = method == "auth.userLogin"
            val userToken = if (isUserLogin) null else userAuthToken
            val partnerToken = partnerAuthToken

            val authToken = userToken ?: partnerToken
            if (userToken != null)
                body.put("userAuthToken", userToken)
            else if (partnerToken != null)
                body.put("partnerAuthToken", partnerToken)

            authToken?.let { queryParams += "auth_token" to it }
            partnerId?.let { queryParams += "partner_id" to it }
            if (!isUserLogin) userId?.let { queryParams += "user_id" to it }
        }

        val bodyString = if (encryptBody) cryptor.encrypt(body.toString()) else body.toString()

        val query = queryParams.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }

        //Tor is optional (checkbox in MainActivity). Read the flag once so the decision
        //can't change halfway through a request.
        val useTor = TorManager.enabled

        val proxy: Proxy =
            if (useTor) {
                //Fail closed: with Tor enabled, never contact Pandora's API without a
                //working Tor circuit - falling back to a direct connection would leak the
                //exact traffic this proxying exists to protect. awaitReady() only returns
                //true once TorManager has loaded GeoIP AND restricted exits to the US
                //(Pandora rejects non-US IPs with the generic code-12 error).
                if (!TorManager.awaitReady())
                    throw Exception(
                        "Tor is not available; refusing to contact Pandora directly." +
                                (TorManager.lastConfigError?.let { " Last Tor error: $it" } ?: "")
                    )

                //Read the port once, and re-check it: tor may have gone down since awaitReady().
                val port = TorManager.socksPort()
                if (port < 0)
                    throw Exception("Tor went down before the request could be sent; refusing to contact Pandora directly.")

                //Per-connection SOCKS proxy -> only this API goes through Tor. The proxy
                //address is a literal IP (no DNS involved). The *target* host
                //(tuner.pandora.com) is handed to tor's SOCKS5 proxy unresolved by the HTTP
                //stack and resolved at the exit node, so there is no local DNS leak.
                //yt-dlp downloads spawn a native process with their own sockets and stay direct.
                Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            } else {
                Proxy.NO_PROXY //user explicitly turned Tor off
            }

        val connection = URL("https://$apiHost?$query").openConnection(proxy) as HttpURLConnection

        val responseText = try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Content-Type", "text/plain")
            //Avoids the legacy okhttp stack reusing a stale pooled connection
            //("unexpected end of stream" / "Broken pipe" on the first request).
            connection.setRequestProperty("Connection", "close")
            connection.outputStream.use { it.write(bodyString.toByteArray(Charsets.UTF_8)) }

            readResponseBody(connection)
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(responseText)

        if (json.optString("stat") != "ok")
            throw Exception(
                "Pandora API error on '$method' " + json.optString("code") + ": " + json.optString("message") +
                        "\n[debug " + requestSummary(method, body, bodyString, encryptBody, requiresAuth) + "]" +
                        //raw response from Pandora, not our request - safe to log in full, no secrets in it.
                        //Truncated defensively in case of an unexpectedly large error body.
                        "\n[response " + responseText.take(1000) + "]"
            )

        json.optJSONObject("result") ?: JSONObject()
    }

    //DEBUG: describes the request that was just sent WITHOUT leaking any secrets
    //(no values, only key names, lengths and formats). Helps tell apart:
    // - empty username/password in the config  (usernameLen / passwordLen = 0)
    // - a missing token                        (keys lacks partnerAuthToken / userAuthToken)
    // - a broken encryption step               (cipher not lowercase hex, or length not a multiple of 16)
    private fun requestSummary(
        method: String,
        body: JSONObject,
        sent: String,
        encrypted: Boolean,
        requiresAuth: Boolean
    ): String {
        val keys = body.keys().asSequence().toList().sorted()
        val cipherOk = !encrypted || (sent.isNotEmpty() && sent.length % 16 == 0 && sent.all { it in '0'..'9' || it in 'a'..'f' })

        return "method=$method" +
                " encrypted=$encrypted" +
                " keys=$keys" +
                " plainLen=${body.toString().length}" +
                " sentLen=${sent.length}" +
                " cipherFormatOk=$cipherOk" +
                (if (method == "auth.userLogin")
                    " usernameLen=${body.optString("username").length} passwordLen=${body.optString("password").length}"
                else "") +
                (if (requiresAuth) " syncTime=${body.optLong("syncTime")} serverSyncTime=$serverSyncTime" else "")
    }
}