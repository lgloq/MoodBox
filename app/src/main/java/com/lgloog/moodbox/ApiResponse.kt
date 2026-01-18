package com.lgloog.moodbox

// 这是一个“数据类”，用来装网络返回的 JSON 数据
data class ApiResponse(
    val code: Int? = null,
    val content: String? = null,
    val text: String? = null,
    val hitokoto: String? = null
) {
    // 自动判断哪个字段有值，就返回哪个
    fun getFinalContent(): String {
        return content ?: text ?: hitokoto ?: "获取内容失败"
    }
}