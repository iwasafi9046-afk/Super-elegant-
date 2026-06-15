package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorites")
data class FavoriteApp(
    @PrimaryKey val packageName: String,
    val label: String,
    val addedTime: Long = System.currentTimeMillis()
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY label ASC")
    fun getAllFavorites(): Flow<List<FavoriteApp>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(app: FavoriteApp)

    @Query("DELETE FROM favorites WHERE packageName = :packageName")
    suspend fun deleteByPackageName(packageName: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE packageName = :packageName LIMIT 1)")
    suspend fun isFavorite(packageName: String): Boolean
}

@Database(entities = [FavoriteApp::class], version = 1, exportSchema = false)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile
        private var INSTANCE: LauncherDatabase? = null

        fun getDatabase(context: Context): LauncherDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LauncherDatabase::class.java,
                    "draco_launcher_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class FavoriteRepository(private val favoriteDao: FavoriteDao) {
    val allFavorites: Flow<List<FavoriteApp>> = favoriteDao.getAllFavorites()

    suspend fun insert(app: FavoriteApp) {
        favoriteDao.insertFavorite(app)
    }

    suspend fun delete(packageName: String) {
        favoriteDao.deleteByPackageName(packageName)
    }

    suspend fun isFavorite(packageName: String): Boolean {
        return favoriteDao.isFavorite(packageName)
    }
}
