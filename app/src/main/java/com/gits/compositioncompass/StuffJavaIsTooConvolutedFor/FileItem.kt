package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import android.os.Build
import android.os.Environment
import com.gits.compositioncompass.BuildConfig
import java.io.File

class LocalFile(path: String) : File(getPath(path)) {

    var originalPath: String = ""

    init {
        originalPath = path
    }

    override fun listFiles(): Array<LocalFile> {
        return super.listFiles().map { LocalFile(it.absolutePath) }.toTypedArray()
    }

    companion object {
        fun getPath(path: String) : String {
            val parts = path.split(":")

            if (parts.count() > 1)
                // scoped storage path
                return Environment.getExternalStorageDirectory().absolutePath + "/" + parts.last()

            else
                // legacy storage path
                return path
        }
    }
}