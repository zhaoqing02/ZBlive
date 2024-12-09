import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonSyntaxException
import com.lizongying.mytv0.R
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.Utils.getDateFormat
import com.lizongying.mytv0.data.Source
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.data.TV
import com.lizongying.mytv0.models.EPGXmlParser
import com.lizongying.mytv0.models.Sources
import com.lizongying.mytv0.models.TVGroupModel
import com.lizongying.mytv0.models.TVListModel
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.requests.HttpClient
import com.lizongying.mytv0.showToast
import io.github.lizongying.Gua
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


class MainViewModel : ViewModel() {
    private var timeFormat = if (SP.displaySeconds) "HH:mm:ss" else "HH:mm"

    private lateinit var appDirectory: File
    var listModel: List<TVModel> = listOf()
    val groupModel = TVGroupModel()
    private var cacheFile: File? = null
    private var cacheChannels = ""
    private var initialized = false

    val sources = Sources()

    private val _channelsOk = MutableLiveData<Boolean>()
    val channelsOk: LiveData<Boolean>
        get() = _channelsOk

    fun setDisplaySeconds(displaySeconds: Boolean) {
        timeFormat = if (displaySeconds) "HH:mm:ss" else "HH:mm"
        SP.displaySeconds = displaySeconds
    }

    fun getTime(): String {
        return getDateFormat(timeFormat)
    }

    fun updateEPG() {
        if (!SP.epg.isNullOrEmpty()) {
            viewModelScope.launch {
                updateEPG(SP.epg!!)
            }
        }
    }

    fun updateConfig() {
        if (SP.configAutoLoad) {
            SP.configUrl?.let {
                if (it.startsWith("http")) {
                    viewModelScope.launch {
                        Log.i(TAG, "updateConfig $it")
                        importFromUrl(it)
                        SP.epg?.let { i ->
                            updateEPG(i)
                        }
                    }
                }
            }
        }
    }

    private fun getCache(): String {
        return if (cacheFile!!.exists()) {
            cacheFile!!.readText()
        } else {
            ""
        }
    }

    fun init(context: Context) {
        groupModel.addTVListModel(TVListModel("我的收藏", 0))
        groupModel.addTVListModel(TVListModel("全部頻道", 1))

        appDirectory = context.filesDir
        cacheFile = File(appDirectory, CACHE_FILE_NAME)
        if (!cacheFile!!.exists()) {
            cacheFile!!.createNewFile()
        }

        cacheChannels = getCache()

        if (cacheChannels.isEmpty()) {
            cacheChannels = context.resources.openRawResource(R.raw.channels).bufferedReader()
                .use { it.readText() }
        }

        Log.i(TAG, "cacheChannels $cacheChannels")

        try {
            str2Channels(cacheChannels)
        } catch (e: Exception) {
            e.printStackTrace()
            cacheFile!!.deleteOnExit()
            R.string.channel_read_error.showToast()
        }

        initialized = true

        _channelsOk.value = true
    }

