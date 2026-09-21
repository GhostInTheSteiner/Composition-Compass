package com.gits.compositioncompass

import Fields
import QueryMode
import QuerySource
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.setPadding
import com.gits.compositioncompass.Configuration.CompositionRoot
import com.gits.compositioncompass.Models.TargetDirectory
import com.gits.compositioncompass.Queries.IFileQuery
import com.gits.compositioncompass.Queries.IStreamingServiceQuery
import com.gits.compositioncompass.Queries.IYoutubeQuery
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Logger
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.PermissionManager
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.TorManager
import com.gits.compositioncompass.databinding.ActivityMainBinding
import com.gits.compositioncompass.ui.controls.InstantMultiAutoCompleteTextView
import com.gits.compositioncompass.ui.controls.SpinnerItem
import getItem
import hasUserContent
import kotlinx.coroutines.*
import registerEventHandler
import setSelection
import java.util.*
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var logger: Logger
    private lateinit var queryParameters: List<InstantMultiAutoCompleteTextView>
    private lateinit var info: TextView
    private lateinit var error: TextView
    private lateinit var genre: InstantMultiAutoCompleteTextView
    private lateinit var artist: InstantMultiAutoCompleteTextView
    private lateinit var track: InstantMultiAutoCompleteTextView
    private lateinit var album: InstantMultiAutoCompleteTextView
    private lateinit var searchQuery: InstantMultiAutoCompleteTextView
    private lateinit var file: InstantMultiAutoCompleteTextView
    private lateinit var favorites: InstantMultiAutoCompleteTextView
    private lateinit var mode: Spinner
    private lateinit var source: Spinner
    private lateinit var download: Button
    private lateinit var update: Button
    private lateinit var useTor: CheckBox

    private lateinit var preferencesReader: SharedPreferences
    private lateinit var preferencesWriter: SharedPreferences.Editor
    private lateinit var jobsDownload: List<Job>
    private lateinit var fieldViews: MutableMap<Fields, View>
    private var notificationChannelId: String? = null

    private lateinit var downloadingLabel: String
    private lateinit var downloadLabel: String

    private lateinit var composition: CompositionRoot
    private lateinit var binding: ActivityMainBinding

    private val permissionManager = PermissionManager(this)

    // Used for cancelling in-flight autocomplete queries to prevent stale data races
    private val autocompleteJobs = mutableMapOf<Int, Job>()
    // Re-entrancy guard for downloads
    private var isDownloading = false

    override fun onResume() {
        super.onResume()
        // Only initialize if we have permission AND haven't already initialized.
        // This handles the "return from Settings" case on API 30+.
        if (!::composition.isInitialized && permissionManager.hasStorageAccess()) {
            initializeApp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)

            val outerThis = this

            notificationChannelId = createNotificationChannel("composition-compass")

            if (!permissionManager.hasStorageAccess()) {
                permissionManager.requestStorageAccess(object : PermissionManager.Callback {
                    override fun onGranted() {
                        initializeApp()
                    }
                    override fun onDenied() {
                        Toast.makeText(
                            outerThis,
                            "Composition Compass needs a folder to store your library in - please pick one.",
                            Toast.LENGTH_LONG
                        ).show()
                        finishAffinity()
                    }
                })
            } else {
                initializeApp()
            }
        }
        catch (e: Exception) {
            val channelId = notificationChannelId ?: createNotificationChannel("composition-compass-error")
            val mBuilder: NotificationCompat.Builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("An exception occured ;(") // title
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(e.stackTraceToString()))
                .setContentText(
                    e.message + System.lineSeparator() + System.lineSeparator() +
                            e.stackTraceToString()) // body message
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            NotificationManagerCompat.from(this).notify(Random().nextInt(Int.MAX_VALUE), mBuilder.build())

            throw e
        }
    }

    private fun initializeApp() {
        // Idempotent: don't re-init if already done (prevents double-init
        // when both the permission callback and onResume() fire).
        if (::composition.isInitialized) return

        // Must be set before the first HttpURLConnection is used anywhere in the process.
        // The legacy okhttp stack behind HttpURLConnection reuses stale pooled connections,
        // which surfaces as "Broken pipe" / "unexpected end of stream" over Tor.
        System.setProperty("http.keepAlive", "false")

        composition = CompositionRoot.initialize(this)
        logger = composition.logger

        preferencesReader = composition.preferencesReader
        preferencesWriter = composition.preferencesWriter

        //Tor is optional (checkbox). The preference must be read BEFORE deciding to start it.
        //When enabled, start the embedded Tor instance in the background. It only proxies
        //Pandora's tuner API (see PandoraQuery.callApi); yt-dlp stays direct.
        //Pandora requests block on TorManager.awaitReady(), so bootstrapping
        //in parallel with the rest of app init hides most of the startup delay.
        TorManager.enabled = preferencesReader.getBoolean("useTor", true)
        if (TorManager.enabled) TorManager.start(applicationContext)

        jobsDownload = listOf()

        prepareView()
        requestConfig()
    }

    // Checkbox handler: persists the choice and starts/stops the embedded Tor instance.
    private fun setTorEnabled(enabled: Boolean) {
        preferencesWriter.putBoolean("useTor", enabled)
        preferencesWriter.apply()

        TorManager.enabled = enabled
        if (enabled) TorManager.start(applicationContext) // idempotent
        else TorManager.stop(applicationContext)          // unbinds; tor shuts down

        info.text =
            if (enabled) "Tor enabled: Pandora traffic goes through Tor (US exit)."
            else "Tor disabled: Pandora traffic connects directly."
    }

    private fun requestConfig() {
        if (!composition.options.__requiredFieldsSet) {
            AlertDialog.Builder(this)
                .setTitle("com.gits.compositioncompass.Configuration required")
                .setMessage(
                    "I'll now open the configuration file for you. The following values need to be configured on first launch:" +
                            System.lineSeparator() + System.lineSeparator() +
                            composition.options.__requiredFields.map { "- " + it }.joinToString(System.lineSeparator()) + System.lineSeparator() + System.lineSeparator() +
                            "Once you're done restart the downloader."
                )
                .setPositiveButton(android.R.string.ok, { a, b -> startActivity(Intent(this, SettingsActivity::class.java)) })
                .setNeutralButton("Help", { a, b -> openWebsite("https://github.com/GhostInTheSteiner/Composition-Compass-Downloader/blob/master/README.md#Setup"); this.finishAffinity() })
                .setIcon(android.R.drawable.ic_dialog_info)
                .show()
        }
    }

    private fun openWebsite(url: String) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(browserIntent)
    }

    private fun createNotificationChannel(id: String): String {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val descriptionText = "Notifications for Compass Compass"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(id, name, importance).apply {
                description = descriptionText
            }

            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        return id
    }

    private fun prepareView() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fieldViews = mutableMapOf()

        info = getView(Fields.Info)
        error = getView(Fields.Error)
        artist = getView(Fields.Artist)
        track = getView(Fields.Track)
        album = getView(Fields.Album)
        genre = getView(Fields.Genre)
        searchQuery = getView(Fields.SearchQuery)
        file = getView(Fields.File)
        favorites = getView(Fields.Favorites)

        download = findViewById(R.id.download)
        update = findViewById(R.id.update)

        useTor = findViewById(R.id.useTor)
        useTor.isChecked = preferencesReader.getBoolean("useTor", true) // set BEFORE the listener
        useTor.setOnCheckedChangeListener { _, checked -> setTorEnabled(checked) }

        queryParameters = listOf(artist, track, album, genre, searchQuery, file, favorites)

        queryParameters.forEach {
            //load last inputs
            it.setText(preferencesReader.getString("view:" + it.id.toString(), ""))

            //register event handler for autocomplete feature
            it.setTokenizer(MultiAutoCompleteTextView.CommaTokenizer())
            it.registerEventHandler(editText_afterChanged = { _ -> queryParameters_AfterChanged(it) })
        }

        mode = getView(Fields.Mode)
        source = getView(Fields.Source)

        mode.adapter = getSpinnerAdapter(
            SpinnerItem(QueryMode.SimilarTracks, "Similar Tracks"),
            SpinnerItem(QueryMode.SimilarArtists, "Similar Artists"),
            SpinnerItem(QueryMode.SimilarAlbums, "Similar Albums"),
            SpinnerItem(QueryMode.Specified, "Specified"),
            SpinnerItem(QueryMode.SpecifiedMoreInteresting, "Liked Artists"),
            //SpinnerItem(QueryMode.SpecifiedLessInteresting, "Liked Artists (Less Interesting)"),
            //SpinnerItem(QueryMode.SpecifiedFavorites, "Liked Artists (Favorites)"),
            //...
        )

        source.adapter = getSpinnerAdapter(
            //SpinnerItem(QuerySource.Spotify, "Spotify"),

            SpinnerItem(QuerySource.Pandora, "Pandora"),
            SpinnerItem(QuerySource.PandoraRest, "Pandora (REST)"),
            SpinnerItem(QuerySource.LastFM, "Last.fm"),
            SpinnerItem(QuerySource.YouTube, "YouTube"),
            SpinnerItem(QuerySource.File, "File")
        )

        mode.setSelection(
            mode.getItem<SpinnerItem> {
                it.id == QueryMode.valueOf(
                    preferencesReader.getString("view:" + mode.id, QueryMode.SimilarTracks.name) ?: "")})

        source.setSelection(
            source.getItem<SpinnerItem> {
                it.id == QuerySource.valueOf(
                    preferencesReader.getString("view:" + source.id, QuerySource.Spotify.name) ?: "")})

        //because Google's implementation for the gui-xml is incomplete...
        mode.registerEventHandler(spinner_onItemSelected = this::mode_OnItemSelected)
        source.registerEventHandler(spinner_onItemSelected = this::source_OnItemSelected)

        composition.changeQueryMode((mode.selectedItem as SpinnerItem).id as QueryMode)

        downloadingLabel = "Downloading..."
        downloadLabel = "Download"

        download.text = downloadLabel

        resetFormatting()
    }

    private fun getSpinnerAdapter(vararg entries: SpinnerItem): ArrayAdapter<SpinnerItem> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, entries)

    private fun getAutocompleteAdapter(entries: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, entries)


    fun<T> getView(field: Fields): T where T: View {
        val id = getResources().getIdentifier(field.viewName, "id", applicationContext.getPackageName());
        val control = findViewById<T>(id)

        fieldViews[field] = control
        return control
    }

    fun updateYoutubeDL(view: View) {
        // UI touches are executed synchronously on Main thread
        resetFormatting()
        info.text = "Update in progress..."

        GlobalScope.launch(Dispatchers.IO + exceptionHandler()) {
            composition.downloader.update()
            runOnUiThread {
                info.text = "Update completed!"
            }
        }
    }

    fun openConfig(view: View) {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    fun openPlayer(view: View) {
        val intent = Intent(this, PlayerActivity::class.java)
        startActivity(intent)
    }

    fun queryParameters_AfterChanged(view: InstantMultiAutoCompleteTextView) {
        val viewId = view.id
        // Extract Editable values eagerly on the main thread
        val viewText = view.text.toString()
        val artistText = artist.text.toString()
        val albumText = album.text.toString()

        // Cancel previous in-flight autocomplete queries to prevent stale overwrites
        autocompleteJobs[viewId]?.cancel()

        autocompleteJobs[viewId] = GlobalScope.launch(Dispatchers.IO + exceptionHandler()) {
            composition.query.prepare()

            var suggestions = listOf<String>()

            when (val query = composition.query) {
                is IStreamingServiceQuery -> {
                    val valuesCurrent = viewText.split(",").map { it.trim() }
                    val valuesArtist = artistText.split(",").map { it.trim() }
                    val valuesAlbum = albumText.split(",").map { it.trim() }

                    val valuesCurrentLatest = valuesCurrent.lastOrNull() ?: ""
                    val valuesCurrentLatest_Artist =
                        if (valuesArtist.count() < valuesCurrent.count()) ""
                        else valuesArtist[valuesCurrent.count() - 1]

                    val valuesCurrentLatest_Album =
                        if (valuesAlbum.count() < valuesCurrent.count()) ""
                        else valuesAlbum[valuesCurrent.count() - 1]

                    suggestions =
                        when (viewId) {
                            R.id.track ->
                                query.searchTrack(valuesCurrentLatest, valuesCurrentLatest_Artist, valuesCurrentLatest_Album)
                                    .sortedByDescending { it.popularity }.map { it.name }
                            R.id.album ->
                                query.searchAlbum(valuesCurrentLatest, valuesCurrentLatest_Artist)
                                    .sortedByDescending { it.popularity }.map { it.name }
                            R.id.artist ->
                                query.searchArtist(valuesCurrentLatest)
                                    .sortedByDescending { it.popularity }.map { it.name }
                            R.id.genre ->
                                query.searchGenre(valuesCurrentLatest, valuesCurrentLatest_Artist)
                                    .sortedBy { it }
                            else -> suggestions
                        }

                    suggestions = suggestions.filter { it.contains(valuesCurrentLatest, true) }
                }
            }

            suggestions = suggestions.distinct().map { it.replace(',', ' ') }

            withContext(Dispatchers.Main) {
                view.setAdapter(getAutocompleteAdapter(suggestions))
            }
        }

        queryParameters.forEach { preferencesWriter.putString("view:" + it.id.toString(), it.text.toString()) }
        preferencesWriter.apply()
    }

    fun mode_OnItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateState()
    fun source_OnItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = updateState()

    fun updateState() {
        //hide all fields
        hideQueryParameters()

        //set query source
        val source_ = (source.selectedItem as SpinnerItem).id as QuerySource
        composition.changeQuerySource(source_)

        //set query mode
        val mode_ = (mode.selectedItem as SpinnerItem).id as QueryMode
        composition.changeQueryMode(mode_)

        //enable supported fields
        queryParameters.forEach {
            val supported = composition.query.supportedFields.map { fieldViews[it] }

            if (supported.contains(it))
                (it.parent as TableRow).visibility = View.VISIBLE
            else
                it.setText("") //clear, so the field doesn't interfer with others while it's invisible
        }

        //enable mode spinner only if supported
        if (source_ in listOf(QuerySource.YouTube, QuerySource.File)) {
            val item = mode.getItem<SpinnerItem> { it.id as QueryMode == QueryMode.Specified }
            mode.setSelection(item)
            mode.isEnabled = false
        }
        else
            mode.isEnabled = true

        preferencesWriter.putString("view:" + source.id, source_.name)
        preferencesWriter.putString("view:" + mode.id, mode_.name)

        preferencesWriter.apply()
    }

    private fun hideQueryParameters() {
        queryParameters.forEach { (it.parent as TableRow).visibility = View.GONE }
    }

    fun getTextViewValues(textView: TextView) =
        textView.text.toString().split(",").map { it.trim() }.filter { it.length > 0 }

    fun download(view: View) {
        // Immediate synchronous check for re-entrancy
        if (isDownloading) return
        isDownloading = true

        try {
            hideKeyboard()
            resetFormatting()

            val required = composition.query.requiredFields
            val supported = composition.query.supportedFields

            val requiredFields = required.map { it.map { fieldViews[it]!! } }
            val supportedFields = supported.map { fieldViews[it]!! }

            if (requiredFields.any { it.all { it.hasUserContent() } }) {
                //pass => start download
            }
            else {
                info.text =
                    "The following fields are required:" + System.lineSeparator() + System.lineSeparator() +
                            required.map { "\"" + it.map { it.viewName }.joinToString(", ") + "\"" }
                                .joinToString(System.lineSeparator() + "or ")

                isDownloading = false // Unlock state
                return
            }

            updateYoutubeDL(findViewById(R.id.update))

            download.isEnabled = false
            update.isEnabled = false
            download.text = this.downloadingLabel

            var directories = listOf<TargetDirectory>()

            info.text = "All required fields were set, initiating download..."

            if (composition.query is IFileQuery) {
                val fileQuery = composition.query as IFileQuery

                GlobalScope.launch(exceptionHandler()) { downloadDirectories(fileQuery.getSpecifiedTracks()) }

            } else if (composition.query is IYoutubeQuery) {
                val youtubeQuery = composition.query as IYoutubeQuery

                youtubeQuery.clear()

                getTextViewValues(searchQuery).forEach { youtubeQuery.addSearchQuery(it) }

                GlobalScope.launch(exceptionHandler()) { downloadDirectories(youtubeQuery.getSearchQueryResults()) }

            } else if (composition.query is IStreamingServiceQuery) {
                val serviceQuery = composition.query as IStreamingServiceQuery

                // Freeze the spinner selection & required views synchronously on Main thread
                val selectedMode = (mode.selectedItem as SpinnerItem).id as QueryMode
                val isFavoritesOnly = supportedFields.all { it.id == R.id.favorites }
                val artistView = supportedFields.firstOrNull { it.id == R.id.artist } as? TextView
                val artists = if (isFavoritesOnly || artistView == null) listOf() else getTextViewValues(artistView)

                // Structure to pass frozen UI states across thread boundary
                class FieldData(val id: Int, val values: List<String>, val matchArtistsItems: Boolean)
                val activeFields = mutableListOf<FieldData>()

                // Track and Album already carry the artist along with them (addTrack/addAlbum both
                // take the artist as a parameter), so if either has content, the artist must NOT
                // also be added on its own via addArtist() - otherwise it gets added twice.
                val trackField = supportedFields.firstOrNull { it.id == R.id.track } as? TextView
                val albumField = supportedFields.firstOrNull { it.id == R.id.album } as? TextView

                val trackHasContent = trackField != null &&
                        (trackField.parent as TableRow).visibility == View.VISIBLE &&
                        trackField.hasUserContent()
                val albumHasContent = albumField != null &&
                        (albumField.parent as TableRow).visibility == View.VISIBLE &&
                        albumField.hasUserContent()

                supportedFields.forEach {
                    val visible = (it.parent as TableRow).visibility == View.VISIBLE
                    if (visible && it.hasUserContent()) {
                        // Skip the standalone artist entry if tracks or albums already cover it
                        if (it.id == R.id.artist && (trackHasContent || albumHasContent))
                            return@forEach

                        val values = getTextViewValues(it as TextView)
                        activeFields.add(FieldData(it.id, values, artists.lastIndex == values.lastIndex))
                    }
                }

                // TODO: Only first artist added, apparently?

                // Call only the addX() methods whose corresponding fields are (enabled + set)!
                jobsDownload += GlobalScope.launch(Dispatchers.IO + exceptionHandler()) {
                    serviceQuery.clear()
                    serviceQuery.prepare()

                    var artistSuccess: Boolean? = null
                    var trackSuccess: Boolean? = null
                    var albumSuccess: Boolean? = null
                    var genreSuccess: Boolean? = null

                    runBlocking {
                        runOnUiThread { info.text = "Fetching data from source..." }

                        activeFields.forEach { fieldData ->
                            jobsDownload += when (fieldData.id) {
                                R.id.artist -> launch(exceptionHandler()) {
                                    artistSuccess = false
                                    fieldData.values.forEach { artistSuccess = serviceQuery.addArtist(it) || artistSuccess!! }
                                }
                                R.id.track -> {
                                    trackSuccess = false
                                    if (!fieldData.matchArtistsItems)
                                        throw Exception("Number of tracks must match number of artists! Artist need to be listed several times if multiple tracks by the same artist are desired.")
                                    launch(exceptionHandler()) {
                                        fieldData.values.forEachIndexed { i, it ->
                                            trackSuccess = serviceQuery.addTrack(it, artists[i]) || trackSuccess!!
                                        }
                                    }
                                }
                                R.id.album -> {
                                    albumSuccess = false
                                    if (!fieldData.matchArtistsItems)
                                        throw Exception("Number of albums must match number of artists! Artist need to be listed several times if multiple tracks by the same artist are desired.")
                                    launch(exceptionHandler()) {
                                        fieldData.values.forEachIndexed { i, it ->
                                            albumSuccess = serviceQuery.addAlbum(it, artists[i]) || albumSuccess!!
                                        }
                                    }
                                }
                                R.id.genre -> {
                                    genreSuccess = false
                                    launch(exceptionHandler()) {
                                        fieldData.values.forEach { genreSuccess = serviceQuery.addGenre(it) || genreSuccess!! }
                                    }
                                }
                                else -> Job()
                            }
                        }
                    }

                    // assume true if not relevant / displayed in current mode
                    if (!(artistSuccess ?: true)) { runOnUiThread { info.text = "Artist not found!"; unlockDownload() }; return@launch }
                    if (!(trackSuccess ?: true)) { runOnUiThread { info.text = "Track not found!"; unlockDownload() }; return@launch }
                    if (!(albumSuccess ?: true)) { runOnUiThread { info.text = "Album not found!"; unlockDownload() }; return@launch }
                    if (!(genreSuccess ?: true)) { runOnUiThread { info.text = "Genre not found!"; unlockDownload() }; return@launch }

                    // TODO: for some reason only one entry even though multiple artists defined?

                    directories = when (selectedMode) {
                        QueryMode.SimilarTracks -> serviceQuery.getSimilarTracks()
                        QueryMode.SimilarAlbums -> serviceQuery.getSimilarAlbums()
                        QueryMode.SimilarArtists -> serviceQuery.getSimilarArtists()
                        QueryMode.Specified -> serviceQuery.getSpecified()
                        QueryMode.SpecifiedMoreInteresting -> serviceQuery.getSpecifiedMoreInteresting()
                        //QueryMode.SpecifiedLessInteresting -> serviceQuery.getSpecifiedLessInteresting()
                        //QueryMode.SpecifiedFavorites -> serviceQuery.getSpecifiedFavorites()
                        //...
                    }

                    downloadDirectories(directories)
                }
            }
        }
        catch (e: Exception) {
            isDownloading = false // Unlock state on synchronous failure
            printError(e)
        }
    }

    private suspend fun downloadDirectories(directories: List<TargetDirectory>) {
        runOnUiThread { info.text = "Fetching tracks from YouTube..." }

        composition.downloader.start(
            directories,
            onUpdate = {
                runOnUiThread {
                    info.text =
                        "Progress: " + it.progress + "%" + System.lineSeparator() + System.lineSeparator() +
                                "Storing in the following locations:" + System.lineSeparator() + System.lineSeparator() +
                                directories.map { "\"${it.targetPath}\"" }
                                    .joinToString(System.lineSeparator() + "-----------------" + System.lineSeparator())
                }
            },
            onFailure = { searchQuery, exception ->
                runOnUiThread {
                    error.text =
                        error.text.toString() + "[" + searchQuery + "]" + System.lineSeparator() +
                                exception.message + System.lineSeparator() + System.lineSeparator()
                }
            }
        )

        runOnUiThread {
            info.text =
                "Download completed!" + System.lineSeparator() + System.lineSeparator() +
                        "Files were stored in:" + System.lineSeparator() + System.lineSeparator() +
                        directories.map { "\"${it.targetPath}\"" }
                            .joinToString(System.lineSeparator() + "-----------------" + System.lineSeparator())

            unlockDownload()
        }
    }

    fun hideKeyboard() {
        val imm: InputMethodManager =
            this.getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        //Find the currently focused view, so we can grab the correct window token from it.
        var view = this.currentFocus
        //If no view currently has focus, create a new one, just so we can grab a window token from it
        if (view == null) {
            view = View(this)
        }
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun exceptionHandler() =
        CoroutineExceptionHandler { context, throwable ->
            runOnUiThread { unlockDownload() }
            printError(throwable)
        }

    fun unlockDownload() {
        isDownloading = false
        download.text = downloadLabel
        download.isEnabled = true
        update.isEnabled = true
    }

    fun printError(e: Throwable) {
        // I/O logic for logger sent to background coroutine, no longer blocking UI Thread
        GlobalScope.launch(Dispatchers.IO) {
            logger.warn(Exception(e.message, e.cause))
        }
        runOnUiThread {
            error.text = getErrorMessage(e.message ?: "Unknown cause", e.stackTraceToString())
        }
    }

    fun printError(e: Exception) {
        // I/O logic for logger sent to background coroutine, no longer blocking UI Thread
        GlobalScope.launch(Dispatchers.IO) {
            logger.warn(e)
        }
        runOnUiThread {
            error.setPadding(15)
            error.text = getErrorMessage(e.message ?: "Unknown cause", e.stackTraceToString())
        }
    }

    fun getErrorMessage(message: String, trace: String) =
        "The following error occured ;(" + System.lineSeparator() + System.lineSeparator() +
                message + System.lineSeparator() + System.lineSeparator() +
                trace

    fun closeApp(view: View) {
        exitProcess(0)
    }

    fun resetFormatting() {
        info.text = ""
        error.text = ""

        queryParameters.forEach { it.getBackground().clearColorFilter() }
    }
}