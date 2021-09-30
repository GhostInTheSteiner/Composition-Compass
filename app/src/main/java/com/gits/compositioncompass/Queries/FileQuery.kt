package com.gits.compositioncompass.Queries

import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import DownloadFolder
import Fields
import QueryMode
import com.gits.compositioncompass.Models.SearchQuery
import com.gits.compositioncompass.Models.TargetDirectory
import java.io.File

class FileQuery(val options: CompositionCompassOptions) : IFileQuery {
    override val requiredFields: List<List<Fields>> get() = listOf(listOf())
    override val supportedFields: List<Fields> get() = listOf(Fields.File)
    
    override fun changeMode(mode: QueryMode) {
        //TODO
    }

    override fun clear() {
        //TODO
    }

    override suspend fun prepare() {
        //TODO
    }

    override fun getSpecifiedTracks(): List<TargetDirectory> =
        File(options.rootDirectory + "/Files").listFiles().map {
            TargetDirectory(
                getPath(DownloadFolder.Stations, "!File (${it.name})"),
                it.readLines().map { SearchQuery(it) })
        }


    private fun getPath(folder: DownloadFolder, subFolderName: String): String {
        return options.rootDirectory + "/" + folder.folderName + "/" + subFolderName
    }
}