    private suspend fun updateEPG(epg: String) {
        var shouldBreak = false
        val request = okhttp3.Request.Builder().url(epg).build()
        for (i in 0..2) {
            try {
                withContext(Dispatchers.IO) {
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val res = EPGXmlParser().parse(response.body!!.byteStream())

                        withContext(Dispatchers.Main) {
                            for (m in listModel) {
                                val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                                if (name.isEmpty()) {
                                    continue
                                }

                                for ((a, b) in res) {
                                    if (name.contains(a, ignoreCase = true)) {
                                        m.setEpg(b)
                                        if (m.tv.logo.isEmpty()) {
                                            m.tv.logo = "https://live.fanmingming.com/tv/$a.png"
                                        }
                                        break
                                    }
                                }
                            }
                        }

                        shouldBreak = true
                        Log.i(TAG, "EPG success")
                    } else {
                        Log.e(TAG, "EPG ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "EPG request error:", e)
//            R.string.epg_request_err.showToast()
            }

            if (shouldBreak) {
                break
            }
        }

        if (!shouldBreak) {
//            R.string.epg_status_err.showToast()
        }
    }

    private suspend fun importFromUrl(url: String, id: String = "") {
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

        var err = 0
        var shouldBreak = false
        for ((a, b) in urls) {
            Log.i(TAG, "request $a")
            try {
                withContext(Dispatchers.IO) {
                    val request = okhttp3.Request.Builder().url(a).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val str = response.body?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            tryStr2Channels(str, null, b, id)
                        }
                        err = 0
                        shouldBreak = true
                    } else {
                        Log.e(TAG, "Request status ${response.code}")
                        err = R.string.channel_status_error
                    }
                }
            } catch (e: JsonSyntaxException) {
                e.printStackTrace()
                Log.e(TAG, "JSON Parse Error", e)
                err = R.string.channel_format_error
                shouldBreak = true
            } catch (e: NullPointerException) {
                e.printStackTrace()
                Log.e(TAG, "Null Pointer Error", e)
                err = R.string.channel_read_error
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "Request error $e")
                err = R.string.channel_request_error
            }

            if (shouldBreak) break
        }

