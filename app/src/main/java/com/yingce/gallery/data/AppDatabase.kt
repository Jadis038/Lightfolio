package com.yingce.gallery.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Photo::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 -> v2：导入链路完整化。
         * - stored_name 允许为空（仅引用模式不落盘）
         * - 新增来源/导入方式/拍摄时间/机型/GPS/指纹/状态
         * SQLite 不能直接去掉 NOT NULL 与旧唯一索引，因此整表重建。
         * 老数据一律视为"复制模式、来源未知、拍摄时间回退到导入时间"。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS photos_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        display_name TEXT NOT NULL,
                        stored_name TEXT,
                        size_bytes INTEGER NOT NULL,
                        mime_type TEXT,
                        imported_at INTEGER NOT NULL,
                        source_kind TEXT NOT NULL DEFAULT 'MEDIASTORE',
                        source_uri TEXT,
                        import_mode TEXT NOT NULL DEFAULT 'COPY',
                        taken_at INTEGER NOT NULL DEFAULT 0,
                        taken_at_source TEXT NOT NULL DEFAULT 'FILE',
                        camera_make TEXT,
                        camera_model TEXT,
                        gps_lat REAL,
                        gps_lon REAL,
                        fingerprint TEXT,
                        state TEXT NOT NULL DEFAULT 'OK'
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO photos_new (
                        id, display_name, stored_name, size_bytes, mime_type, imported_at,
                        source_kind, source_uri, import_mode, taken_at, taken_at_source,
                        camera_make, camera_model, gps_lat, gps_lon, fingerprint, state
                    )
                    SELECT id, display_name, stored_name, size_bytes, mime_type, imported_at,
                        'MEDIASTORE', NULL, 'COPY', imported_at, 'FILE',
                        NULL, NULL, NULL, NULL, NULL, 'OK'
                    FROM photos
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE photos")
                db.execSQL("ALTER TABLE photos_new RENAME TO photos")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_stored_name ON photos(stored_name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_imported_at ON photos(imported_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_taken_at ON photos(taken_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_fingerprint ON photos(fingerprint)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_camera_model ON photos(camera_model)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_source_uri ON photos(source_uri)")
            }
        }

        /** 惰性单例：冷启动不触碰磁盘，首次查看作品时才建库。 */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yingce.db",
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
