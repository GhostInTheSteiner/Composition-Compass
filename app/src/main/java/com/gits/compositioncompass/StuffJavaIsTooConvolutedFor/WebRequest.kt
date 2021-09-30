package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import kotlinx.coroutines.*
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class WebRequest(
    private val base: String
) {

    private var endpoint: String = ""
    private var parameters: String = ""

    private val enpointUrl: String
        get() = (base + "/" + endpoint).trimEnd('/')

    fun addEndpoint(endpoint: String): WebRequest {
        this.endpoint = endpoint
        return this
    }

    fun addParameters(vararg parameters: String): WebRequest {
        this.parameters = ""

        parameters.forEachIndexed { i, it ->
            if ((i % 2) == 0) this.parameters += it + "="
            else this.parameters += URLEncoder.encode(it, StandardCharsets.UTF_8.toString()) + "&"
        }
        this.parameters = this.parameters.trimEnd('&')
        return this
    }

    suspend fun get(): String {
        val endpointUrl = this.enpointUrl
        return GlobalScope.async(newSingleThreadContext("webrequest-get")) {
           URL(enpointUrl + "?" + parameters)
                .openConnection()
                .getInputStream()
                .bufferedReader()
                .use { it.readText() }
        }.await()
    }
}