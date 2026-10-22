package com.dungeonvibes.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BulbEntity::class,
        LightSlotEntity::class,
        SlotBulbEntity::class,
        AdventureEntity::class,
        SceneEntity::class,
        SceneLightEntity::class,
        SceneLightEffectsEntity::class,
        EffectColorEntity::class,
        FxEntity::class,
        FxTargetEntity::class,
    ],
    version = 8,
    exportSchema = true,
    // Room writes each upgrade itself by comparing the exported schemas in app/schemas.
    // 1 → 2 adds the two effect tables; 2 → 3 adds the flash peak range's low end (default 100 %).
    // 3 → 4 is written by hand below (it also removes data).
    // 5 → 6 adds the FX library tables. 6 → 7 and 7 → 8 are written by hand below.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 5, to = 6)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bulbDao(): BulbDao
    abstract fun lightSlotDao(): LightSlotDao
    abstract fun adventureDao(): AdventureDao
    abstract fun sceneDao(): SceneDao
    abstract fun sceneLightDao(): SceneLightDao
    abstract fun sceneEffectsDao(): SceneEffectsDao
    abstract fun fxDao(): FxDao

    companion object {
        const val NAME = "dungeon_vibes.db"

        /**
         * Adds the scene "All lights" switch (off for every existing scene), and removes the
         * simulated bulbs the debug tools used to create, now that they're gone from the app.
         * Their group assignments go first, so no orphan rows are left even without cascades.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scenes ADD COLUMN allLightsOn INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DELETE FROM slot_bulbs WHERE bulbId IN (SELECT id FROM bulbs WHERE protocolId = 'fake')")
                db.execSQL("DELETE FROM bulbs WHERE protocolId = 'fake'")
            }
        }

        /**
         * Marks one slot as the hidden "All lights" slot (none exists yet; the app creates it on
         * first use, see LightSlotRepository.ensureRoomSlot).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE light_slots ADD COLUMN isRoom INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Gives every color a brightness and Multi-color palettes a brightness lock. Each palette
         * color starts at its group's brightness (what the group showed until now), locked; flash
         * and pulse colors get 100 % (they use their peak instead).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scene_light_effect_colors ADD COLUMN brightness REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE scene_light_effects ADD COLUMN flashes_color_brightness REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE scene_light_effects ADD COLUMN colorShift_brightnessLocked INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE fx ADD COLUMN pulse_color_brightness REAL NOT NULL DEFAULT 1.0")
                db.execSQL(
                    """
                    UPDATE scene_light_effect_colors SET brightness = COALESCE(
                        (SELECT l.brightness FROM scene_lights l
                         WHERE l.sceneId = scene_light_effect_colors.sceneId AND l.slotId = scene_light_effect_colors.slotId),
                        1.0)
                    """.trimIndent(),
                )
            }
        }

        /** Marks the hidden default-lighting scene (none exists yet; it's created on first use). */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scenes ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Hand-written migrations, for every builder (the app's and the tests'). */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_6_7, MIGRATION_7_8)
    }
}
