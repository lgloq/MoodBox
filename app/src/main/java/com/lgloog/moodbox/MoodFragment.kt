package com.lgloog.moodbox

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

class MoodFragment : Fragment(), TextToSpeech.OnInitListener {

    private var moodType: String = "joke"
    private lateinit var tvContent: TextView
    private lateinit var btnSpeak: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnFav: ImageButton
    private lateinit var btnShare: ImageButton

    private var tts: TextToSpeech? = null
    private var ttsStatus = 0
    private var currentText = "点击刷新获取内容..."

    // ================== AI 配置 ==================
    private val AI_API_KEY = "sk-iuzxavusdirvnnpualubkcsjtssrgkjfnotgttwjsyageiyo"
    private val AI_API_URL = "https://api.siliconflow.cn/v1/chat/completions"
    private val AI_MODEL = "Qwen/Qwen2.5-7B-Instruct"

    // ================== 本地兜底数据 ==================
    private val localJokes = listOf(
        "今天解决不了的事，别着急，因为明天也解决不了。",
        "失败是成功之母，但成功六亲不认。",
        "我的钱包就像洋葱，每次打开都让我泪流满面。",
        "单身狗别怕，以后单身的日子还长着呢。"
    )
    private val localSoups = listOf(
        "生活原本沉闷，但跑起来就有风。",
        "星光不问赶路人，时光不负有心人。",
        "知足且上进，温柔而坚定。",
        "万物皆有裂痕，那是光照进来的地方。"
    )
    private val localPoetry = listOf(
        "行到水穷处，坐看云起时。",
        "欲把西湖比西子，淡妆浓抹总相宜。",
        "采菊东篱下，悠然见南山。",
        "长风破浪会有时，直挂云帆济沧海。"
    )

    companion object {
        fun newInstance(type: String): MoodFragment {
            val fragment = MoodFragment()
            val args = Bundle()
            args.putString("mood_type", type)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            moodType = it.getString("mood_type") ?: "joke"
        }
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(requireContext().applicationContext, this)
        } catch (e: Exception) {
            ttsStatus = -1
            Log.e("TTS", "TTS构造崩溃", e)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_mood, container, false)
        tvContent = view.findViewById(R.id.tvContent)
        btnSpeak = view.findViewById(R.id.btnSpeak)
        btnRefresh = view.findViewById(R.id.btnRefresh)
        btnFav = view.findViewById(R.id.btnFav)
        btnShare = view.findViewById(R.id.btnShare)

        loadDataFromNetwork()

        btnRefresh.setOnClickListener {
            stopTts()
            loadDataFromNetwork()
        }

