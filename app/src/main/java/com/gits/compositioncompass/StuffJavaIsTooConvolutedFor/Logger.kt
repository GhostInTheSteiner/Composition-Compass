package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import com.gits.compositioncompass.Configuration.CompositionCompassOptions

class Logger(private val options: CompositionCompassOptions, private val storage: SafStorage, val notifier: Notifier) {

    fun error(e: Exception) {
        notifier.post(e)
        storage.appendText(options.rootDirectoryPath, options.logName, e.toString() + "\n\n" + e.stackTraceToString() + "\n\n")
    }

    fun warn(e: Exception) {
        storage.appendText(options.rootDirectoryPath, options.logName, e.toString() + "\n\n" + e.stackTraceToString() + "\n\n")
    }
}
