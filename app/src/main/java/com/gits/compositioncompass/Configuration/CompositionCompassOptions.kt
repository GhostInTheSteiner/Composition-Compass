package com.gits.compositioncompass.Configuration

import android.app.Activity
import android.net.Uri
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.SafStorage

class CompositionCompassOptions {

    //private fields
    private var activity: Activity
    private var storage: SafStorage
    private var options: MutableMap<String, Object>
    private val configFileName = "config.ini"

    //public fields
    var __requiredFields: List<String>
    var __requiredFieldsSet: Boolean
    var __exceptionsList: List<String> = listOf()

    //constants
//    var tempDirectory: String = "!temporary"
//    var resourcesDirectory: String = "!resources"
    var automatedDirectory: String = "!automated"
    var stationsDirectory: String = "Stations"
    var recyclebinDirectory: String = "Recycle Bin"
    var favoritesDirectory: String = "Favorites"
    var moreInterestingDirectory: String = "More Interesting"
    var lessInterestingDirectory: String = "Less Interesting"

//    lateinit var tempDirectoryPath: String
//    lateinit var resourcesDirectoryPath: String
    lateinit var automatedDirectoryPath: String
    lateinit var stationsDirectoryPath: String
    lateinit var recyclebinDirectoryPath: String
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

    var resultsLikedArtists: Int
        get() = options[::resultsLikedArtists.name] as Int
        set(value) { options[::resultsLikedArtists.name] = value as Object }

    var playerVolumeLevel: Int
        get() = options[::playerVolumeLevel.name] as Int
        set(value) { options[::playerVolumeLevel.name] = value as Object }

    var lastfmApiKey: String
        get() = options[::lastfmApiKey.name] as String
        set(value) { options[::lastfmApiKey.name] = value as Object }

    //Pandora's own account credentials (the user's login)
    var pandoraUsername: String
        get() = options[::pandoraUsername.name] as String
        set(value) { options[::pandoraUsername.name] = value as Object }

    var pandoraPassword: String
        get() = options[::pandoraPassword.name] as String
        set(value) { options[::pandoraPassword.name] = value as Object }

    //"partner" credentials identifying the client to Pandora's API, the same way the
    //official apps do - not tied to any one Pandora account. See pydora's docs
    //(https://github.com/mcrute/pydora) for how to source a working set.
    var pandoraPartnerUsername: String
        get() = options[::pandoraPartnerUsername.name] as String
        set(value) { options[::pandoraPartnerUsername.name] = value as Object }

    var pandoraPartnerPassword: String
        get() = options[::pandoraPartnerPassword.name] as String
        set(value) { options[::pandoraPartnerPassword.name] = value as Object }

    var pandoraDeviceModel: String
        get() = options[::pandoraDeviceModel.name] as String
        set(value) { options[::pandoraDeviceModel.name] = value as Object }

    var pandoraDecryptionKey: String
        get() = options[::pandoraDecryptionKey.name] as String
        set(value) { options[::pandoraDecryptionKey.name] = value as Object }

    var pandoraEncryptionKey: String
        get() = options[::pandoraEncryptionKey.name] as String
        set(value) { options[::pandoraEncryptionKey.name] = value as Object }

    var commaReplacer: String
        get() = options[::commaReplacer.name] as String
        set(value) { options[::commaReplacer.name] = value as Object }




    constructor(activity: Activity, storage: SafStorage) {
        this.activity = activity
        this.storage = storage
        this.options = loadDefaults()

        setDirectories()

        if (!storage.exists("", configFileName)) {
            save()
        }

        load()

        // __requiredFields = listOf(::spotifyClientId.name, ::spotifyClientSecret.name, ::lastfmApiKey.name)
        __requiredFields = listOf()
        __requiredFieldsSet = __requiredFields.map { options[it] }.all { (it as String).length > 0 }
    }

    //content:// Uri for config.ini, so it can be opened directly in an external file
    //manager/text editor for hand-editing (see MainActivity.openFile())
    val configFileUri: Uri? get() = storage.getOrCreateFile("", configFileName)?.uri

    private fun setDirectories() {
//        tempDirectoryPath = "$rootDirectoryPath/$tempDirectory"
//        resourcesDirectoryPath = "$rootDirectoryPath/$resourcesDirectory"
        automatedDirectoryPath = "$rootDirectoryPath/$automatedDirectory"
        stationsDirectoryPath = "$rootDirectoryPath/$stationsDirectory"
        recyclebinDirectoryPath = "$automatedDirectoryPath/$recyclebinDirectory"
        favoritesDirectoryPath = "$automatedDirectoryPath/$favoritesDirectory"
        moreInterestingDirectoryPath = "$favoritesDirectoryPath/$moreInterestingDirectory"
        lessInterestingDirectoryPath = "$favoritesDirectoryPath/$lessInterestingDirectory"
    }

    private fun loadDefaults(): MutableMap<String, Object> {
        val options_ = mutableMapOf<String, Object>()

        //root of the SAF tree the user picked - config.ini lives here too, and every
        //other path is relative to this (see SafStorage)
        val rootDirectory_ = ""

        options_[::rootDirectoryPath.name] = rootDirectory_ as Object

        options_[::appName.name] = "Composition Compass" as Object
        options_[::packageName.name] = activity.packageName as Object
        options_[::logName.name] = "error.log" as Object

        options_[::spotifyClientSecret.name] = "" as Object
        options_[::spotifyClientId.name] = "" as Object
        options_[::lastfmApiKey.name] = "" as Object

        options_[::pandoraUsername.name] = "" as Object
        options_[::pandoraPassword.name] = "" as Object
        options_[::pandoraPartnerUsername.name] = "" as Object
        options_[::pandoraPartnerPassword.name] = "" as Object
        options_[::pandoraDeviceModel.name] = "" as Object
        options_[::pandoraDecryptionKey.name] = "" as Object
        options_[::pandoraEncryptionKey.name] = "" as Object

        options_[::samplesSimilarArtists.name] = 1000 as Object
        options_[::samplesSimilarAlbums.name] = 1000 as Object

        options_[::resultsSimilarArtists.name] = 5 as Object
        options_[::resultsSimilarAlbums.name] = 5 as Object
        options_[::resultsLikedArtists.name] = 5 as Object

        options_[::playerVolumeLevel.name] = 4 as Object

        options_[::maxParallelDownloads.name] = 5 as Object
        options_[::commaReplacer.name] = "<comma>" as Object
        options_[::exceptions.name] = "live|remix| mix|add_other_exceptions_here" as Object

        return options_
    }

    private fun load() {
        storage.readLines("", configFileName).forEach {
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

        listOf(
            automatedDirectoryPath,
            recyclebinDirectoryPath, favoritesDirectoryPath,
            moreInterestingDirectoryPath, lessInterestingDirectoryPath
        )
        .forEach { storage.getOrCreateDirectory(it) }
    }

    private fun save() {
        val content = options.entries.joinToString("") { "${it.key}=${it.value}${System.lineSeparator()}" }
        storage.writeText("", configFileName, content)
    }
}
