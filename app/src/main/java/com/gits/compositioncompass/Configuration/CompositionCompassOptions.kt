package com.gits.compositioncompass.Configuration

import android.app.Activity
import android.app.Application
import java.io.File

class CompositionCompassOptions {
    private var activity: Activity
    private var configFile: File
    private var options: MutableMap<String, Object>

    var __requiredFields: List<String>
    var __requiredFieldsSet: Boolean
    var __filePath: String

    var spotifyClientId: String
        get() = options[::spotifyClientId.name] as String
        set(value) { options[::spotifyClientId.name] = value as Object }

    var spotifyClientSecret: String
        get() = options[::spotifyClientSecret.name] as String
        set(value) { options[::spotifyClientSecret.name] = value as Object }

    var rootDirectory: String
        get() = options[::rootDirectory.name] as String
        set(value) { options[::rootDirectory.name] = value as Object }

    var tempDirectory: String
        get() = options[::tempDirectory.name] as String
        set(value) { options[::tempDirectory.name] = value as Object }

    var automatedDirectory: String
        get() = options[::automatedDirectory.name] as String
        set(value) { options[::automatedDirectory.name] = value as Object }

    var resourcesDirectory: String
        get() = options[::resourcesDirectory.name] as String
        set(value) { options[::resourcesDirectory.name] = value as Object }

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

    var lastfmApiKey: String
        get() = options[::lastfmApiKey.name] as String
        set(value) { options[::lastfmApiKey.name] = value as Object }

    var commaReplacer: String
        get() = options[::commaReplacer.name] as String
        set(value) { options[::commaReplacer.name] = value as Object }

    var exceptionsList: List<String> = listOf()

    constructor(filePath: String, activity: Activity) {
        this.configFile = File(filePath)
        this.activity = activity
        this.options = loadDefaults()

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

    private fun loadDefaults(): MutableMap<String, Object> {
        val options_ = mutableMapOf<String, Object>()
        val rootDirectory_ = configFile.parent

        options_[::rootDirectory.name] = rootDirectory_ as Object
        options_[::tempDirectory.name] = "!temporary" as Object
        options_[::automatedDirectory.name] = "!automated" as Object
        options_[::resourcesDirectory.name] = "!resources" as Object

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

        options_[::maxParallelDownloads.name] = 100 as Object
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
                    exceptionsList = value.split("|")

                if (number == null)
                    options[key] = value as Object
                else
                    options[key] = number as Object
            }
        }
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
