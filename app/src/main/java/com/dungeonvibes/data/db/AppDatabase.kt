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
        AdventureFxEntity::class,
        EffectLayerEntity::class,
    ],
    version = 13,
    exportSchema = true,
    // Room writes each upgrade itself by comparing the exported schemas in app/schemas.
    // 1 → 2 adds the two effect tables; 2 → 3 adds the flash peak range's low end (default 100 %).
    // 3 → 4 is written by hand below (it also removes data).
    // 5 → 6 adds the FX library tables. 6 → 7 through 12 → 13 are written by hand below.
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

        /**
         * Bulbs in several groups, and individual lights. slot_bulbs is rebuilt with (slotId,
         * bulbId) as its key instead of bulbId alone, keeping every assignment; nothing else refers
         * to it, so dropping the old table can't cascade. light_slots gains the bulbId of an
         * individual light's hidden slot (none exist yet). Adding a column can't cascade either.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE light_slots ADD COLUMN bulbId INTEGER " +
                        "REFERENCES bulbs(id) ON UPDATE NO ACTION ON DELETE CASCADE",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_light_slots_bulbId ON light_slots (bulbId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS slot_bulbs_new (
                        slotId INTEGER NOT NULL,
                        bulbId INTEGER NOT NULL,
                        PRIMARY KEY(slotId, bulbId),
                        FOREIGN KEY(slotId) REFERENCES light_slots(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(bulbId) REFERENCES bulbs(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO slot_bulbs_new (slotId, bulbId) SELECT slotId, bulbId FROM slot_bulbs")
                db.execSQL("DROP TABLE slot_bulbs")
                db.execSQL("ALTER TABLE slot_bulbs_new RENAME TO slot_bulbs")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_slot_bulbs_slotId ON slot_bulbs (slotId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_slot_bulbs_bulbId ON slot_bulbs (bulbId)")
            }
        }

        /**
         * Bulb order within groups, Strobe and Spin FX, the Spin scene layer, and per-adventure FX
         * favorites. Every change adds a column with a default or a new table, so nothing
         * cascades. Existing groups keep the order they showed until now: bulbs by name.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE slot_bulbs ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    UPDATE slot_bulbs SET position = (
                        SELECT COUNT(*) FROM slot_bulbs o
                        JOIN bulbs ob ON ob.id = o.bulbId
                        JOIN bulbs mb ON mb.id = slot_bulbs.bulbId
                        WHERE o.slotId = slot_bulbs.slotId AND (
                            ob.name COLLATE NOCASE < mb.name COLLATE NOCASE OR
                            (ob.name COLLATE NOCASE = mb.name COLLATE NOCASE AND ob.id < mb.id)
                        )
                    )
                    """.trimIndent(),
                )

                // The Spin scene layer: off, in a cyan (matches SpinSettings()).
                addColumns(
                    db, "scene_light_effects",
                    "spin_enabled INTEGER NOT NULL DEFAULT 0",
                    *colorColumns("spin_color_", hue = 200),
                    "spin_level REAL NOT NULL DEFAULT 1.0",
                    "spin_attackMs INTEGER NOT NULL DEFAULT 100",
                    "spin_holdMs INTEGER NOT NULL DEFAULT 100",
                    "spin_releaseMs INTEGER NOT NULL DEFAULT 500",
                    "spin_stepMs INTEGER NOT NULL DEFAULT 300",
                    "spin_lapGapMs INTEGER NOT NULL DEFAULT 0",
                )
                // Strobe and Spin FX settings (matching StrobeSettings() and SpinFxSettings()).
                addColumns(
                    db, "fx",
                    "strobe_color_mode TEXT NOT NULL DEFAULT 'WHITE'",
                    "strobe_color_hue REAL NOT NULL DEFAULT 0.0",
                    "strobe_color_saturation REAL NOT NULL DEFAULT 0.0",
                    "strobe_color_kelvin INTEGER NOT NULL DEFAULT 6500",
                    "strobe_color_brightness REAL NOT NULL DEFAULT 1.0",
                    "strobe_level REAL NOT NULL DEFAULT 1.0",
                    "strobe_flashesPerSecond REAL NOT NULL DEFAULT 3.0",
                    "strobe_durationMs INTEGER NOT NULL DEFAULT 2000",
                    *colorColumns("spin_color_", hue = 270),
                    "spin_level REAL NOT NULL DEFAULT 1.0",
                    "spin_attackMs INTEGER NOT NULL DEFAULT 50",
                    "spin_holdMs INTEGER NOT NULL DEFAULT 100",
                    "spin_releaseMs INTEGER NOT NULL DEFAULT 400",
                    "spin_stepMs INTEGER NOT NULL DEFAULT 200",
                    "spin_laps INTEGER NOT NULL DEFAULT 2",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `adventure_fx` (
                        `adventureId` INTEGER NOT NULL,
                        `fxId` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        PRIMARY KEY(`adventureId`, `fxId`),
                        FOREIGN KEY(`adventureId`) REFERENCES `adventures`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`fxId`) REFERENCES `fx`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_adventure_fx_fxId` ON `adventure_fx` (`fxId`)")
            }
        }

        /** Spin direction (owner request): every existing Spin keeps going first to last. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scene_light_effects ADD COLUMN spin_reverse INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE fx ADD COLUMN spin_reverse INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Intro FX on scenes (none for every existing scene); deleting the FX clears it. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE scenes ADD COLUMN introFxId INTEGER " +
                        "REFERENCES fx(id) ON UPDATE NO ACTION ON DELETE SET NULL",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scenes_introFxId ON scenes (introFxId)")
            }
        }

        /**
         * Layered effects (owner request): each group's Brightness shift, Flashes and Spin become
         * rows of their own, so a group can stack several. Every effect that was on becomes a
         * layer (Flashes on top, then Spin, then Brightness shift, which is how they mixed before);
         * effects that were off are dropped (owner decision). The old columns are left in place
         * (unused) rather than rebuilding a table the palettes refer to.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scene_effect_layers` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sceneId` INTEGER NOT NULL,
                        `slotId` INTEGER NOT NULL,
                        `position` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `brightnessShift_enabled` INTEGER NOT NULL,
                        `brightnessShift_spread` TEXT NOT NULL,
                        `brightnessShift_waveOffsetMs` INTEGER NOT NULL,
                        `brightnessShift_mode` TEXT NOT NULL,
                        `brightnessShift_levelMin` REAL NOT NULL,
                        `brightnessShift_levelMax` REAL NOT NULL,
                        `brightnessShift_speedMinMs` INTEGER NOT NULL,
                        `brightnessShift_speedMaxMs` INTEGER NOT NULL,
                        `flashes_enabled` INTEGER NOT NULL,
                        `flashes_spread` TEXT NOT NULL,
                        `flashes_waveOffsetMs` INTEGER NOT NULL,
                        `flashes_peak` REAL NOT NULL,
                        `flashes_peakMin` REAL NOT NULL DEFAULT 1.0,
                        `flashes_attackMs` INTEGER NOT NULL,
                        `flashes_holdMs` INTEGER NOT NULL,
                        `flashes_releaseMs` INTEGER NOT NULL,
                        `flashes_gapMinMs` INTEGER NOT NULL,
                        `flashes_gapMaxMs` INTEGER NOT NULL,
                        `flashes_pulsesMin` INTEGER NOT NULL,
                        `flashes_pulsesMax` INTEGER NOT NULL,
                        `flashes_spacingMinMs` INTEGER NOT NULL,
                        `flashes_spacingMaxMs` INTEGER NOT NULL,
                        `flashes_color_mode` TEXT NOT NULL,
                        `flashes_color_hue` REAL NOT NULL,
                        `flashes_color_saturation` REAL NOT NULL,
                        `flashes_color_kelvin` INTEGER NOT NULL,
                        `flashes_color_brightness` REAL NOT NULL DEFAULT 1.0,
                        `spin_enabled` INTEGER NOT NULL,
                        `spin_level` REAL NOT NULL,
                        `spin_attackMs` INTEGER NOT NULL,
                        `spin_holdMs` INTEGER NOT NULL,
                        `spin_releaseMs` INTEGER NOT NULL,
                        `spin_stepMs` INTEGER NOT NULL,
                        `spin_lapGapMs` INTEGER NOT NULL,
                        `spin_color_mode` TEXT NOT NULL,
                        `spin_color_hue` REAL NOT NULL,
                        `spin_color_saturation` REAL NOT NULL,
                        `spin_color_kelvin` INTEGER NOT NULL,
                        `spin_color_brightness` REAL NOT NULL DEFAULT 1.0,
                        `spin_reverse` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`sceneId`, `slotId`) REFERENCES `scene_lights`(`sceneId`, `slotId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scene_effect_layers_sceneId_slotId` ON `scene_effect_layers` (`sceneId`, `slotId`)",
                )
                for ((position, type, flag) in LAYER_MIGRATION) {
                    db.execSQL(
                        "INSERT INTO scene_effect_layers (sceneId, slotId, position, type, $LAYER_COLUMNS) " +
                            "SELECT sceneId, slotId, $position, '$type', $LAYER_COLUMNS FROM scene_light_effects WHERE $flag = 1",
                    )
                }
                db.execSQL("UPDATE scene_light_effects SET brightnessShift_enabled = 0, flashes_enabled = 0, spin_enabled = 0")
            }
        }

        /** Old effect → (priority, layer type, the column that said it was on). */
        private val LAYER_MIGRATION = listOf(
            Triple(0, "FLASHES", "flashes_enabled"),
            Triple(1, "SPIN", "spin_enabled"),
            Triple(2, "BRIGHTNESS_SHIFT", "brightnessShift_enabled"),
        )

        /** Every settings column a layer row shares with scene_light_effects. */
        private const val LAYER_COLUMNS =
            "`brightnessShift_enabled`, `brightnessShift_spread`, `brightnessShift_waveOffsetMs`, `brightnessShift_mode`, `brightnessShift_levelMin`, `brightnessShift_levelMax`, `brightnessShift_speedMinMs`, `brightnessShift_speedMaxMs`, `flashes_enabled`, `flashes_spread`, `flashes_waveOffsetMs`, `flashes_peak`, `flashes_peakMin`, `flashes_attackMs`, `flashes_holdMs`, `flashes_releaseMs`, `flashes_gapMinMs`, `flashes_gapMaxMs`, `flashes_pulsesMin`, `flashes_pulsesMax`, `flashes_spacingMinMs`, `flashes_spacingMaxMs`, `flashes_color_mode`, `flashes_color_hue`, `flashes_color_saturation`, `flashes_color_kelvin`, `flashes_color_brightness`, `spin_enabled`, `spin_level`, `spin_attackMs`, `spin_holdMs`, `spin_releaseMs`, `spin_stepMs`, `spin_lapGapMs`, `spin_color_mode`, `spin_color_hue`, `spin_color_saturation`, `spin_color_kelvin`, `spin_color_brightness`, `spin_reverse`"

        private fun addColumns(db: SupportSQLiteDatabase, table: String, vararg columns: String) {
            for (column in columns) db.execSQL("ALTER TABLE $table ADD COLUMN $column")
        }

        /** An embedded saturated FxColor's columns at [hue] (FxColor.color(hue)). */
        private fun colorColumns(prefix: String, hue: Int): Array<String> = arrayOf(
            "${prefix}mode TEXT NOT NULL DEFAULT 'COLOR'",
            "${prefix}hue REAL NOT NULL DEFAULT $hue.0",
            "${prefix}saturation REAL NOT NULL DEFAULT 1.0",
            "${prefix}kelvin INTEGER NOT NULL DEFAULT 2700",
            "${prefix}brightness REAL NOT NULL DEFAULT 1.0",
        )

        /** Hand-written migrations, for every builder (the app's and the tests'). */
        val MIGRATIONS: Array<Migration> =
            arrayOf(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
    }
}
