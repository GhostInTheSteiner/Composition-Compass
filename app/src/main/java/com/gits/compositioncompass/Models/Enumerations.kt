enum class Fields(val viewName: String) {
    Artist("artist"),
    Track("track"),
    Genre("genre"),
    Album("album"),
    SearchQuery("searchQuery"),
    File("file"),
    Info("info"),
    Mode("mode"),
    Source("source"),
    Error("error")
}

enum class QueryMode() {
    SimilarTracks,
    SimilarAlbums,
    SimilarArtists,
    Specified
}

enum class DownloadFolder(val folderName: String) {
    Stations("Stations"),
    Artists("Artists"),
    Albums("Albums"),
}

enum class QuerySource() {
    Spotify,
    LastFM,
    YouTube,
    File
}

enum class MetaItem {
    Track,
    Artist,
    Album
}

enum class LastFM_EmptyResponses(val json: String) {
    ArtistTopTracks("{\"toptracks\": {\"track\": []}}")
}