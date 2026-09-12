package com.gits.compositioncompass.Queries

import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.SafStorage
import DownloadFolder
import Fields
import QueryMode
import com.gits.compositioncompass.Models.SearchQuery
import com.gits.compositioncompass.Models.TargetDirectory

class FileQuery(val options: CompositionCompassOptions, val storage: SafStorage) : IFileQuery {
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
        storage.listFiles("Files").mapNotNull { it.name }.map { name ->
            TargetDirectory(
                getPath(DownloadFolder.Stations, "!File ($name)"),
                storage.readLines("Files", name).map { SearchQuery(track = it) })
        }


    private fun getPath(folder: DownloadFolder, subFolderName: String): String {
        return options.rootDirectoryPath + "/" + folder.folderName + "/" + subFolderName
    }
}

