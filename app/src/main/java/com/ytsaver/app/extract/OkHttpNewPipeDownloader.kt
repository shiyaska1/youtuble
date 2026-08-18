package com.ytsaver.app.extract

import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NewPipeExtractor needs a concrete HTTP client wired in before it can talk to
 * YouTube; this adapts our OkHttp client to its Downloader contract. Modeled
 * on the reference implementation NewPipe itself ships.
 */
class OkHttpNewPipeDownloader private constructor() : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val dataToSend = request.dataToSend()
        val requestBody = dataToSend?.toRequestBody(null)

        val builder = OkRequest.Builder()
            .method(request.httpMethod(), requestBody)
            .url(request.url())
            .header("User-Agent", USER_AGENT)

        for ((headerName, headerValues) in request.headers()) {
            if (headerValues.isEmpty()) continue
            builder.removeHeader(headerName)
            for (value in headerValues) {
                builder.addHeader(headerName, value)
            }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw IOException("Rate limited (429) while fetching ${request.url()}")
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString()
            )
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        val instance: OkHttpNewPipeDownloader by lazy { OkHttpNewPipeDownloader() }
    }
}