        if (err != 0) {
            err.showToast()
        }
    }

    fun reset(context: Context) {
        val str = context.resources.openRawResource(R.raw.channels).bufferedReader()
            .use { it.readText() }

        try {
            str2Channels(str)
        } catch (e: Exception) {
            e.printStackTrace()
            R.string.channel_read_error.showToast()
        }
    }

    fun importFromUri(uri: Uri, id: String = "") {
        if (uri.scheme == "file") {
            val file = uri.toFile()
            Log.i(TAG, "file $file")
            val str = if (file.exists()) {
                file.readText()
            } else {
                R.string.file_not_exist.showToast()
                return
            }

            tryStr2Channels(str, file, uri.toString(), id)
        } else {
            viewModelScope.launch {
                importFromUrl(uri.toString(), id)
            }
        }
    }

    fun tryStr2Channels(str: String, file: File?, url: String, id: String = "") {
        try {
            if (str2Channels(str)) {
                cacheFile!!.writeText(str)
                cacheChannels = str
                if (url.isNotEmpty()) {
                    SP.configUrl = url
                    val source = Source(
                        id = id,
                        uri = url
                    )
                    sources.addSource(
                        source
                    )
                }
                _channelsOk.value = true
                R.string.channel_import_success.showToast()
            } else {
                R.string.channel_import_error.showToast()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            file?.deleteOnExit()
            R.string.channel_read_error.showToast()
        }
    }

    private fun str2Channels(str: String): Boolean {
        var string = str
        if (initialized && string == cacheChannels) {
            Log.w(TAG, "same channels")
            return true
        }

        val g = Gua()
        if (g.verify(str)) {
            string = g.decode(str)
        }

        if (string.isEmpty()) {
            Log.w(TAG, "channels is empty")
            return false
        }

        if (initialized && string == cacheChannels) {
            Log.w(TAG, "same channels")
            return true
        }

        val list: List<TV>

        when (string[0]) {
            '[' -> {
                try {
                    val type = object : com.google.gson.reflect.TypeToken<List<TV>>() {}.type
                    list = com.google.gson.Gson().fromJson(string, type)
                    Log.i(TAG, "导入频道 ${list.size}")
                } catch (e: Exception) {
                    Log.i(TAG, "parse error $string")
                    Log.i(TAG, e.message, e)
                    return false
                }
            }

            '#' -> {
                val lines = string.lines()
                val nameRegex = Regex("""tvg-name="([^"]+)"""")
                val logRegex = Regex("""tvg-logo="([^"]+)"""")
                val epgRegex = Regex("""x-tvg-url="([^"]+)"""")
                val groupRegex = Regex("""group-title="([^"]+)"""")

                val l = mutableListOf<TV>()
                val tvMap = mutableMapOf<String, List<TV>>()
                for ((index, line) in lines.withIndex()) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.startsWith("#EXTM3U")) {
                        SP.epg = epgRegex.find(trimmedLine)?.groupValues?.get(1)?.trim()
                    } else if (trimmedLine.startsWith("#EXTINF")) {
                        val info = trimmedLine.split(",")
                        val title = info.last().trim()
                        var name = nameRegex.find(info.first())?.groupValues?.get(1)?.trim()
                        name = name ?: title
                        var group = groupRegex.find(info.first())?.groupValues?.get(1)?.trim()
                        group = group ?: ""
                        val logo = logRegex.find(info.first())?.groupValues?.get(1)?.trim()
                        val uris =
                            if (index + 1 < lines.size) listOf(lines[index + 1].trim()) else emptyList()
                        val tv = TV(
                            -1,
                            name,
                            title,
                            "",
                            logo ?: "",
                            "",
                            uris,
                            mapOf(),
                            group,
                            SourceType.UNKNOWN,
                            listOf(),
                        )

                        if (!tvMap.containsKey(group + name)) {
                            tvMap[group + name] = listOf()
                        }
                        tvMap[group + name] = tvMap[group + name]!! + tv
                    }
                }
                for ((_, tv) in tvMap) {
                    val uris = tv.map { t -> t.uris }.flatten()
                    val t0 = tv[0]
                    val t1 = TV(
                        -1,
                        t0.name,
                        t0.name,
                        "",
                        t0.logo,
                        "",
                        uris,
                        mapOf(),
                        t0.group,
                        SourceType.UNKNOWN,
                        listOf(),
                    )
                    l.add(t1)
                }
                list = l
                Log.i(TAG, "导入频道 ${list.size}")
            }

            else -> {
                val lines = string.lines()
                var group = ""
                val l = mutableListOf<TV>()
                val tvMap = mutableMapOf<String, List<String>>()
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty()) {
                        if (trimmedLine.contains("#genre#")) {
                            group = trimmedLine.split(',', limit = 2)[0].trim()
                        } else {
                            val arr = trimmedLine.split(',').map { it.trim() }
                            val title = arr.first().trim()
                            val uris = arr.drop(1)

                            if (!tvMap.containsKey(group + title)) {
                                tvMap[group + title] = listOf()
                                tvMap[group + title] = tvMap[group + title]!! + group
                            }
                            tvMap[group + title] = tvMap[group + title]!! + uris
                        }
                    }
                }
                for ((title, uris) in tvMap) {
                    val channelGroup = uris.first();
                    uris.drop(1);
                    val tv = TV(
                        -1,
                        "",
                        title.removePrefix(channelGroup),
                        "",
                        "",
                        "",
                        uris,
                        mapOf(),
                        channelGroup,
                        SourceType.UNKNOWN,
                        listOf(),
                    )

                    l.add(tv)
                }
                list = l
                Log.i(TAG, "导入频道 ${list.size}")
            }
        }

        groupModel.initTVGroup()

        val map: MutableMap<String, MutableList<TVModel>> = mutableMapOf()
        for (v in list) {
            if (v.group !in map) {
                map[v.group] = mutableListOf()
            }
            map[v.group]?.add(TVModel(v))
        }

        val listModelNew: MutableList<TVModel> = mutableListOf()
        var groupIndex = 2
        var id = 0
        for ((k, v) in map) {
            val listTVModel = TVListModel(k.ifEmpty { "未知" }, groupIndex)
            for ((listIndex, v1) in v.withIndex()) {
                v1.tv.id = id
                v1.setLike(SP.getLike(id))
                v1.setGroupIndex(groupIndex)
                v1.listIndex = listIndex
                listTVModel.addTVModel(v1)
                listModelNew.add(v1)
                id++
            }
            groupModel.addTVListModel(listTVModel)
            groupIndex++
        }

        listModel = listModelNew

        // 全部频道
        groupModel.tvGroupValue[1].setTVListModel(listModel)

        groupModel.initPosition()
        groupModel.setChange()

        return true
    }

    companion object {
        private const val TAG = "MainViewModel"
        const val CACHE_FILE_NAME = "channels.txt"
    }
}