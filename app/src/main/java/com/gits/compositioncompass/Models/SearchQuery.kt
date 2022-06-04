package com.gits.compositioncompass.Models

class SearchQuery {
    val track:String
    val artists: List<String>
    val album: String
    val genre: String
    val url: String

    constructor(track: String, artists: List<String> = listOf(), album: String = "", genre: String = "") {
        this.track = track
        this.artists = artists
        this.album = album
        this.genre = genre
        this.url = ""

        if (listOf(track, artists.joinToString(""), album, genre).all { it.length == 0 })
            throw Exception("At least one property needs to be initialized!")
    }

    constructor(url: String) {
        this.track = ""
        this.artists = listOf()
        this.album = ""
        this.genre = ""
        this.url = url

        if (url.length == 0)
            throw Exception("URL needs to be initialized!")
    }

    override fun toString(): String {
        return listOf(track, artists.joinToString(" "), album, genre, url).filter { it.length > 0 }.joinToString(", ")
    }
}