        btnSpeak.setOnClickListener {
            when (ttsStatus) {
                1 -> if (tts?.isSpeaking == true) stopTts() else speakOut()
                0 -> {
                    Toast.makeText(requireContext(), "语音引擎启动中...", Toast.LENGTH_SHORT).show()
                    if (tts == null) initTts()
                }
                -1 -> {
                    Toast.makeText(requireContext(), "手机语音引擎故障", Toast.LENGTH_SHORT).show()
                    try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) {}
                }
            }
        }

        btnFav.setOnClickListener {
            if (currentText.isEmpty() || currentText.contains("加载中")) return@setOnClickListener
            thread {
                val db = AppDatabase.getDatabase(requireContext())
                val dao = db.favDao()
                val existing = dao.findByContent(currentText)
                if (existing != null) {
                    dao.delete(existing)
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "已取消收藏", Toast.LENGTH_SHORT).show()
                        btnFav.setImageResource(android.R.drawable.star_off)
                    }
                } else {
                    val newRecord = FavRecord(
                        content = currentText,
                        type = moodType,
                        time = System.currentTimeMillis()
                    )
                    dao.insert(newRecord)
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "已加入收藏", Toast.LENGTH_SHORT).show()
                        btnFav.setImageResource(android.R.drawable.star_on)
                    }
                }
            }
        }

        btnShare.setOnClickListener {
            val shareIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, "【MoodBox】分享给你：\n$currentText")
                type = "text/plain"
            }
            startActivity(Intent.createChooser(shareIntent, "分享到"))
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        checkFavStatus()
    }

    private fun checkFavStatus() {
        if (currentText.isEmpty() || currentText.contains("加载中") || currentText.contains("刷新")) return
        thread {
            val db = AppDatabase.getDatabase(requireContext())
            val existing = db.favDao().findByContent(currentText)
            activity?.runOnUiThread {
                if (existing != null) btnFav.setImageResource(android.R.drawable.star_on)
                else btnFav.setImageResource(android.R.drawable.star_off)
            }
        }
    }

    // ================== 网络请求逻辑 ==================

    private fun loadDataFromNetwork() {
        tvContent.text = if (moodType == "joke") "AI 正在创作段子..." else "正在连接远端星球..."
        btnFav.setImageResource(android.R.drawable.star_off)
        btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off)

        Log.d("MoodBox", "开始请求: type=$moodType")

        thread {
            try {
                // 【防御 3】严格区分通道
                val content = if (moodType == "joke") {
                    requestAiJoke()
                } else {
                    requestNormalApi()
                }

                if (content.isBlank()) throw Exception("返回内容为空")

                activity?.runOnUiThread {
                    currentText = content
                    tvContent.text = currentText
                    checkFavStatus()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                val rawError = e.toString()
                Log.e("MoodBox", "Error: $rawError")

                // 简化报错显示
                val userError = when {
                    rawError.contains("no protocol") -> "API地址配置错误"
                    rawError.contains("timeout") -> "连接超时"
                    rawError.contains("SSL") -> "证书校验失败"
                    else -> "网络异常: ${e.message}"
                }

                loadFromLocal(userError)
            }
        }
    }

    private fun requestAiJoke(): String {
        val scenarios = listOf(
            mapOf("type" to "💘 恋爱清醒拳", "theme" to "谈恋爱、相亲或单身", "style" to "冷酷的情感咨询师"),
            mapOf("type" to "💰 搞钱扎心拳", "theme" to "工资、贫穷或消费主义", "style" to "极度现实的资本家"),
            mapOf("type" to "🤪 弱智逻辑拳", "theme" to "日常生活中的常识", "style" to "脑回路清奇的杠精")
        )
        val selected = scenarios[Random().nextInt(scenarios.size)]
        val systemPrompt = """
            你是一名${selected["style"]}。
            请针对【${selected["theme"]}】创作一个“神回复”段子。
            格式：
            甲：[问题]
            乙：[神回复]
            要求：字数适中，回复不要少于20个字，要有一种好笑的逻辑感。
        """.trimIndent()

        val jsonBody = JSONObject().apply {
            put("model", AI_MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", "来一个！") })
            })
            put("temperature", 1.0)
            put("max_tokens", 300)
            put("stream", false)
        }

        val url = URL(AI_API_URL)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.setRequestProperty("Authorization", "Bearer $AI_API_KEY")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        OutputStreamWriter(connection.outputStream).use {
            it.write(jsonBody.toString())
            it.flush()
        }

        if (connection.responseCode == 200) {
            val responseText = connection.inputStream.bufferedReader().readText()
            return JSONObject(responseText).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content").trim()
        } else {
            throw Exception("AI HTTP ${connection.responseCode}")
        }
    }

    private fun requestNormalApi(): String {
        val apiUrl = getApiUrl(moodType)

        // 【防御 2】如果在请求前发现 URL 是空的，直接拦截抛错，防止崩溃
        if (apiUrl.isEmpty() || !apiUrl.startsWith("http")) {
            throw Exception("无效的API地址: [$moodType] -> '$apiUrl'")
        }

        val url = URL(apiUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        // 伪装 + 压缩支持
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        connection.setRequestProperty("Accept", "application/json, text/plain, */*")
        connection.setRequestProperty("Accept-Encoding", "gzip")

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val encoding = connection.contentEncoding
            val inputStream: InputStream = if (encoding != null && encoding.contains("gzip")) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }

            val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            return parseContent(sb.toString(), moodType)
        } else {
            throw Exception("HTTP $responseCode")
        }
    }

    private fun loadFromLocal(reason: String) {
        val list = when (moodType) {
            "joke" -> localJokes
            "soup" -> localSoups
            "poetry" -> localPoetry
            else -> localSoups
        }
        val randomContent = list[Random().nextInt(list.size)]

        activity?.runOnUiThread {
            currentText = randomContent
            tvContent.text = currentText
            checkFavStatus()
            // 提示具体错误
            Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
        }
    }

    private fun getApiUrl(type: String): String {
        return when (type) {
            "poetry" -> "https://v2.jinrishici.com/one.json"
            "soup" -> "https://v1.hitokoto.cn/?c=a&encode=json"
            // 【防御 1】万一 joke 跑到了这里，返回一个保底 URL，而不是空字符串
            else -> "https://v1.hitokoto.cn/?encode=json"
        }
    }

    private fun parseContent(json: String, type: String): String {
        return try {
            val jsonObject = JSONObject(json)
            when (type) {
                "poetry" -> {
                    val status = jsonObject.optString("status")
                    if (status == "success") {
                        val data = jsonObject.optJSONObject("data")
                        val origin = data?.optJSONObject("origin")
                        val title = origin?.optString("title", "无题")
                        val author = origin?.optString("author", "佚名")
                        val contentArray = origin?.optJSONArray("content")

                        val sb = StringBuilder()
                        if (contentArray != null) {
                            for (i in 0 until contentArray.length()) {
                                var line = contentArray.getString(i)
                                // 【核心优化】
                                // 把逗号和句号后面加上换行符，强制短句换行
                                // 这样“春江潮水连海平，海上明月共潮生”会变成两行，不会尴尬地断开
                                line = line.replace("，", "，\n").replace("。", "。\n")
                                sb.append(line).append("\n") // 每段原本的结尾再加个空行，增加呼吸感
                            }
                        }
                        if (sb.isEmpty()) return data?.optString("content") ?: "暂无诗词"

                        // 拼接标题和作者
                        "《$title》\n$author\n\n${sb.toString().trim()}"
                    } else {
                        throw Exception("Token失效或受限")
                    }
                }

                "soup" -> {
                    val text = jsonObject.optString("hitokoto", "")
                    val from = jsonObject.optString("from", "")
                    if (from.isNotEmpty() && from != "null") "$text\n—— $from" else text
                }
                // 保底解析
                else -> jsonObject.optString("hitokoto", "解析失败")
            }
        } catch (e: Exception) {
            throw Exception("JSON解析失败: ${e.message}")
        }
    }

    // ... TTS 保持不变 ...
    override fun onPause() { super.onPause(); stopTts() }
    override fun onDestroy() { if (tts != null) { tts?.stop(); tts?.shutdown() }; super.onDestroy() }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            var result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = tts?.setLanguage(Locale.CHINESE)
            }
            ttsStatus = 1
            setupProgressListener()
        } else {
            ttsStatus = -1
        }
    }
    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { activity?.runOnUiThread { btnSpeak.setImageResource(android.R.drawable.ic_media_pause) } }
            override fun onDone(utteranceId: String?) { activity?.runOnUiThread { btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off) } }
            override fun onError(utteranceId: String?) { activity?.runOnUiThread { btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off) } }
        })
    }
    private fun speakOut() {
        if (currentText.isEmpty() || currentText.contains("加载中")) return
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "MoodID")
        try { tts?.speak(currentText, TextToSpeech.QUEUE_FLUSH, params, "MoodID") } catch (e: Exception) {}
    }
    private fun stopTts() {
        try { tts?.stop() } catch (e: Exception) {}
        btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
    }
}