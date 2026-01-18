package com.lgloog.moodbox

import android.content.Context
import androidx.room.*

// 1. 定义表结构
@Entity(tableName = "fav_table")
data class FavRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val type: String, // 【新增】记录类型：joke, quote, poetry
    val time: Long = System.currentTimeMillis()
)

// 2. 操作接口
@Dao
interface FavDao {
    @Insert
    fun insert(record: FavRecord)

    @Query("SELECT * FROM fav_table")
    fun getAll(): List<FavRecord>

    @Query("SELECT * FROM fav_table WHERE content = :content LIMIT 1")
    fun findByContent(content: String): FavRecord?

    @Delete
    fun delete(record: FavRecord)
}

// 3. 数据库入口
// 【注意看这里！】增加了 exportSchema = false
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