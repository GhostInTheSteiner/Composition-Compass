import java.io.File
import java.io.ObjectInput

class CompositionCompassOptions {
    private var configFile: File
    private var options: MutableMap<String, Object>

    var __newFile: Boolean
    var __location: String

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

    constructor(filePath: String) {
        configFile = File(filePath)
        options = loadDefaults()

        __newFile = false
        __location = filePath

        if (!configFile.exists()) {
            configFile.createNewFile()
            save()

            __newFile = true
        }

        load()
    }

    private fun loadDefaults(): MutableMap<String, Object> {
        val options_ = mutableMapOf<String, Object>()
        val rootDirectory_ = configFile.parent

        options_[::rootDirectory.name] = rootDirectory_ as Object
        options_[::tempDirectory.name] = (rootDirectory_ + "/.temp") as Object
        options_[::automatedDirectory.name] = (rootDirectory_ + "/.Automated") as Object

        options_[::spotifyClientSecret.name] = "<Sono me, dare no me?>" as Object
        options_[::spotifyClientId.name] = "<Sono me, dare no me?>" as Object
        options_[::lastfmApiKey.name] = "<Sono me, dare no me?>" as Object

        options_[::samplesSimilarArtists.name] = 1000 as Object
        options_[::samplesSimilarAlbums.name] = 1000 as Object

        options_[::resultsSimilarArtists.name] = 5 as Object
        options_[::resultsSimilarAlbums.name] = 5 as Object

        options_[::maxParallelDownloads.name] = 100 as Object
        options_[::commaReplacer.name] = "<comma>" as Object
        options_[::exceptions.name] = "<insert_exceptions_here>" as Object

        return options_
    }

    private fun load() {
        configFile.forEachLine {
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
