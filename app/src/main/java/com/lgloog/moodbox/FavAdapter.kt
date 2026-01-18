package com.lgloog.moodbox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class FavAdapter(
    private val list: MutableList<FavRecord>,
    private val onLongClick: (FavRecord, Int) -> Unit
) : RecyclerView.Adapter<FavAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvContent: TextView = v.findViewById(R.id.tvFavContent)
        val tvTime: TextView = v.findViewById(R.id.tvFavTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        // 这里引用的是 item_fav.xml
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_fav, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val record = list[position]

        // 1. 设置内容
        holder.tvContent.text = record.content

        // 2. 获取当前的时间字符串
        val currentTimeStr = getSmartDate(record.time)
        holder.tvTime.text = currentTimeStr

        // 3. 时间去重逻辑 (和上一条对比)
        if (position > 0) {
            val prevRecord = list[position - 1]
            val prevTimeStr = getSmartDate(prevRecord.time)

            if (currentTimeStr == prevTimeStr) {
                // 如果时间完全一样，就隐藏
                holder.tvTime.visibility = View.GONE
            } else {
                holder.tvTime.visibility = View.VISIBLE
            }
        } else {
            // 第一条永远显示
            holder.tvTime.visibility = View.VISIBLE
        }

        // 长按事件
        holder.itemView.setOnLongClickListener {
            onLongClick(record, position)
            true
        }
    }

    override fun getItemCount() = list.size

    fun removeItem(position: Int) {
        list.removeAt(position)
        notifyDataSetChanged() // 刷新列表，以更新时间的显示状态
    }

    // --- 【仿微信】智能时间格式化 ---
    private fun getSmartDate(timeInMillis: Long): String {
        val now = Calendar.getInstance()
        val record = Calendar.getInstance()
        record.timeInMillis = timeInMillis

        // 1. 判断是否是同一年
        val isSameYear = now.get(Calendar.YEAR) == record.get(Calendar.YEAR)

        // 2. 判断是否是同一天 (今天)
        val isSameDay = isSameYear && (now.get(Calendar.DAY_OF_YEAR) == record.get(Calendar.DAY_OF_YEAR))
        if (isSameDay) {
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(record.time)
        }

        // 3. 判断是否是昨天
        // 方法：把“今天”减去1天，看看是不是和记录那天一样
        val yesterday = Calendar.getInstance()
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        val isYesterday = (yesterday.get(Calendar.YEAR) == record.get(Calendar.YEAR)) &&
                (yesterday.get(Calendar.DAY_OF_YEAR) == record.get(Calendar.DAY_OF_YEAR))

        if (isYesterday) {
            val timePart = SimpleDateFormat("HH:mm", Locale.getDefault()).format(record.time)
            return "昨天 $timePart"
        }

        // 4. 判断是否是同一周 (本周)
        // 注意：这里简单判定为“同一年的第几周相同”
        val isSameWeek = isSameYear && (now.get(Calendar.WEEK_OF_YEAR) == record.get(Calendar.WEEK_OF_YEAR))

        if (isSameWeek) {
            // "EEE" 在中文环境下会显示 "周几" (例如：周五)
            // 如果想要强制中文，可以用 Locale.CHINA
            return SimpleDateFormat("EEE HH:mm", Locale.CHINA).format(record.time)
        }

        // 5. 剩下的情况 (今年/往年)
        val formatPattern = if (isSameYear) {
            "MM/dd HH:mm"        // 今年其他时间：05/20 14:30
        } else {
            "yyyy/MM/dd HH:mm"   // 往年：2025/01/01 14:30
        }

        return SimpleDateFormat(formatPattern, Locale.getDefault()).format(record.time)
    }
}