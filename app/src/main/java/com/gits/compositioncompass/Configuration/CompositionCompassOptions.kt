package com.gits.compositioncompass.Configuration

import android.app.Activity
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class CompositionCompassOptions {

    //private fields
    private var activity: Activity
    private var configFile: File
    private var options: MutableMap<String, Object>

    //public fields
    var __requiredFields: List<String>
    var __requiredFieldsSet: Boolean
    var __filePath: String
    var __exceptionsList: List<String> = listOf()

    //constants
    var tempDirectory: String = "!temporary"
    var automatedDirectory: String = "!automated"
    var resourcesDirectory: String = "!resources"
    var recyclebinDirectory: String = "Recycle Bin"
    var favoritesBaseDirectory: String = "Favorites"
    var moreInterestingDirectory: String = "More Interesting"
    var lessInterestingDirectory: String = "Less Interesting"

    lateinit var tempDirectoryPath: String
    lateinit var automatedDirectoryPath: String
    lateinit var resourcesDirectoryPath: String
    lateinit var recyclebinDirectoryPath: String
    lateinit var favoritesBaseDirectoryPath: String
    lateinit var favoritesDirectoryPath: String
    lateinit var moreInterestingDirectoryPath: String
    lateinit var lessInterestingDirectoryPath: String




    //user configurable fields
    var rootDirectoryPath: String
        get() = options[::rootDirectoryPath.name] as String
        set(value) { options[::rootDirectoryPath.name] = value as Object }

    var spotifyClientId: String
        get() = options[::spotifyClientId.name] as String
        set(value) { options[::spotifyClientId.name] = value as Object }

    var spotifyClientSecret: String
        get() = options[::spotifyClientSecret.name] as String
        set(value) { options[::spotifyClientSecret.name] = value as Object }

    var appName: String
        get() = options[::appName.name] as String
        set(value) { options[::appName.name] = value as Object }

    var packageName: String
        get() = options[::packageName.name] as String
        set(value) { options[::packageName.name] = value as Object }

    var logName: String
        get() = options[::logName.name] as String
        set(value) { options[::logName.name] = value as Object }

    var maxParallelDownloads: Int
        get() = options[::maxParallelDownloads.name] as Int
        set(value) { options[::maxParallelDownloads.name] = value as Object }

    var exceptions: String
        get() = options[::exceptions.name] as String
        set(value) { options[::exceptions.name] = value as Object }

    var samplesSimilarArtists: Int
        get() = options[::samplesSimilarArtists.name] as Int
        set(value) { options[::samplesSimilarArtists.name] = value as Object }

    var samplesSimilarAlbums: Int
        get() = options[::samplesSimilarAlbums.name] as Int
        set(value) { options[::samplesSimilarAlbums.name] = value as Object }

    var resultsSimilarArtists: Int
        get() = options[::resultsSimilarArtists.name] as Int
        set(value) { options[::resultsSimilarArtists.name] = value as Object }

    var resultsSimilarAlbums: Int
        get() = options[::resultsSimilarAlbums.name] as Int
        set(value) { options[::resultsSimilarAlbums.name] = value as Object }

    var resultsSpecifiedFavorites: Int
        get() = options[::resultsSpecifiedFavorites.name] as Int
        set(value) { options[::resultsSpecifiedFavorites.name] = value as Object }

    var lastfmApiKey: String
        get() = options[::lastfmApiKey.name] as String
        set(value) { options[::lastfmApiKey.name] = value as Object }

    var commaReplacer: String
        get() = options[::commaReplacer.name] as String
        set(value) { options[::commaReplacer.name] = value as Object }




    constructor(filePath: String, activity: Activity) {
        this.configFile = File(filePath)
        this.activity = activity
        this.options = loadDefaults()

        setDirectories()

        File(configFile.parent).mkdirs()

        if (!configFile.exists()) {
            configFile.createNewFile()
            save()
        }

        load()

        __filePath = filePath
        __requiredFields = listOf(::spotifyClientId.name, ::spotifyClientSecret.name, ::lastfmApiKey.name)
        __requiredFieldsSet = __requiredFields.map { options[it] }.all { (it as String).length > 0 }
    }

    private fun setDirectories() {
        tempDirectoryPath = "$rootDirectoryPath/$tempDirectory"
        automatedDirectoryPath = "$rootDirectoryPath/$automatedDirectory"
        resourcesDirectoryPath = "$rootDirectoryPath/$resourcesDirectory"
        recyclebinDirectoryPath = "$automatedDirectoryPath/$recyclebinDirectory"
        favoritesBaseDirectoryPath = "$automatedDirectoryPath/$favoritesBaseDirectory"
        favoritesDirectoryPath = "$favoritesBaseDirectoryPath (${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))})"
        moreInterestingDirectoryPath = "$favoritesDirectoryPath/$moreInterestingDirectory"
        lessInterestingDirectoryPath = "$favoritesDirectoryPath/$lessInterestingDirectory"
    }

    private fun loadDefaults(): MutableMap<String, Object> {
        val options_ = mutableMapOf<String, Object>()
        val rootDirectory_ = configFile.parent

        options_[::rootDirectoryPath.name] = rootDirectory_ as Object

        options_[::appName.name] = "Composition Compass" as Object
        options_[::packageName.name] = activity.packageName as Object
        options_[::logName.name] = "error.log" as Object

        options_[::spotifyClientSecret.name] = "" as Object
        options_[::spotifyClientId.name] = "" as Object
        options_[::lastfmApiKey.name] = "" as Object

        options_[::samplesSimilarArtists.name] = 1000 as Object
        options_[::samplesSimilarAlbums.name] = 1000 as Object

        options_[::resultsSimilarArtists.name] = 5 as Object
        options_[::resultsSimilarAlbums.name] = 5 as Object
        options_[::resultsSpecifiedFavorites.name] = 5 as Object

        options_[::maxParallelDownloads.name] = 5 as Object
        options_[::commaReplacer.name] = "<comma>" as Object
        options_[::exceptions.name] = "live|remix| mix|add_other_exceptions_here" as Object

        return options_
    }

    private fun load() {
        configFile.forEachLine {
            if (it.contains("=")) {
                val splitted = it.split("=")

                val key = splitted.first()
                val value = splitted.drop(1).joinToString("=")

                val number = value.toIntOrNull()

                if (key.equals("exceptions"))
                    __exceptionsList = value.split("|")

                if (number == null)
                    options[key] = value as Object
                else
                    options[key] = number as Object
            }
        }

        listOf(automatedDirectoryPath, tempDirectoryPath, resourcesDirectoryPath)
            .forEach { File(it).mkdirs() }
    }

    private fun save() {
        val tmp = File(configFile.absolutePath + ".tmp")

        //create backup
        configFile.renameTo(tmp)
        configFile.createNewFile()

        options.forEach {
            configFile.appendText("${it.key}=${it.value}${System.lineSeparator()}")
        }

        //remove backup
        tmp.delete()
    }
}
