package com.lgloog.moodbox

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class FavActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var adapter: FavAdapter

    // 用于临时存储生成的 CSV 文本内容
    private var tempExportContent: String = ""

    // 注册文件保存回调 (SAF框架)
    // 当用户在系统文件管理器中点击“保存”后，系统会回调这里
    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    // 写入 BOM 头 (\uFEFF)，防止 Excel 打开中文乱码
                    val writer = OutputStreamWriter(outputStream, "UTF-8")
                    writer.write("\uFEFF")
                    writer.write(tempExportContent)
                    writer.flush()
                }
                Toast.makeText(this, "导出成功！", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fav)

        db = AppDatabase.getDatabase(this)

        // 1. 初始化 RecyclerView (注意 ID 要和 XML 对应，这里用 recyclerViewFav)
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewFav)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // 获取数据
        val allFavs = db.favDao().getAll().toMutableList()

        // 初始化适配器，传入数据和长按删除逻辑
        adapter = FavAdapter(allFavs) { record, position ->
            showDeleteDialog(record, position)
        }
        recyclerView.adapter = adapter

        // 绑定导出按钮事件
        val fabExport = findViewById<FloatingActionButton>(R.id.fabExport)
        fabExport.setOnClickListener {
            showDateRangePicker()
        }
    }

    // 删除确认弹窗
    private fun showDeleteDialog(record: FavRecord, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("删除收藏")
            .setMessage("确定要删除这条内容吗？")
            .setPositiveButton("删除") { _, _ ->
                // 使用线程操作数据库，防止卡顿
                thread {
                    db.favDao().delete(record)
                    runOnUiThread {
                        adapter.removeItem(position)
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 显示日期范围选择器
    private fun showDateRangePicker() {
        val datePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("选择导出日期范围")
            .setSelection(
                androidx.core.util.Pair(
                    MaterialDatePicker.thisMonthInUtcMilliseconds(),
                    MaterialDatePicker.todayInUtcMilliseconds()
                )
            )
            .setTheme(com.google.android.material.R.style.ThemeOverlay_MaterialComponents_MaterialCalendar)
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
            val startDate = selection.first
            val endDate = selection.second

            if (startDate != null && endDate != null) {
                //DatePicker 返回的是 UTC 0点
                val adjustedEndDate = endDate + 86400000L - 1
                prepareExportData(startDate, adjustedEndDate)
            }
        }
        datePicker.show(supportFragmentManager, "EXPORT_DATE")
    }

    // 查询数据并生成 CSV 内容
    private fun prepareExportData(start: Long, end: Long) {
        thread {
            // 1. 查库
            val list = db.favDao().getFavsByDateRange(start, end)

            if (list.isEmpty()) {
                runOnUiThread { Toast.makeText(this, "该时间段内没有收藏记录", Toast.LENGTH_SHORT).show() }
                return@thread
            }

            // 2. 拼接 CSV 字符串
            val sb = StringBuilder()
            sb.append("ID,类型,内容,收藏时间\n") // 表头

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            for (record in list) {
                // 处理 CSV 转义：如果有双引号，替换成两个双引号，并用引号包裹内容
                val safeContent = record.content.replace("\"", "\"\"")
                val timeStr = dateFormat.format(Date(record.time))

                // 类型转中文
                val typeName = when (record.type) {
                    "joke" -> "笑话"
                    "quote" -> "鸡汤"
                    "poetry" -> "古诗"
                    else -> "其他"
                }

                sb.append("${record.id},${typeName},\"${safeContent}\",${timeStr}\n")
            }

            tempExportContent = sb.toString()

            // 3. 调起系统文件保存框
            runOnUiThread {
                val fileName = "MoodBox_Export_${System.currentTimeMillis()}.csv"
                saveFileLauncher.launch(fileName)
            }
        }
    }
}