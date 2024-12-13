package com.lizongying.mytv0


import MainViewModel
import MainViewModel.Companion.CACHE_FILE_NAME
import MainViewModel.Companion.DEFAULT_CHANNELS_FILE
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lizongying.mytv0.data.ReqSettings
import com.lizongying.mytv0.data.ReqSourceAdd
import com.lizongying.mytv0.data.ReqSources
import com.lizongying.mytv0.data.RespSettings
import com.lizongying.mytv0.data.Source
import com.lizongying.mytv0.requests.HttpClient
import fi.iki.elonen.NanoHTTPD
import io.github.lizongying.Gua
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets


class SimpleServer(private val context: Context, private val viewModel: MainViewModel) :
    NanoHTTPD(PORT) {
    private val handler = Handler(Looper.getMainLooper())

    init {
        try {
            start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/api/settings" -> handleSettings()
            "/api/sources" -> handleSources()
            "/api/import-text" -> handleImportFromText(session)
            "/api/import-uri" -> handleImportFromUri(session)
            "/api/proxy" -> handleProxy(session)
            "/api/epg" -> handleEPG(session)
            "/api/channel" -> handleDefaultChannel(session)
            "/api/remove-source" -> handleRemoveSource(session)
            else -> handleStaticContent()
        }
    }

    private fun handleSettings(): Response {
        val response: String
        try {
            val file = File(context.filesDir, CACHE_FILE_NAME)
            var str = if (file.exists()) {
                file.readText()
            } else {
                ""
            }
            if (str.isEmpty()) {
                str = context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
                    .use { it.readText() }
            }

            var history = mutableListOf<Source>()

            if (!SP.sources.isNullOrEmpty()) {
                try {
                    val type = object : TypeToken<List<Source>>() {}.type
                    val sources: List<Source> = Gson().fromJson(SP.sources!!, type)
                    history = sources.toMutableList()
                } catch (e: Exception) {
                    e.printStackTrace()
                    SP.sources = SP.DEFAULT_SOURCES
                }
            }

            val respSettings = RespSettings(
                channelUri = SP.configUrl ?: "",
                channelText = str,
                channelDefault = SP.channel,
                proxy = SP.proxy ?: "",
                epg = SP.epg ?: "",
                history = history
            )
            response = Gson().toJson(respSettings) ?: ""
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", response)
    }

    private suspend fun fetchSources(url: String): String {
        val urls =
            if (url.startsWith("https://raw.githubusercontent.com") || url.startsWith("https://github.com")) {
                listOf(
                    "https://ghp.ci/",
                    "https://gh.llkk.cc/",
                    "https://github.moeyy.xyz/",
                    "https://mirror.ghproxy.com/",
                    "https://ghproxy.cn/",
                    "https://ghproxy.net/",
                    "https://ghproxy.click/",
                    "https://ghproxy.com/",
                    "https://github.moeyy.cn/",
                    "https://gh-proxy.llyke.com/",
                    "https://www.ghproxy.cc/",
                    "https://cf.ghproxy.cc/"
                ).map {
                    Pair("$it$url", url)
                }
            } else {
                listOf(Pair(url, url))
            }

        var sources = ""
        var success = false
        for ((a, b) in urls) {
            Log.i(TAG, "request $a")
            try {
                withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder().url(a).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        sources = response.bodyAlias()?.string() ?: ""
                        success = true
                    } else {
                        Log.e(TAG, "Request status ${response.codeAlias()}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "fetchSources", e)
            }

            if (success) break
        }

        return sources
    }

    private fun handleSources(): Response {
        val response = runBlocking(Dispatchers.IO) {
            fetchSources("https://raw.githubusercontent.com/lizongying/my-tv-0/main/app/src/main/res/raw/sources.txt")
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            Gua().decode(response)
        )
    }

    private fun handleImportFromText(session: IHTTPSession): Response {
        R.string.start_config_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    viewModel.tryStr2Channels(it, null, "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleImportFromUri(session: IHTTPSession): Response {
        R.string.start_config_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                val req = Gson().fromJson(it, ReqSourceAdd::class.java)
                val uri = Uri.parse(req.uri)
                handler.post {
                    viewModel.importFromUri(uri, req.id)
                }
            }
        } catch (e: IOException) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleProxy(session: IHTTPSession): Response {
        try {
            readBody(session)?.let {
                handler.post {
                    val req = Gson().fromJson(it, ReqSettings::class.java)
                    if (req.proxy != null) {
                        SP.proxy = req.proxy
                        R.string.default_proxy_set_success.showToast()
                    } else {
                        R.string.default_proxy_set_failure.showToast()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        val response = ""
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleEPG(session: IHTTPSession): Response {
        try {
            readBody(session)?.let {
                handler.post {
                    val req = Gson().fromJson(it, ReqSettings::class.java)
                    if (req.epg != null) {
                        SP.epg = req.epg
                        R.string.default_epg_set_success.showToast()
                    } else {
                        R.string.default_epg_set_failure.showToast()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        val response = ""
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleDefaultChannel(session: IHTTPSession): Response {
        R.string.start_set_default_channel.showToast()
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    val req = Gson().fromJson(it, ReqSettings::class.java)
                    if (req.channel != null && req.channel > -1) {
                        SP.channel = req.channel
                        R.string.default_channel_set_success.showToast()
                    } else {
                        R.string.default_channel_set_failure.showToast()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun handleRemoveSource(session: IHTTPSession): Response {
        val response = ""
        try {
            readBody(session)?.let {
                handler.post {
                    val req = Gson().fromJson(it, ReqSources::class.java)
                    Log.i(TAG, "req $req")
                    if (req.sourceId.isNotEmpty()) {
                        val res = viewModel.sources.removeSource(req.sourceId)
                        if (res) {
                            Log.i(TAG, "remove source success ${req.sourceId}")
                        } else {
                            Log.i(TAG, "remove source failure ${req.sourceId}")
                        }
                    } else {
                        Log.i(TAG, "remove source failure, sourceId is empty")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                e.message
            )
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", response)
    }

    private fun readBody(session: IHTTPSession): String? {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"]
    }

    private fun handleStaticContent(): Response {
        val html = loadHtmlFromResource(R.raw.index)
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun loadHtmlFromResource(resourceId: Int): String {
        val inputStream = context.resources.openRawResource(resourceId)
        return inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    companion object {
        const val TAG = "SimpleServer"
        const val PORT = 34567
    }
}