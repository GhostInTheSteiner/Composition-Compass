enum class Fields(val viewName: String) {
    Artist("artist"),
    Track("track"),
    Genre("genre"),
    Info("info"),
    Mode("mode"),
    Source("source"),
    SearchQuery("searchQuery"),
    Error("error"),
    Album("album")
}

enum class QueryMode() {
    SimilarTracks,
    SimilarAlbums,
    SimilarArtists,
    Specified
}

enum class QuerySource() {
    Spotify,
    LastFM,
    YouTube,
    File
}