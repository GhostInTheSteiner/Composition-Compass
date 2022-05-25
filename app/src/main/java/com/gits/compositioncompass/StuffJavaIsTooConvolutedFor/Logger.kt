package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import java.io.File

class Logger(private val notifier: Notifier, private val file: File) {

    init {
        if (!file.exists())
            file.createNewFile()
    }

    fun error(e: Exception) {
        notifier.post(e)
        file.appendText(e.toString() + "\n\n" +  e.stackTraceToString() + "\n\n")
    }
}