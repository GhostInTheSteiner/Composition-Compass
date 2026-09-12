package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import java.io.File

//Thin File wrapper that keeps listFiles() returning LocalFile instances too, so callers
//like PlayerActivity.playFolder() can keep chaining File-style operations.
//
//Previously this class tried to guess a real path from a scoped-storage URI string itself
//(splitting on ":" and assuming everything after it was a path relative to external
//storage - broken for anything but the simplest cases, and never persisted the grant).
//That resolution now happens once, up front, via SafPath - by the time a LocalFile is
//constructed it's already a normal, valid, real absolute path.
class LocalFile(path: String) : File(path) {
    override fun listFiles(): Array<LocalFile> =
        super.listFiles()?.map { LocalFile(it.absolutePath) }?.toTypedArray() ?: arrayOf()
}
