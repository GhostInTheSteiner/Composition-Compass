package com.gits.compositioncompass.Queries

import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import DownloadFolder
import Fields
import QueryMode
import com.gits.compositioncompass.Models.SearchQuery
import com.gits.compositioncompass.Models.TargetDirectory

class YouTubeQuery : IYoutubeQuery {
    private var addedSearchQueries: MutableList<String>
    private val options: CompositionCompassOptions

    override val requiredFields: List<List<Fields>> get() = listOf(listOf(Fields.SearchQuery))
    override val supportedFields: List<Fields> get() = listOf(Fields.SearchQuery)

    constructor(options: CompositionCompassOptions) {
        this.addedSearchQueries = mutableListOf()
        this.options = options
    }

    override fun addSearchQuery(query: String): Boolean {
        addedSearchQueries += query
        return true
    }

    override fun getSearchQueryResults(): List<TargetDirectory> =
        addedSearchQueries.map {
            TargetDirectory(
                getPath(DownloadFolder.Stations, getSubFolder(it)),
                listOf(SearchQuery(it))
            )
        }

    private fun getSubFolder(searchOrUrl: String): String =
        if ((searchOrUrl.startsWith("http://") || searchOrUrl.startsWith("https://")) && searchOrUrl.contains("list="))
            //playlist; quick and dirty alternative to avoid getting the title ;(
            "!Playlist (" + searchOrUrl.replace("\\W+".toRegex(), "").replace("https?".toRegex(), "") + ")"

        else if (searchOrUrl.startsWith("http://") || searchOrUrl.startsWith("https://"))
            //single video
            "!Singles"

        else
            //search query
            "!Search (" + searchOrUrl.replace('/', ' ') + ")"

    override fun changeMode(mode: QueryMode) {
        //pass => gui is always locked to "Specified"
    }

    override fun clear() {
        addedSearchQueries.clear()
    }

    override suspend fun prepare() {
        //pass => nothing to connect to
    }

    private fun getPath(folder: DownloadFolder, subFolderName: String): String {
        return options.rootDirectoryPath + "/" + folder.folderName + "/" + subFolderName
    }
}