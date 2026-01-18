package com.lgloog.moodbox

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.gson.Gson
import okhttp3.*
import java.io.IOException
import java.util.Locale

class MoodFragment : Fragment(), TextToSpeech.OnInitListener {

    private var category: String = "joke"
    private var apiUrl: String = ""

    private lateinit var tvContent: TextView
    private lateinit var btnFav: ImageButton
    private lateinit var btnSpeak: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnRefresh: ImageButton

    private lateinit var db: AppDatabase

    private val client = OkHttpClient()
    private val gson = Gson()
    private lateinit var tts: TextToSpeech
    private var currentText: String = ""

    companion object {
        fun newInstance(type: String): MoodFragment {
            val fragment = MoodFragment()
            val args = Bundle()
            args.putString("TYPE", type)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        category = arguments?.getString("TYPE") ?: "joke"

        apiUrl = when (category) {
            "joke" -> "https://v1.hitokoto.cn/?c=d&encode=json"
            "quote" -> "https://v1.hitokoto.cn/?c=k&encode=json"
            "poetry" -> "https://v1.hitokoto.cn/?c=i&encode=json"
            else -> "https://v1.hitokoto.cn/?encode=json"
        }

        db = AppDatabase.getDatabase(requireContext())
        tts = TextToSpeech(requireContext(), this)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_mood, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvContent = view.findViewById(R.id.tvContent)
        btnFav = view.findViewById(R.id.btnFav)
        btnSpeak = view.findViewById(R.id.btnSpeak)
        btnShare = view.findViewById(R.id.btnShare)
        btnRefresh = view.findViewById(R.id.btnRefresh)

        fetchData()

        btnRefresh.setOnClickListener {
            stopTts()
            fetchData()
        }

        btnSpeak.setOnClickListener {
            if (currentText.isNotEmpty()) {
                if (tts.isSpeaking) {
                    stopTts()
                    Toast.makeText(requireContext(), "已停止", Toast.LENGTH_SHORT).show()
                } else {
                    // 播放前，再次强制停止可能存在的旧语音（双重保险）
                    tts.stop()
                    tts.speak(currentText, TextToSpeech.QUEUE_FLUSH, null, "UniqueID")
                    btnSpeak.setImageResource(android.R.drawable.ic_media_pause)
                }
            }
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                activity?.runOnUiThread {
                    btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                }
            }
            override fun onError(utteranceId: String?) {}
        })

        btnFav.setOnClickListener {
            if (isValidContent()) {
                val existing = db.favDao().findByContent(currentText)
                if (existing != null) {
                    db.favDao().delete(existing)
                    updateFavIcon(false)
                    Toast.makeText(requireContext(), "已取消收藏", Toast.LENGTH_SHORT).show()
                } else {
                    // 在 MoodFragment.kt 的 btnFav 点击事件里：
                    db.favDao().insert(FavRecord(content = currentText, type = category)) // 传入 category
                    updateFavIcon(true)
                    Toast.makeText(requireContext(), "已收藏", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnShare.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, currentText)
            }
            startActivity(Intent.createChooser(intent, "分享"))
        }
    }

    override fun onResume() {
        super.onResume()
        checkFavStatus()
    }

    // 【核心修改】这里改成了 onPause！
    // 当你点击底部栏切换Tab时，旧页面会立即触发 onPause，从而停止播放。
    override fun onPause() {
        super.onPause()
        stopTts()
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    private fun stopTts() {
        if (::tts.isInitialized) { // 这里去掉了 && tts.isSpeaking 判断，强制执行 stop
            tts.stop()
        }
        btnSpeak.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
    }

    private fun fetchData() {
        stopTts()
        tvContent.text = "加载中..."
        updateFavIcon(false)

        val request = Request.Builder().url(apiUrl).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activity?.runOnUiThread { tvContent.text = "网络错误，请检查网络" }
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string()
                try {
                    val res = gson.fromJson(json, ApiResponse::class.java)
                    val text = res.getFinalContent()

                    activity?.runOnUiThread {
                        currentText = text
                        tvContent.text = text
                        checkFavStatus()
                    }
                } catch (e: Exception) { }
            }
        })
    }

    private fun checkFavStatus() {
        if (isValidContent()) {
            val isFav = db.favDao().findByContent(currentText) != null
            updateFavIcon(isFav)
        }
    }

    private fun updateFavIcon(isFav: Boolean) {
        if (isFav) {
            btnFav.setImageResource(android.R.drawable.star_on)
            btnFav.setColorFilter(Color.parseColor("#FFC107"))
        } else {
            btnFav.setImageResource(android.R.drawable.star_off)
            btnFav.setColorFilter(Color.parseColor("#AAAAAA"))
        }
    }

    private fun isValidContent(): Boolean {
        return currentText.isNotEmpty() && !currentText.contains("加载中") && !currentText.contains("错误")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.CHINESE
    }
}