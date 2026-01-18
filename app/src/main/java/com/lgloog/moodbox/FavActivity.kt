package com.lgloog.moodbox

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FavActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fav) // 确保你的布局文件叫 activity_fav.xml

        val recyclerView = findViewById<RecyclerView>(R.id.rvFav)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val db = AppDatabase.getDatabase(this)
        // 获取所有数据并转为 MutableList，方便删除
        val allFavs = db.favDao().getAll().toMutableList()

        // 初始化适配器，传入数据和长按删除逻辑
        val adapter = FavAdapter(allFavs) { record, position ->
            // 弹出删除确认框
            showDeleteDialog(record, position, db, recyclerView.adapter as FavAdapter)
        }

        recyclerView.adapter = adapter
    }

    private fun showDeleteDialog(record: FavRecord, position: Int, db: AppDatabase, adapter: FavAdapter) {
        AlertDialog.Builder(this)
            .setTitle("删除收藏")
            .setMessage("确定要删除这条内容吗？")
            .setPositiveButton("删除") { _, _ ->
                // 1. 删数据库
                db.favDao().delete(record)
                // 2. 删界面
                adapter.removeItem(position)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}