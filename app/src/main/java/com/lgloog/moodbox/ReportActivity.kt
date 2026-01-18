package com.lgloog.moodbox

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        val pieChart = findViewById<PieChart>(R.id.pieChart)
        val barChart = findViewById<BarChart>(R.id.barChart)
        val btnShare = findViewById<Button>(R.id.btnShareReport)
        val layoutReport = findViewById<View>(R.id.layoutReport)

        val db = AppDatabase.getDatabase(this)
        val allFavs = db.favDao().getAll()

        if (allFavs.isEmpty()) {
            Toast.makeText(this, "暂无数据，快去收藏一些吧！", Toast.LENGTH_SHORT).show()
        }

        // 1. 设置饼图
        setupPieChart(pieChart, allFavs)

        // 2. 设置柱状图
        setupBarChart(barChart, allFavs)

        // 3. 分享截图
        btnShare.setOnClickListener {
            shareReportImage(layoutReport)
        }
    }

    private fun setupPieChart(chart: PieChart, list: List<FavRecord>) {
        val jokeCount = list.count { it.type == "joke" }
        val quoteCount = list.count { it.type == "quote" }
        val poetryCount = list.count { it.type == "poetry" }

        val entries = ArrayList<PieEntry>()
        if (jokeCount > 0) entries.add(PieEntry(jokeCount.toFloat(), "开心"))
        if (quoteCount > 0) entries.add(PieEntry(quoteCount.toFloat(), "思考"))
        if (poetryCount > 0) entries.add(PieEntry(poetryCount.toFloat(), "风雅"))

        if (entries.isEmpty()) entries.add(PieEntry(1f, "暂无数据"))

        val dataSet = PieDataSet(entries, "")
        dataSet.colors = listOf(
            Color.parseColor("#FFC107"), // 黄
            Color.parseColor("#4CAF50"), // 绿
            Color.parseColor("#6200EE"), // 紫
            Color.LTGRAY
        )

        // 【关键】获取适配深色模式的文字颜色
        val textColor = getThemeColor(R.color.chart_text)
        val cardColor = getThemeColor(R.color.chart_card_bg)

        dataSet.valueTextColor = Color.WHITE // 饼图内部的文字一般保持白色看得清
        dataSet.valueTextSize = 14f

        chart.data = PieData(dataSet)
        chart.description.isEnabled = false
        chart.centerText = "收藏总数\n${list.size}"
        chart.setCenterTextSize(16f)

        // 【关键】中间的洞颜色必须和卡片背景一致，否则很难看
        chart.setHoleColor(cardColor)
        // 【关键】中间文字的颜色随系统变
        chart.setCenterTextColor(textColor)

        // 设置图例颜色
        chart.legend.textColor = textColor

        chart.animateY(1000)
        chart.invalidate()
    }

    private fun setupBarChart(chart: BarChart, list: List<FavRecord>) {
        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()
        val calendar = Calendar.getInstance()

        calendar.add(Calendar.DAY_OF_YEAR, -6)

        for (i in 0..6) {
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            val startOfDay = calendar.timeInMillis

            calendar.set(Calendar.HOUR_OF_DAY, 23)
            calendar.set(Calendar.MINUTE, 59)
            calendar.set(Calendar.SECOND, 59)
            val endOfDay = calendar.timeInMillis

            val count = list.count { it.time in startOfDay..endOfDay }
            entries.add(BarEntry(i.toFloat(), count.toFloat()))

            val dayFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
            labels.add(dayFormat.format(calendar.time))
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        // 【关键】获取当前主题颜色
        val textColor = getThemeColor(R.color.chart_text)

        val dataSet = BarDataSet(entries, "每日收藏数")
        dataSet.color = Color.parseColor("#6200EE")
        dataSet.valueTextColor = textColor // 柱子顶上的数字颜色
        dataSet.valueTextSize = 12f

        val data = BarData(dataSet)
        data.barWidth = 0.5f

        chart.data = data
        chart.description.isEnabled = false
        chart.legend.isEnabled = false

        val xAxis = chart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        xAxis.textColor = textColor // 【关键】X轴标签颜色
        xAxis.gridColor = textColor // X轴网格线颜色
        xAxis.setDrawGridLines(false)

        chart.axisRight.isEnabled = false
        chart.axisLeft.textColor = textColor // 【关键】Y轴标签颜色
        chart.axisLeft.axisMinimum = 0f
        if (list.isEmpty()) chart.axisLeft.axisMaximum = 5f

        chart.animateY(1000)
        chart.invalidate()
    }

    // --- 【工具方法】自动获取当前资源里的颜色值 ---
    private fun getThemeColor(colorResId: Int): Int {
        return ContextCompat.getColor(this, colorResId)
    }

    private fun shareReportImage(view: View) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 【关键】截图的背景色也要根据当前主题来，不能写死 White
        val bgColor = getThemeColor(R.color.chart_card_bg)
        canvas.drawColor(bgColor)

        view.draw(canvas)

        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, bytes)
        val path = MediaStore.Images.Media.insertImage(contentResolver, bitmap, "MoodReport", null)

        if (path != null) {
            val uri = Uri.parse(path)
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "image/jpeg"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            startActivity(Intent.createChooser(intent, "分享我的心情报告"))
        } else {
            Toast.makeText(this, "保存图片失败", Toast.LENGTH_SHORT).show()
        }
    }
}