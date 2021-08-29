class SearchQuery(
    val track:String = "",
    val artists: List<String> = listOf(),
    val album: String = "",
    val genre: String = "",
    val playlist: String = "",
) {
    init {
        if (listOf(track, artists.joinToString(""), album, album, genre, playlist).all { it.length == 0 })
            throw Exception("At least one property needs to be initialized!")
    }

    override fun toString(): String {
        return listOf(track, artists.joinToString(" "), album, album, genre, playlist).joinToString(", ")
    }
}