package com.gits.compositioncompass

import com.gits.compositioncompass.Configuration.CompositionRoot
import QuerySource
import QueryMode
import Fields
import com.gits.compositioncompass.Queries.IFileQuery
import com.gits.compositioncompass.Queries.IStreamingServiceQuery
import com.gits.compositioncompass.Queries.IYoutubeQuery
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.gits.compositioncompass.databinding.ActivityMainBinding

import hasUserContent
import kotlinx.coroutines.*
import android.app.NotificationChannel
import android.content.SharedPreferences
import android.widget.*
import getItem
import registerEventHandler
import setSelection
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.*
import android.app.AlertDialog
import com.gits.compositioncompass.Models.TargetDirectory
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Logger
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.PermissionManager
import com.gits.compositioncompass.ui.controls.InstantMultiAutoCompleteTextView
import com.gits.compositioncompass.ui.controls.SpinnerItem
import kotlin.Exception


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
    private lateinit var mode: Spinner
    private lateinit var source: Spinner
    private lateinit var download: Button
    private lateinit var update: Button

    private lateinit var preferencesReader: SharedPreferences
    private lateinit var preferencesWriter: SharedPreferences.Editor
    private lateinit var jobsDownload: List<Job>
    private lateinit var fieldViews: MutableMap<Fields, View>
    private lateinit var notificationChannelId: String

    private lateinit var downloadingLabel: String
    private lateinit var downloadLabel: String

    private lateinit var composition: CompositionRoot
    private lateinit var binding: ActivityMainBinding

    override fun onResume(savedInstanceState: Bundle?) {
        composition.activity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            val permissions = PermissionManager(this)

            val storage = permissions.requestStorageLegacy()

            super.onCreate(savedInstanceState)

            notificationChannelId = createNotificationChannel("composition-compass")

            composition = CompositionRoot.getInstance(this)
            logger = composition.logger(this)

            preferencesReader = composition.preferencesReader
            preferencesWriter = composition.preferencesWriter

            jobsDownload = listOf()

            prepareView()
            requestConfig()
            updateYoutubeDL(findViewById(R.id.update))
        }
        catch (e: Exception) {
            val mBuilder: NotificationCompat.Builder = NotificationCompat.Builder(this, notificationChannelId)
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
                .setPositiveButton(android.R.string.ok, { a, b -> openFile(composition.options.__filePath); this.finishAffinity() })
                .setNeutralButton("Help", { a, b -> openWebsite("https://github.com/GhostInTheSteiner/Composition-Compass-Downloader/blob/master/README.md#Setup"); this.finishAffinity() })
                .setIcon(android.R.drawable.ic_dialog_info)
                .show()
        }
    }

    private fun openFile(filePath: String) {
        val intent = Intent(Intent.ACTION_EDIT)
        val data = FileProvider.getUriForFile(
            applicationContext,
            BuildConfig.APPLICATION_ID + ".provider",
            File(filePath)
        );
        intent.setDataAndType(data, "text/plain")
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startActivity(intent)
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

        download = findViewById(R.id.download)
        update = findViewById(R.id.update)

        queryParameters = listOf(artist, track, album, genre, searchQuery, file)

        queryParameters.forEach {
            //load last inouts
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
            SpinnerItem(QueryMode.Specified, "Specified")
        )

        source.adapter = getSpinnerAdapter(
            SpinnerItem(QuerySource.Spotify, "Spotify"),
            SpinnerItem(QuerySource.LastFM, "LastFM"),
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
        GlobalScope.launch(exceptionHandler()) {
            resetFormatting()
            info.text = "Update in progress..."
            composition.downloader.update();
            info.text = "Update completed!"
        }
    }

    fun openConfig(view: View) {
        openFile(composition.options.__filePath)
        this.finishAffinity()
    }

    fun openPlayer(view: View) {
        val intent = Intent(this, PlayerActivity::class.java)
        startActivity(intent)
    }

    fun queryParameters_AfterChanged(view: InstantMultiAutoCompleteTextView) {
        GlobalScope.launch(exceptionHandler()) {

            composition.query.prepare()

            var suggestions = listOf<String>()

            runBlocking {

                when (val query = composition.query) {
                    is IStreamingServiceQuery -> {

                        //take last value from field
                        val valuesCurrent = view.text.toString().split(",").map { it.trim() }
                        val valuesArtist = artist.text.toString().split(",").map { it.trim() }
                        val valuesAlbum = album.text.toString().split(",").map { it.trim() }

                        val valuesCurrentLatest = valuesCurrent.last()
                        val valuesCurrentLatest_Artist =
                            if (valuesArtist.count() < valuesCurrent.count()) ""
                            else valuesArtist[valuesCurrent.count() - 1]

                        val valuesCurrentLatest_Album =
                            if (valuesAlbum.count() < valuesCurrent.count()) ""
                            else valuesAlbum[valuesCurrent.count() - 1]

                        suggestions =
                            when (view.id) {
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

            }

            runOnUiThread { view.setAdapter(getAutocompleteAdapter(suggestions)) }
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

//        composition.query.supportedFields.forEach { (fieldViews[it]!!.parent as TableRow).visibility = View.VISIBLE }

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

                return
            }

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

                //call only the addX() methods whose corresponding fields are (enabled + set)!
                jobsDownload += GlobalScope.launch(exceptionHandler()) {
                    serviceQuery.clear()
                    serviceQuery.prepare()

                    var artistSuccess = true
                    var trackSuccess = true
                    var albumSuccess = true
                    var genreSuccess = true

                    runBlocking {
                        val artistView =
                            (supportedFields.filter { it.id == R.id.artist }.first() as TextView)
                        val artists = getTextViewValues(artistView)

                        runOnUiThread { info.text = "Fetching data from source..." }

                        supportedFields.forEach {

                            val visible = (it.parent as TableRow).visibility == View.VISIBLE

                            if (visible && it.hasUserContent()) {
                                jobsDownload +=
                                    when (it.id) {
                                        R.id.artist -> launch(exceptionHandler()) {
                                            getTextViewValues(it as TextView).forEachIndexed { i, it ->
                                                artistSuccess = artistSuccess && serviceQuery.addArtist(it)
                                            }
                                        }
                                        R.id.track -> launch(exceptionHandler()) {
                                            getTextViewValues(it as TextView).forEachIndexed { i, it ->
                                                trackSuccess = trackSuccess && serviceQuery.addTrack(it, artists[i])
                                            }
                                        }
                                        R.id.album -> launch(exceptionHandler()) {
                                            getTextViewValues(it as TextView).forEachIndexed { i, it ->
                                                albumSuccess = albumSuccess && serviceQuery.addAlbum(it, artists[i])
                                            }
                                        }
                                        R.id.genre -> launch(exceptionHandler()) {
                                            getTextViewValues(it as TextView).forEachIndexed { i, it ->
                                                genreSuccess = genreSuccess && serviceQuery.addGenre(it)
                                            }
                                        }
                                        else -> Job()
                                    }
                            }
                        }
                    }

                    if (!artistSuccess) { runOnUiThread { info.text = "\"Artist\" not found!"; unlockDownload(); }; return@launch }
                    if (!trackSuccess) { runOnUiThread { info.text = "\"Track\" not found!"; unlockDownload(); }; return@launch }
                    if (!albumSuccess) { runOnUiThread { info.text = "\"Album\" not found!"; unlockDownload(); }; return@launch }
                    if (!genreSuccess) { runOnUiThread { info.text = "\"Genre\" not found!"; unlockDownload(); }; return@launch }

                    val selectedMode = (mode.selectedItem as SpinnerItem).id as QueryMode

                    directories =
                        when (selectedMode) {
                            QueryMode.SimilarTracks -> serviceQuery.getSimilarTracks()
                            QueryMode.SimilarAlbums -> serviceQuery.getSimilarAlbums()
                            QueryMode.SimilarArtists -> serviceQuery.getSimilarArtists()
                            QueryMode.Specified -> serviceQuery.getSpecified()
                        }

                    downloadDirectories(directories)
                }
            }
        }
        catch (e: Exception) {
            printError(e)
        }
    }

    private suspend fun downloadDirectories(directories: List<TargetDirectory>) {
        //the below needs to be removed from this scope!

        runOnUiThread { info.text = "Fetching tracks from YouTube..." }

        composition.downloader.start(
            directories,
            onUpdate = {
                runOnUiThread {
                    info.text =
                        "Progress: " + it.progress + "%" + System.lineSeparator() + System.lineSeparator() +
                                "Storing in the following locations:" + System.lineSeparator() + System.lineSeparator() +
                                directories.map { "\"${getShortPath(it.targetPath)}\"" }
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
                        directories.map { "\"${getShortPath(it.targetPath)}\"" }
                            .joinToString(System.lineSeparator() + "-----------------" + System.lineSeparator())

            unlockDownload()
        }
    }

    fun getShortPath(fullPath: String) =
        fullPath.split("Pandora/").drop(1).joinToString("Pandora/")

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
            runOnUiThread { unlockDownload() };
            printError(throwable)
        }

    fun unlockDownload() {
        download.text = downloadLabel
        download.isEnabled = true
        update.isEnabled = true
    }

    fun printError(e: Throwable) {
        runOnUiThread {
            error.text = getErrorMessage(e.message ?: "Unknown cause", e.stackTraceToString())
            logger.error(Exception(e.message, e.cause))
        }
    }

    fun printError(e: Exception) {
        runOnUiThread {
            error.text = getErrorMessage(e.message ?: "Unknown cause", e.stackTraceToString())
            logger.error(e)
        }
    }

    fun getErrorMessage(message: String, trace: String) =
        "The following error occured ;(" + System.lineSeparator() + System.lineSeparator() +
                message + System.lineSeparator() + System.lineSeparator() +
                trace

    fun closeApp() {
        finishAffinity()
    }

    fun resetFormatting() {
        info.text = ""
        error.text = ""

        queryParameters.forEach { it.getBackground().clearColorFilter() }
    }
}