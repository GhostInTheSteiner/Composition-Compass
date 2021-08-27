enum class Fields(val viewName: String) {
    Artist("artist"),
    Track("track"),
    Genre("genre"),
    Info("info"),
    Mode("mode"),
    Source("source"),
    SearchQuery("searchQuery"),
    Error("error")
}

enum class DownloadMode() {
    SimilarTracks,
    SimilarAlbums,
    SimilarArtists,
    SpecificTracks,
    SpecificAlbums,
    SpecificArtists,
}

enum class QuerySource() {
    Spotify,
    LastFM,
    YouTube,
    File
}