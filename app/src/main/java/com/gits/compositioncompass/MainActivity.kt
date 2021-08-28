package com.gits.compositioncompass

import CompositionRoot
import QuerySource
import QueryMode
import Fields
import IFileQuery
import IStreamingServiceQuery
import IYoutubeQuery
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.gits.compositioncompass.databinding.ActivityMainBinding

import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import hasUserContent
import kotlinx.coroutines.*
import SpinnerItem
import android.widget.*
import getItem
import registerEventHandler
import setSelection
import java.lang.Exception


class MainActivity : AppCompatActivity() {

    private lateinit var queryParameters: List<TextView>
    private lateinit var info: TextView
    private lateinit var error: TextView
    private lateinit var genre: EditText
    private lateinit var artist: EditText
    private lateinit var track: EditText
    private lateinit var album: EditText
    private lateinit var searchQuery: EditText
    private lateinit var mode: Spinner
    private lateinit var source: Spinner

    private lateinit var views: MutableMap<Fields, View>

    private lateinit var composition: CompositionRoot
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        composition = CompositionRoot.getInstance(application)

        requestPerms()
        prepareView()
    }

    private fun requestPerms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1);
        }
    }


    private fun prepareView() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        views = mutableMapOf()

        info = getView(Fields.Info)
        error = getView(Fields.Error)
        artist = getView(Fields.Artist)
        track = getView(Fields.Track)
        album = getView(Fields.Album)
        genre = getView(Fields.Genre)
        searchQuery = getView(Fields.SearchQuery)

        queryParameters = listOf(info, artist, track, album, genre, searchQuery)

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

        //because Google's implementation for the gui-xml is incomplete...
        mode.registerEventHandler<Spinner>(spinner_onItemSelected = this::mode_OnItemSelected)
        source.registerEventHandler<Spinner>(spinner_onItemSelected = this::source_OnItemSelected)

        composition.changeQueryMode((mode.selectedItem as SpinnerItem).id as QueryMode)

        resetStatusMessages()
    }

    private fun getSpinnerAdapter(vararg entries: SpinnerItem): ArrayAdapter<SpinnerItem> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, entries)


    fun<T> getView(field: Fields): T where T: View {
        val id = getResources().getIdentifier(field.viewName, "id", applicationContext.getPackageName());
        val control = findViewById<T>(id)

        views[field] = control
        return control
    }

    fun updateYoutubeDL(view: View) {
        GlobalScope.launch(CoroutineExceptionHandler()) {
            info.text = "Update in progress..."
            composition.youtube.update();
            info.text = "Update completed!"
        }
    }

    fun mode_OnItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        composition.changeQueryMode((mode.selectedItem as SpinnerItem).id as QueryMode)
    }

    fun source_OnItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        disableQueryParameters()

        val source = (source.selectedItem as SpinnerItem).id as QuerySource

        if (source in listOf(QuerySource.YouTube, QuerySource.File)) {
            val item = mode.getItem<SpinnerItem> { it.id as QueryMode == QueryMode.Specified }
            mode.setSelection(item)
            mode.isEnabled = false
        }

        else {
            mode.isEnabled = true
        }

        composition.changeQuerySource(source)
        composition.query.supportedFields.forEach { views[it]!!.isEnabled = true }
    }

    private fun disableQueryParameters() {
        queryParameters.forEach { it.isEnabled = false }
    }




    fun getTextViewValues(textView: TextView) =
        textView.text.toString().split(";").filter { it.length > 0 }

    fun download(view: View) {
        try {
            resetStatusMessages()

            val required = composition.query.requiredFields
            val supported = composition.query.supportedFields

            val requiredFields = required.map { it.map { views[it]!! } }
            val supportedFields = supported.map { views[it]!! }

            if (requiredFields.any { it.all { it.hasUserContent() } })
                info.text = "All required fields were set, starting download..."
            else {
                info.text =
                    "One of the following field-combinations is required:" + System.lineSeparator() + System.lineSeparator()
                required.map { it.map { it.viewName }.joinToString(", ") }
                    .joinToString(" or" + System.lineSeparator())
                return
            }

            if (composition.query is IFileQuery) {
                val fileQuery = composition.query as IFileQuery
            } else if (composition.query is IYoutubeQuery) {
                val youtubeQuery = composition.query as IYoutubeQuery
            } else if (composition.query is IStreamingServiceQuery) {

                val serviceQuery = composition.query as IStreamingServiceQuery

                //call only the addX() methods whose corresponding fields are (enabled + set)!
                GlobalScope.launch(CoroutineExceptionHandler()) {
                    serviceQuery.clear()
                    serviceQuery.prepare()

                    runBlocking {
                        val artistView =
                            (supportedFields.filter { it.id == R.id.artist }.first() as TextView)
                        val artists = getTextViewValues(artistView)

                        supportedFields.forEach {
                            if (it.isEnabled && it.hasUserContent()) {
                                when (it.id) {
                                    R.id.artist -> launch(CoroutineExceptionHandler()) {
                                        getTextViewValues(it as TextView).forEachIndexed { i, it ->
                                            serviceQuery.addArtist(
                                                it
                                            )
                                        }
                                    }
                                    R.id.track -> launch(CoroutineExceptionHandler()) {
                                        getTextViewValues(it as TextView).forEachIndexed { i, it ->
                                            serviceQuery.addTrack(
                                                it,
                                                artists[i]
                                            )
                                        }
                                    }
                                    R.id.genre -> launch(CoroutineExceptionHandler()) {
                                        getTextViewValues(it as TextView).forEachIndexed { i, it ->
                                            serviceQuery.addGenre(
                                                it
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val selectedMode = (mode.selectedItem as SpinnerItem).id as QueryMode

                    val directories =
                        when (selectedMode) {
                            QueryMode.SimilarTracks -> serviceQuery.getSimilarTracks()
                            QueryMode.SimilarAlbums -> serviceQuery.getSimilarAlbums()
                            QueryMode.SimilarArtists -> serviceQuery.getSimilarArtists()
                            QueryMode.Specified -> serviceQuery.getSpecified()
                        }

                    composition.youtube.download(
                        directories,
                        onUpdate = {
                            runOnUiThread {
                                info.text = "Progress: " + it.progress.toString() + "%"
                            }
                        },
                        onFailure = { track, exception ->
                            runOnUiThread {
                                error.text =
                                    error.text.toString() + "[" + track + "]" + System.lineSeparator() +
                                            exception.message + System.lineSeparator() + System.lineSeparator()
                            }
                        }
                    )
                }
            }
            runOnUiThread {
                info.text =
                    "Download completed! Files were stored in ${composition.options.rootDirectory}"
            }
        }
        catch (e: Exception) {
            printError(e)
        }
    }

    fun CoroutineExceptionHandler() =
        CoroutineExceptionHandler { context, throwable -> printError(throwable) }

    fun printError(e: Throwable) {
        runOnUiThread { error.text = getErrorMessage(e.message ?: "Unknown cause", e.stackTraceToString()) }
    }

    fun printError(e: Exception) {
        runOnUiThread { error.text = getErrorMessage(e.message ?: "Unknown cause", e.stackTraceToString()) }
    }

    fun getErrorMessage(message: String, trace: String) =
        "Download failed: " + System.lineSeparator() + System.lineSeparator() +
        message + System.lineSeparator() +
        trace

    fun resetStatusMessages() {
        info.text = ""
        error.text = ""
    }
}