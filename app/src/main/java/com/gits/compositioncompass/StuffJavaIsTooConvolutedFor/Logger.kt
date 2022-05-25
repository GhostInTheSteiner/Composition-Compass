package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import com.gits.compositioncompass.Configuration.CompositionCompassOptions
import java.io.File

class Logger(private val options: CompositionCompassOptions, private val notifier: Notifier) {

    private val file: File = File(options.rootDirectory  + "/" + options.logName)

    init {
        if (!file.exists())
            file.createNewFile()
    }

    fun error(e: Exception) {
        notifier.post(e)
        file.appendText(e.toString() + "\n\n" +  e.stackTraceToString() + "\n\n")
    }
}