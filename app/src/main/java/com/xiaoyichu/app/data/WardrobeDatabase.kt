package com.xiaoyichu.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "garments")
data class Garment(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    val originalPath: String,
    val thumbnailPath: String,
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val cutoutPath: String? = null,
    val cropPath: String? = null,
    @ColumnInfo(defaultValue = "0.0") val cropLeft: Float = 0f,
    @ColumnInfo(defaultValue = "0.0") val cropTop: Float = 0f,
    @ColumnInfo(defaultValue = "1.0") val cropRight: Float = 1f,
    @ColumnInfo(defaultValue = "1.0") val cropBottom: Float = 1f,
    @ColumnInfo(defaultValue = "''") val color:String="",
    @ColumnInfo(defaultValue = "''") val season:String="",
    @ColumnInfo(defaultValue = "''") val occasion:String="",
    val deletedAt:Long?=null
)

@Dao
interface GarmentDao {
    @Query("SELECT * FROM garments ORDER BY id") suspend fun snapshot(): List<Garment>
    @Query("SELECT * FROM garments ORDER BY createdAt DESC, id ASC")
    fun observeAll(): Flow<List<Garment>>

    @Insert
    suspend fun insert(garment: Garment)

    @Query("UPDATE garments SET name = :name, category = :category, updatedAt = :updatedAt, color=:color,season=:season,occasion=:occasion WHERE id = :id")
    suspend fun updateDetails(id: String, name: String, category: String, updatedAt: Long,color:String,season:String,occasion:String)

    @Query("UPDATE garments SET favorite = :favorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, updatedAt: Long)

    @Query("DELETE FROM garments WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [Garment::class, Outfit::class, OutfitItem::class, CalendarEntry::class, RestoredBackup::class], version = 6, exportSchema = true)
abstract class WardrobeDatabase : RoomDatabase() {
    abstract fun garments(): GarmentDao
    abstract fun outfits(): OutfitDao
    abstract fun tools(): WardrobeToolsDao

    companion object {
        @Volatile private var instance: WardrobeDatabase? = null
        fun get(context: Context): WardrobeDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WardrobeDatabase::class.java, "wardrobe.db")
                .addMigrations(object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE garments ADD COLUMN cutoutPath TEXT")
                    }
                })
                .addMigrations(object : Migration(2, 3) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE garments ADD COLUMN cropPath TEXT")
                        db.execSQL("ALTER TABLE garments ADD COLUMN cropLeft REAL NOT NULL DEFAULT 0.0")
                        db.execSQL("ALTER TABLE garments ADD COLUMN cropTop REAL NOT NULL DEFAULT 0.0")
                        db.execSQL("ALTER TABLE garments ADD COLUMN cropRight REAL NOT NULL DEFAULT 1.0")
                        db.execSQL("ALTER TABLE garments ADD COLUMN cropBottom REAL NOT NULL DEFAULT 1.0")
                    }
                })
                .addMigrations(object : Migration(3, 4) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS outfits (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS outfit_items (id TEXT NOT NULL PRIMARY KEY, outfitId TEXT NOT NULL, garmentId TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, width REAL NOT NULL, rotation REAL NOT NULL, zIndex INTEGER NOT NULL, FOREIGN KEY(outfitId) REFERENCES outfits(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(garmentId) REFERENCES garments(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_outfit_items_outfitId ON outfit_items(outfitId)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_outfit_items_garmentId ON outfit_items(garmentId)")
                    }
                })
                .addMigrations(object : Migration(4, 5) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE outfits ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE outfits ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("ALTER TABLE outfits ADD COLUMN todayDate TEXT")
                        db.execSQL("ALTER TABLE outfit_items ADD COLUMN cropLeft REAL NOT NULL DEFAULT 0.0")
                        db.execSQL("ALTER TABLE outfit_items ADD COLUMN cropTop REAL NOT NULL DEFAULT 0.0")
                        db.execSQL("ALTER TABLE outfit_items ADD COLUMN cropRight REAL NOT NULL DEFAULT 1.0")
                        db.execSQL("ALTER TABLE outfit_items ADD COLUMN cropBottom REAL NOT NULL DEFAULT 1.0")
                        db.execSQL("ALTER TABLE outfit_items ADD COLUMN filter TEXT NOT NULL DEFAULT 'original'")
                    }
                })
                .addMigrations(object : Migration(5, 6) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE garments ADD COLUMN color TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE garments ADD COLUMN season TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE garments ADD COLUMN occasion TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE garments ADD COLUMN deletedAt INTEGER")
                        db.execSQL("ALTER TABLE outfits ADD COLUMN collectionName TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE outfits ADD COLUMN deletedAt INTEGER")
                        db.execSQL("CREATE TABLE IF NOT EXISTS calendar_entries (date TEXT NOT NULL PRIMARY KEY, outfitId TEXT NOT NULL, worn INTEGER NOT NULL, FOREIGN KEY(outfitId) REFERENCES outfits(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_entries_outfitId ON calendar_entries(outfitId)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS restored_backups (fingerprint TEXT NOT NULL PRIMARY KEY, restoredAt INTEGER NOT NULL)")
                        db.execSQL("INSERT OR IGNORE INTO calendar_entries(date,outfitId,worn) SELECT todayDate,id,0 FROM outfits WHERE todayDate IS NOT NULL")
                    }
                })
                .build().also { instance = it }
        }
    }
}
