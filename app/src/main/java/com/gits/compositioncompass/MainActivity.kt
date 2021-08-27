package com.gits.compositioncompass

import CompositionRoot
import QuerySource
import DownloadMode
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
import registerEventHandler


class MainActivity : AppCompatActivity() {

    private lateinit var queryParameters: List<TextView>
    private lateinit var info: TextView
    private lateinit var error: TextView
    private lateinit var genre: EditText
    private lateinit var artist: EditText
    private lateinit var track: EditText
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
        genre = getView(Fields.Genre)
        searchQuery = getView(Fields.SearchQuery)

        queryParameters = listOf(info, artist, track, genre, searchQuery)

        mode = getView(Fields.Mode)
        source = getView(Fields.Source)

        mode.adapter = getSpinnerAdapter(
            SpinnerItem(DownloadMode.SimilarTracks, "Similar Tracks"),
            SpinnerItem(DownloadMode.SimilarArtists, "Similar Artists"),
            SpinnerItem(DownloadMode.SimilarAlbums, "Similar Albums"),
            SpinnerItem(DownloadMode.SpecificTracks, "Specified Tracks"),
            SpinnerItem(DownloadMode.SpecificAlbums, "Specified Albums"),
            SpinnerItem(DownloadMode.SpecificArtists, "Specified Artists")
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

        queryParameters.forEach { it.registerEventHandler<EditText>(editText_onFocusChange = this::queryParameters_OnFocusChange) }
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
        GlobalScope.launch {
            info.text = "Update in progress..."
            composition.youtube.update();
            info.text = "Update completed!"
        }
    }

    fun mode_OnItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        //is there even anything to do here?
    }

    fun source_OnItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        disableQueryParameters()

        composition.changeQuery((source.selectedItem as SpinnerItem).id as QuerySource)
        composition.query.supportedFields.forEach { views[it]!!.isEnabled = true }
    }

    fun queryParameters_OnFocusChange(v: View?, hasFocus: Boolean) {

    }

    //formatExclusiveFields(view: View)
    //  val exclusiveIDs = query.ExclusiveFields.map { it.Field.id }
    //
    //  if (exclusiveIDs.contains(view.id))
    //      query.ExclusiveFields.filter { it.Field.id != view.id }.forEach { it.Field.disable }
    //      show "Only of the the following fields may be set: " + query.ExclusiveFields


    private fun disableQueryParameters() {
        queryParameters.forEach { it.isEnabled = false }
    }




    fun getTextViewValues(textView: TextView) =
        textView.text.toString().split(";").filter { it.length > 0 }

    fun download(view: View) {

        val requiredFields = composition.query.requiredFields.map { views[it]!! }
        val supportedFields = composition.query.supportedFields.map { views[it]!! }

        if (requiredFields.all { it.hasUserContent() })
            info.text = "All required fields were set, starting download..."

        else {
            info.text = "The following required fields are missing: " +
                            requiredFields.filter { !it.hasUserContent() }.joinToString(", ")
            return
        }

        if (composition.query is IFileQuery) {
            val fileQuery = composition.query as IFileQuery
        }

        else if (composition.query is IYoutubeQuery) {
            val youtubeQuery = composition.query as IYoutubeQuery
        }

        else if (composition.query is IStreamingServiceQuery) {

            val serviceQuery = composition.query as IStreamingServiceQuery

            //call only the addX() methods whose corresponding fields are (enabled + set)!
            GlobalScope.launch {
                serviceQuery.clear()
                serviceQuery.prepare()

                runBlocking {
                    val artist = (supportedFields.filter { it.id == R.id.artist }.first() as TextView).text.toString()

                    supportedFields.forEach {
                        if (it.isEnabled && it.hasUserContent()) {
                            when (it.id) {
                                R.id.artist -> getTextViewValues(it as TextView).forEach { launch { serviceQuery.addArtist(it) } }
                                R.id.track -> getTextViewValues(it as TextView).forEach { launch { serviceQuery.addTrack(it, artist) } }
                                R.id.genre -> getTextViewValues(it as TextView).forEach { launch { serviceQuery.addGenre(it) } }
                            }
                        }
                    }
                }

                val selectedMode = (mode.selectedItem as SpinnerItem).id as DownloadMode

                val tracks =
                    when (selectedMode) {
                        DownloadMode.SimilarTracks -> serviceQuery.getSimilarTracks()
                        DownloadMode.SimilarAlbums -> serviceQuery.getSimilarAlbums()
                        DownloadMode.SimilarArtists -> serviceQuery.getSimilarArtists()
                        DownloadMode.SpecificArtists -> serviceQuery.getSpecificArtists()
                        DownloadMode.SpecificAlbums -> serviceQuery.getSpecificAlbums()
                        DownloadMode.SpecificTracks -> serviceQuery.getSpecificTracks()
                    }

                val searchQueries = tracks.map { it.name + " " + it.artists.map { it.name }.joinToString(" ") }

                composition.youtube.download(
                    searchQueries,
                    onUpdate = { runOnUiThread { info.text = "Progress: " + it.progress.toString() + "%" } },
                    onFailure = { runOnUiThread { error.text = it.message } }
                )

                runOnUiThread { info.text = "Download completed! Files were stored in ${composition.options.downloadDirectory}" }
            }
        }
    }

    fun download_(view: View) {
        val job = GlobalScope.launch {

        }
    }
}