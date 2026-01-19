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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.Random
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

    // ================== 本地兜底数据 (万一连 Hitokoto 都挂了) ==================
    private val localJokes = listOf(
        "今天解决不了的事，别着急，因为明天也解决不了。",
        "失败是成功之母，但成功六亲不认。",
        "我的钱包就像洋葱，每次打开都让我泪流满面。",
        "单身狗别怕，以后单身的日子还长着呢。",
        "虽然你长得丑，但是你想得美啊！"
    )
    private val localSoups = listOf(
        "生活原本沉闷，但跑起来就有风。",
        "星光不问赶路人，时光不负有心人。",
        "热爱可抵岁月漫长。",
        "满地都是六便士，他却抬头看见了月亮。",
        "知足且上进，温柔而坚定。"
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

    // ================== 网络请求 (新接口) ==================

    private fun loadDataFromNetwork() {
        tvContent.text = "加载中..."
        btnFav.setImageResource(android.R.drawable.star_off)
        btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off)

        thread {
            try {
                // 1. 请求
                val apiUrl = getApiUrl(moodType)
                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                // 伪装成浏览器，防止被拦截
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")

                if (connection.responseCode == 200) {
                    val jsonText = url.readText()
                    // 2. 解析
                    val content = parseContent(jsonText, moodType)

                    if (content.isBlank()) throw Exception("Empty Content")

                    activity?.runOnUiThread {
                        currentText = content
                        tvContent.text = currentText
                        checkFavStatus()
                    }
                } else {
                    throw Exception("HTTP ${connection.responseCode}")
                }
                connection.disconnect()

            } catch (e: Exception) {
                // 失败就用本地数据
                e.printStackTrace()
                loadFromLocal()
            }
        }
    }

    private fun loadFromLocal() {
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
        }
    }

    // 【关键修改 1】目前最稳的接口地址
    private fun getApiUrl(type: String): String {
        return when (type) {
            "poetry" -> "https://v1.jinrishici.com/all.json"

            // 鸡汤 -> Hitokoto (一言)，非常稳
            "soup" -> "https://v1.hitokoto.cn/?encode=json"

            // 笑话 -> 韩小韩 API (VVHan)，国内源，JSON 格式
            "joke" -> "https://api.vvhan.com/api/text/joke?type=json"

            else -> "https://v1.hitokoto.cn/?encode=json"
        }
    }

    // 【关键修改 2】针对新接口的解析逻辑
    private fun parseContent(json: String, type: String): String {
        return try {
            val jsonObject = JSONObject(json)
            when (type) {
                "poetry" -> jsonObject.optString("content", "")

                // Hitokoto 结构: {"hitokoto": "内容", "from": "来源"}
                "soup" -> {
                    val text = jsonObject.optString("hitokoto", "")
                    val from = jsonObject.optString("from", "")
                    if (from.isNotEmpty() && from != "null") "$text —— $from" else text
                }

                // VVHan 结构: {"success": true, "data": {"content": "..."}}
                "joke" -> {
                    val data = jsonObject.optJSONObject("data")
                    data?.optString("content") ?: ""
                }
                else -> ""
            }
        } catch (e: Exception) {
            throw e
        }
    }

    // ================== TTS & Lifecycle ==================

    override fun onPause() {
        super.onPause()
        stopTts()
    }

    override fun onDestroy() {
        if (tts != null) {
            tts?.stop()
            tts?.shutdown()
        }
        super.onDestroy()
    }

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
            override fun onStart(utteranceId: String?) {
                activity?.runOnUiThread { btnSpeak.setImageResource(android.R.drawable.ic_media_pause) }
            }
            override fun onDone(utteranceId: String?) {
                activity?.runOnUiThread { btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off) }
            }
            override fun onError(utteranceId: String?) {
                activity?.runOnUiThread { btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off) }
            }
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