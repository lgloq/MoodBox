package com.lgloog.moodbox

import android.content.Context
import androidx.room.*

// 1. 定义表结构
@Entity(tableName = "fav_table")
data class FavRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val type: String, // 记录类型：joke, quote, poetry
    val time: Long = System.currentTimeMillis()
)

// 2. 操作接口
@Dao
interface FavDao {
    @Insert
    fun insert(record: FavRecord)

    @Query("SELECT * FROM fav_table ORDER BY time DESC")
    fun getAll(): List<FavRecord>

    @Query("SELECT * FROM fav_table WHERE content = :content LIMIT 1")
    fun findByContent(content: String): FavRecord?

    // 使用 SQL 的 LIKE 语句，%标题%
    @Query("SELECT * FROM fav_table WHERE content LIKE '%' || :title || '%' LIMIT 1")
    fun findByTitle(title: String): FavRecord?

    @Delete
    fun delete(record: FavRecord)

    //根据时间范围查询 (start 和 end 是毫秒时间戳)
    @Query("SELECT * FROM fav_table WHERE time BETWEEN :start AND :end ORDER BY time DESC")
    fun getFavsByDateRange(start: Long, end: Long): List<FavRecord>
}

// 3. 数据库入口
@Database(entities = [FavRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favDao(): FavDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moodbox_db"
                )
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}