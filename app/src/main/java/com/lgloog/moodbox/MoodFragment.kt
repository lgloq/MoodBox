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
import java.util.Locale
import java.util.Random
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import androidx.core.content.ContextCompat

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
    private var currentTitle: String = ""

    // ================== AI 配置 ==================
    private val AI_API_KEY = "sk-iuzxavusdirvnnpualubkcsjtssrgkjfnotgttwjsyageiyo"
    private val AI_API_URL = "https://api.siliconflow.cn/v1/chat/completions"

    private val AI_MODEL_QWEN = "Qwen/Qwen2.5-7B-Instruct"
    private val AI_MODEL_GLM = "THUDM/glm-4-9b-chat"

    // ================== 本地兜底数据 ==================
    private val localJokes = listOf(
        "今天解决不了的事，别着急，因为明天也解决不了。",
        "失败是成功之母，但成功六亲不认。",
        "我的钱包就像洋葱，每次打开都让我泪流满面。"
    )
    private val localSoups = listOf(
        "生活原本沉闷，但跑起来就有风。",
        "星光不问赶路人，时光不负有心人。",
        "知足且上进，温柔而坚定。"
    )
    private val localPoetry = listOf(
        "《行路难》\n[唐] 李白\n\n长风破浪会有时，\n直挂云帆济沧海。",
        "《定风波》\n[宋] 苏轼\n\n竹杖芒鞋轻胜马，谁怕？\n一蓑烟雨任平生。"
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
                val existing = if (moodType == "poetry" && currentTitle.isNotEmpty()) {
                    dao.findByTitle(currentTitle)
                } else {
                    dao.findByContent(currentText)
                }

                if (existing != null) {
                    dao.delete(existing)
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "已取消收藏", Toast.LENGTH_SHORT).show()
                        updateFavIcon(false)
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
                        updateFavIcon(true)
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
            val dao = db.favDao()
            val existing = if (moodType == "poetry" && currentTitle.isNotEmpty()) {
                dao.findByTitle(currentTitle)
            } else {
                dao.findByContent(currentText)
            }
            activity?.runOnUiThread { updateFavIcon(existing != null) }
        }
    }

    private fun updateFavIcon(isFav: Boolean) {
        val context = context ?: return
        if (isFav) {
            btnFav.setImageResource(android.R.drawable.star_on)
            // 激活状态：使用新加的 star_active (黄色)
            btnFav.setColorFilter(ContextCompat.getColor(context, R.color.star_active))
        } else {
            btnFav.setImageResource(android.R.drawable.star_off)
            // 未激活状态：使用 text_secondary (自适应灰)
            btnFav.setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
        }
    }

    private fun loadDataFromNetwork() {
        val loadingText = when(moodType) {
            "joke" -> "AI 正在创作段子..."
            "poetry" -> "AI 正在寻觅古诗..."
            else -> "正在连接..."
        }
        tvContent.text = loadingText
        updateFavIcon(false)
        btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
        currentTitle = ""

        thread {
            try {
                val content = if (moodType == "joke" || moodType == "poetry") {
                    requestAiContent(moodType)
                } else {
                    requestNormalApi()
                }

                if (content.isBlank()) throw Exception("返回内容为空")

                activity?.runOnUiThread {
                    if (moodType == "poetry") tvContent.textSize = 18f else tvContent.textSize = 22f
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

    private fun requestAiContent(type: String): String {
        // 1. 构造 System Prompt (加入随机性)
        val systemPrompt = if (type == "poetry") {
            // 【核心修改】定义一个丰富的诗人库，强制 AI 每次换人
            val poets = listOf(
                "李白", "杜甫", "苏轼", "王维", "白居易", "李清照", "辛弃疾", "纳兰性德",
                "李商隐", "杜牧", "陆游", "孟浩然", "刘禹锡", "柳宗元", "欧阳修", "陶渊明",
                "王勃", "岑参", "王昌龄", "杨万里"
            )
            val randomPoet = poets.random() // 每次随机抽一个

            """
            你是一个严谨的国学数据库。仅返回JSON数据。
            请推荐一首【$randomPoet】的代表作，或者风格相似的经典古诗。
            
            JSON格式：
            {
              "title": "标题",
              "author": "[朝代] 作者",
              "lines": [
                "第一句，第二句。", 
                "第三句，第四句。"
              ]
            }
            
            严格要求：
            1. 绝对严禁重复诗句！lines数组里每一行必须是独一无二的。
            2. lines数组中，每一项必须包含完整的标点符号（逗号和句号）。
            3. 严禁出现 setVisible, println 等代码！严禁出现英文！
            """.trimIndent()
        } else {
            """
            你是一个幽默大师。仅返回JSON数据。
            JSON格式：{ "qa_list": ["甲：...", "乙：..."] }
            """.trimIndent()
        }

        val targetModel = if (type == "poetry") AI_MODEL_GLM else AI_MODEL_QWEN

        val jsonBody = JSONObject().apply {
            put("model", targetModel)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", "开始") })
            })
            put("temperature", 0.3) // 保持低温，防止乱码
            put("max_tokens", 2048)
            put("stream", false)
        }

        val url = URL(AI_API_URL)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
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

            val jsonStr = rawContent.replace("```json", "").replace("```", "").trim()

            return try {
                val obj = JSONObject(jsonStr)
                if (type == "poetry") {
                    val title = obj.optString("title", "无题")
                    currentTitle = title
                    val author = obj.optString("author", "佚名")
                    val lines = obj.optJSONArray("lines")

                    val sb = StringBuilder()
                    sb.append("《$title》\n$author\n\n")

                    // 手动拼接 + 强制排版 + 清洗
                    val fullPoem = StringBuilder()
                    if (lines != null) {
                        for (i in 0 until lines.length()) {
                            fullPoem.append(lines.getString(i))
                        }
                    }

                    var rawPoem = fullPoem.toString()
                    rawPoem = rawPoem.replace(Regex("[a-zA-Z{}_=]"), "") // 再次清洗代码残留

                    val formattedPoem = rawPoem
                        .replace("。", "。\n")
                        .replace("！", "！\n")
                        .replace("？", "？\n")
                        .replace("\n\n", "\n")

                    sb.append(formattedPoem)
                    sb.toString().trim()
                } else {
                    val list = obj.optJSONArray("qa_list")
                    val sb = StringBuilder()
                    if (list != null) {
                        for (i in 0 until list.length()) {
                            sb.append(list.getString(i)).append("\n")
                        }
                    } else { obj.toString() }
                    sb.toString().trim()
                }
            } catch (e: Exception) {
                rawContent.replace(Regex("[a-zA-Z{}_]"), "")
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
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
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

    // TTS ...
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