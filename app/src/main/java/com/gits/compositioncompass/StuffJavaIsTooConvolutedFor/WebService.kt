package com.gits.compositioncompass.StuffJavaIsTooConvolutedFor

import kotlinx.coroutines.*
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class WebService(
    private val base: String
) {

    private var endpoint: String = ""
    private val endpointUrl: String
        get() = (base + "/" + endpoint).trimEnd('/')

    fun setEndpoint(endpoint: String): WebService {
        this.endpoint = endpoint
        return this
    }

    fun createRequest() = WebRequest(base, endpointUrl)
}

class WebRequest(
    private val base: String,
    private val endpointUrl: String
) {
    private var parameters: String = ""
    private var errorCondition: (String) -> Boolean = { false }

    fun setParameters(vararg parameters: String): WebRequest {
        this.parameters = ""

        parameters.forEachIndexed { i, it ->
            if ((i % 2) == 0) this.parameters += it + "="
            else this.parameters += URLEncoder.encode(it, StandardCharsets.UTF_8.toString()) + "&"
        }

        this.parameters = this.parameters.trimEnd('&')
        return this
    }

    fun setError(errorCondition: (response: String) -> Boolean ): WebRequest {
        this.errorCondition = errorCondition
        return this
    }

    suspend fun get(): WebRequestResponse {
        val endpointUrl = this.endpointUrl
        return GlobalScope.async(newSingleThreadContext("webrequest-get")) {
            val url = URL(endpointUrl + "?" + parameters)
            val response = url
                .openConnection()
                .getInputStream()
                .bufferedReader()
                .use { it.readText() }

            WebRequestResponse(response, url, errorCondition(response))
        }.await()
    }
}

class WebRequestResponse(
    private val content: String,
    private val url: URL,
    val isError: Boolean
) {
    fun getContent() =
        if (!isError) content else throw WebRequestErrorException(content, url)

    fun getContentOrDefault(default: String) =
        if (!isError) content else default

    override fun toString(): String {
        return content
    }
}

class WebRequestErrorException(val content: String, val url: URL): Exception("'" + url.toString() + "' returned an error: " + System.lineSeparator() + System.lineSeparator() + content)