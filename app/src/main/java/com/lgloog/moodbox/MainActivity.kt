package com.lgloog.moodbox

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 初始化控件
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        val fabHistory = findViewById<FloatingActionButton>(R.id.fabHistory)
        val fabReport = findViewById<FloatingActionButton>(R.id.fabReport)

        fabReport.setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }

        // 2. 设置 ViewPager2 的适配器 (管理 3 个 Fragment)
        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3 // 一共有3页

            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> MoodFragment.newInstance("joke")   // 第1页：笑话
                    1 -> MoodFragment.newInstance("quote")  // 第2页：鸡汤
                    2 -> MoodFragment.newInstance("poetry") // 第3页：古诗
                    else -> MoodFragment.newInstance("joke")
                }
            }
        }

        // 3. 底部导航栏点击事件 -> 切换 ViewPager 到对应页面
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_joke -> viewPager.setCurrentItem(0, true)   // 切到第1页
                R.id.nav_quote -> viewPager.setCurrentItem(1, true)  // 切到第2页
                R.id.nav_poetry -> viewPager.setCurrentItem(2, true) // 切到第3页
            }
            true
        }

        // 4. ViewPager 页面滑动事件 -> 自动选中底部导航栏对应图标
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                bottomNav.menu.getItem(position).isChecked = true
            }
        })

        // 5. 右上角悬浮按钮点击 -> 跳转到收藏历史页面 (FavActivity)
        fabHistory.setOnClickListener {
            val intent = Intent(this, FavActivity::class.java)
            startActivity(intent)
        }
    }
}