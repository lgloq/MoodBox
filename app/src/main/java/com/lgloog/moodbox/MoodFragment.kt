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

    // 模型定义
    private val AI_MODEL_QWEN = "Qwen/Qwen2.5-7B-Instruct" // 写段子用
    private val AI_MODEL_GLM = "THUDM/glm-4-9b-chat"      // 写古诗用 (适合JSON指令)

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
        "《行路难》\n[唐] 李白\n\n长风破浪会有时，\n直挂云帆济沧海。",
        "《定风波》\n[宋] 苏轼\n\n竹杖芒鞋轻胜马，谁怕？\n一蓑烟雨任平生。",
        "《望岳》\n[唐] 杜甫\n\n会当凌绝顶，\n一览众山小。"
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
                        btnFav.setColorFilter(android.graphics.Color.parseColor("#999999"))
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
                        btnFav.setColorFilter(android.graphics.Color.parseColor("#FFC107"))
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
                if (existing != null) {
                    btnFav.setImageResource(android.R.drawable.star_on)
                    btnFav.setColorFilter(android.graphics.Color.parseColor("#FFC107"))
                } else {
                    btnFav.setImageResource(android.R.drawable.star_off)
                    btnFav.setColorFilter(android.graphics.Color.parseColor("#999999"))
                }
            }
        }
    }

    // ================== 网络请求逻辑 ==================

    private fun loadDataFromNetwork() {
        val loadingText = when(moodType) {
            "joke" -> "Qwen 正在创作段子..."
            "poetry" -> "GLM 正在寻觅古诗..."
            else -> "正在连接..."
        }
        tvContent.text = loadingText

        btnFav.setImageResource(android.R.drawable.star_off)
        btnFav.setColorFilter(android.graphics.Color.parseColor("#999999"))
        btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off)

        thread {
            try {
                val content = if (moodType == "joke" || moodType == "poetry") {
                    requestAiContent(moodType)
                } else {
                    requestNormalApi()
                }

                if (content.isBlank()) throw Exception("返回内容为空")

                activity?.runOnUiThread {
                    // 字体控制: 古诗略小(18)以防换行，其他略大(22)
                    if (moodType == "poetry") {
                        tvContent.textSize = 18f
                    } else {
                        tvContent.textSize = 22f
                    }

                    currentText = content
                    tvContent.text = currentText
                    checkFavStatus()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                loadFromLocal("网络开小差了: ${e.message}")
            }
        }
    }

    // 【AI 请求核心方法】 - 强制 JSON 模式
    private fun requestAiContent(type: String): String {
        // 1. 构造 System Prompt (强制 JSON)
        val systemPrompt = if (type == "poetry") {
            """
            你是一个古诗词API。请返回纯JSON格式数据，不要包含任何Markdown标记。
            随机推荐一首中国古代经典诗词（唐诗或宋词），避开《静夜思》等基础诗词。
            JSON格式要求：
            {
              "title": "标题",
              "author": "[朝代] 作者",
              "lines": [
                "第一句，第二句。", 
                "第三句，第四句。"
              ]
            }
            注意：
            1. lines数组中，每一项必须是完整的一联（包含逗号和句号），绝对不要把一句拆成两行。
            2. 杜绝出现代码、英文或乱码。
            """.trimIndent()
        } else {
            // 段子也用 JSON 保持稳定
            """
            你是一个幽默大师。请返回纯JSON格式数据。
            主题：生活/恋爱/搞钱。
            JSON格式要求：
            {
              "qa_list": [
                "甲：[天真的话]",
                "乙：[神回复]"
              ]
            }
            """.trimIndent()
        }

        val targetModel = if (type == "poetry") AI_MODEL_GLM else AI_MODEL_QWEN

        val jsonBody = JSONObject().apply {
            put("model", targetModel)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", "开始") })
            })
            // 【关键】降低温度，防止出现 setVisible 这种幻觉
            put("temperature", 0.6)
            put("max_tokens", 450)
            put("stream", false)
        }

        val url = URL(AI_API_URL)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.setRequestProperty("Authorization", "Bearer $AI_API_KEY")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        OutputStreamWriter(connection.outputStream).use {
            it.write(jsonBody.toString())
            it.flush()
        }

        if (connection.responseCode == 200) {
            val responseText = connection.inputStream.bufferedReader().readText()
            val rawContent = JSONObject(responseText).getJSONArray("choices")
                .getJSONObject(0).getJSONObject("message").getString("content").trim()

            // 【清洗数据】去掉可能存在的 ```json ``` 包裹
            val jsonStr = rawContent.replace("```json", "").replace("```", "").trim()

            // 【解析 JSON 并手动排版】
            return try {
                val obj = JSONObject(jsonStr)
                if (type == "poetry") {
                    val title = obj.optString("title", "无题")
                    val author = obj.optString("author", "佚名")
                    val lines = obj.optJSONArray("lines")
                    val sb = StringBuilder()
                    sb.append("《$title》\n$author\n\n")

                    if (lines != null) {
                        for (i in 0 until lines.length()) {
                            sb.append(lines.getString(i)).append("\n")
                        }
                    }
                    sb.toString().trim()
                } else {
                    // 段子解析
                    val list = obj.optJSONArray("qa_list")
                    val sb = StringBuilder()
                    if (list != null) {
                        for (i in 0 until list.length()) {
                            sb.append(list.getString(i)).append("\n")
                        }
                    } else {
                        // 兼容 fallback
                        obj.toString()
                    }
                    sb.toString().trim()
                }
            } catch (e: Exception) {
                // 如果 JSON 解析挂了，说明 AI 还是返回了纯文本，直接返回文本即可
                rawContent
            }
        } else {
            throw Exception("AI HTTP ${connection.responseCode}")
        }
    }

    private fun requestNormalApi(): String {
        val apiUrl = "https://v1.hitokoto.cn/?c=a&encode=json"
        val url = URL(apiUrl)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Accept-Encoding", "gzip")

        if (connection.responseCode == 200) {
            val encoding = connection.contentEncoding
            val inputStream: InputStream = if (encoding != null && encoding.contains("gzip")) {
                GZIPInputStream(connection.inputStream)
            } else {
                connection.inputStream
            }
            val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) sb.append(line)

            val jsonObject = JSONObject(sb.toString())
            val text = jsonObject.optString("hitokoto", "")
            val from = jsonObject.optString("from", "")
            return if (from.isNotEmpty()) "$text\n—— $from" else text
        } else {
            throw Exception("HTTP ${connection.responseCode}")
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
            if (moodType == "poetry") tvContent.textSize = 18f else tvContent.textSize = 22f
            Toast.makeText(context, reason, Toast.LENGTH_SHORT).show()
        }
    }

    // TTS 部分保持不变...
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
        if (currentText.isEmpty() || currentText.contains("AI") || currentText.contains("正在")) return
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "MoodID")
        try { tts?.speak(currentText, TextToSpeech.QUEUE_FLUSH, params, "MoodID") } catch (e: Exception) {}
    }
    private fun stopTts() {
        try { tts?.stop() } catch (e: Exception) {}
        btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
    }
}