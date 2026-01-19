package com.lgloog.moodbox

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    // 【修改】将 ViewPager 提升为成员变量，这样 Launcher 才能访问并控制它
    private lateinit var viewPager: ViewPager2

    // 定义一个全局的 Toast 变量，用于防堆积
    private var mToast: Toast? = null

    // 封装一个防堆积的 Toast 显示方法
    private fun showToast(message: String) {
        // 如果当前有 Toast 在显示，先取消它
        mToast?.cancel()
        // 创建新的 Toast
        mToast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        mToast?.show()
    }

    // 【新增】注册结果回调 (替代旧的 onActivityResult)
    // 这里的逻辑是：当 FaceDetectActivity 关闭并传回数据时，自动执行这里
    private val faceDetectLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            // 获取 AI 返回的心情类型 (joke/soup/poetry)
            val moodType = result.data?.getStringExtra("mood_result")

            // 根据心情自动切换页面，并弹出提示
            when (moodType) {
                "joke" -> {
                    // 切换到第 1 页 (笑话)，false 表示不带滑动动画，直接跳
                    viewPager.setCurrentItem(0, false)
                    showToast("检测到心情低落，讲个笑话开心一下！")
                    //Toast.makeText(this, "检测到心情低落，讲个笑话开心一下！", Toast.LENGTH_LONG).show()
                }
                "soup" -> {
                    // 切换到第 2 页 (鸡汤)
                    viewPager.setCurrentItem(1, false)
                    showToast("心情平静，来喝碗鸡汤~")
                    //Toast.makeText(this, "心情平静，来喝碗鸡汤~", Toast.LENGTH_LONG).show()
                }
                "poetry" -> {
                    // 切换到第 3 页 (古诗)
                    viewPager.setCurrentItem(2, false)
                    showToast("笑容灿烂，唯有古诗配得上你！")
                    //Toast.makeText(this, "笑容灿烂，唯有古诗配得上你！", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 初始化控件
        viewPager = findViewById(R.id.viewPager) // 这里赋值给成员变量
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        val fabHistory = findViewById<FloatingActionButton>(R.id.fabHistory)
        val fabReport = findViewById<FloatingActionButton>(R.id.fabReport)

        // 报表按钮点击
        fabReport.setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }

        // 2. 设置 ViewPager2 的适配器
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3

            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> MoodFragment.newInstance("joke")
                    1 -> MoodFragment.newInstance("quote")
                    2 -> MoodFragment.newInstance("poetry")
                    else -> MoodFragment.newInstance("joke")
                }
            }
        }

        // 3. 底部导航栏点击事件
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_joke -> {
                    viewPager.setCurrentItem(0, false)
                    true
                }
                R.id.nav_soup -> {
                    viewPager.setCurrentItem(1, false)
                    true
                }
                R.id.nav_poetry -> {
                    viewPager.setCurrentItem(2, false)
                    true
                }
                // 【修改】如果是“测心情”，使用 launcher 启动，以便接收返回值
                R.id.nav_face_detect -> {
                    val intent = Intent(this, FaceDetectActivity::class.java)
                    // 使用 launch 替代 startActivity
                    faceDetectLauncher.launch(intent)

                    // 返回 false，保持底栏选中状态在原来的位置
                    false
                }
                else -> false
            }
        }

        // 4. ViewPager 页面滑动联动底栏
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // 防止越界（因为 menu 只有3项，而 ViewPager 有3页，对应索引 0,1,2，是安全的）
                if (position < bottomNav.menu.size()) {
                    bottomNav.menu.getItem(position).isChecked = true
                }
            }
        })

        // 5. 历史记录按钮
        fabHistory.setOnClickListener {
            val intent = Intent(this, FavActivity::class.java)
            startActivity(intent)
        }
    }
}