package com.dungeonvibes.data.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.dungeonvibes.data.TimeSource
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.data.scene.SceneRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Upgrading from the MVP database keeps every existing row (FX_REQUIREMENTS.md → Data model).
 *
 * Builds a version 1 database file from the exported 1.json schema with plain SQLite, fills it,
 * then opens it with the current Room database. Room runs the 1 → 2 migration and then checks
 * every table matches what the code expects, so a bad migration fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MigrationTest {

    private val context = RuntimeEnvironment.getApplication()
    private val dbName = "migration-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun version1ToCurrent_keepsAllData_andAddsEffectTables() {
        createDatabase(1, V1_ROWS)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                val scenes = SceneRepository(db, TimeSource { 2_000L })
                val library = scenes.getScene(1)!!
                assertEquals("Tavern", library.name)
                val copy = scenes.getScene(2)!!
                assertEquals(1L, copy.parentSceneId)
                assertEquals(1L, copy.adventureId)
                assertEquals(listOf(SceneLightEntity(1, 1, true, LightMode.WHITE, 30f, 0.5f, 2_700, 0.6f)), scenes.getSceneLights(1))
                assertEquals(1, db.sceneLightDao().applyTargets(1).size)

                // The new tables work.
                assertTrue(scenes.getSceneEffects(1).isEmpty())
                val effects = GroupEffects(flashes = FlashSettings(enabled = true))
                assertTrue(scenes.saveGroupEffects(1, 1, effects))
                assertEquals(effects, scenes.getSceneEffects(1)[1])
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun version2To3_givesExistingFlashesAFullPeakRange() {
        createDatabase(
            2,
            V1_ROWS + listOf(
                // Columns in table order: scene, slot, color shift (8), brightness shift (8), flashes (13 + color 4).
                "INSERT INTO scene_light_effects VALUES (1, 1, " +
                    "0, 'TOGETHER', 500, 'IN_SEQUENCE', 2000, 4000, 1000, 3000, " +
                    "0, 'TOGETHER', 500, 'SMOOTH', 0.2, 0.8, 3000, 5000, " +
                    "1, 'TOGETHER', 500, 0.7, 50, 100, 300, 5000, 20000, 1, 1, 100, 300, " +
                    "'WHITE', 0.0, 0.0, 6500)",
            ),
        )
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                val flashes = SceneRepository(db, TimeSource { 2_000L }).getSceneEffects(1).getValue(1).flashes
                assertTrue(flashes.enabled)
                assertEquals(0.7f, flashes.peak, 0f)
                assertEquals(1f, flashes.peakMin, 0f)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun version3To4_removesFakeBulbs_keepsRealOnes_andLeavesAllLightsOff() {
        createDatabase(
            3,
            V1_ROWS + listOf(
                "INSERT INTO light_slots VALUES (2, 'Lamp', 1)",
                "INSERT INTO bulbs VALUES (2, 'fake', 'fake:01', '0.0.0.1', 'Fake bulb 1', 1000)",
                "INSERT INTO slot_bulbs VALUES (2, 2)",
            ),
        )
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                assertEquals(listOf("lifx_lan"), db.bulbDao().observeAll().first().map { it.protocolId })
                assertEquals(listOf(1L), db.lightSlotDao().observeAssignments().first().map { it.bulbId })
                // The group itself stays, just without the fake bulb.
                assertEquals(2, db.lightSlotDao().observeAll().first().size)
                assertEquals(false, db.sceneDao().get(1)!!.allLightsOn)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun version6To7_givesPaletteColorsTheirGroupsBrightness_locked() {
        createDatabase(
            6,
            listOf(
                "INSERT INTO light_slots (id, name, sortOrder, isRoom) VALUES (1, 'Ceiling', 0, 0)",
                "INSERT INTO scenes (id, name, adventureId, parentSceneId, sortOrder, fadeMs, createdAt, updatedAt, allLightsOn) " +
                    "VALUES (1, 'Campfire', NULL, NULL, 0, 1000, 1000, 1000, 0)",
                "INSERT INTO scene_lights VALUES (1, 1, 1, 'COLOR', 30.0, 1.0, 2700, 0.6)",
                // Columns in table order: scene, slot, color shift (8), brightness shift (8), flashes (14 + color 4).
                "INSERT INTO scene_light_effects VALUES (1, 1, " +
                    "1, 'TOGETHER', 500, 'IN_SEQUENCE', 2000, 4000, 1000, 3000, " +
                    "0, 'TOGETHER', 500, 'SMOOTH', 0.2, 0.8, 3000, 5000, " +
                    "0, 'TOGETHER', 500, 1.0, 1.0, 50, 100, 300, 5000, 20000, 1, 1, 100, 300, " +
                    "'WHITE', 0.0, 0.0, 6500)",
                "INSERT INTO scene_light_effect_colors VALUES (1, 1, 0, 'COLOR', 20.0, 1.0, 2700)",
                "INSERT INTO scene_light_effect_colors VALUES (1, 1, 1, 'WHITE', 0.0, 0.0, 2200)",
            ),
        )
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
        try {
            runBlocking {
                val effects = SceneRepository(db, TimeSource { 2_000L }).getSceneEffects(1).getValue(1)
                assertEquals(listOf(0.6f, 0.6f), effects.palette.map { it.brightness })
                assertTrue(effects.colorShift.brightnessLocked)
                // Flash colors use their peak; theirs is simply 100 %.
                assertEquals(1f, effects.flashes.color.brightness, 0f)
            }
        } finally {
            db.close()
        }
    }

    /** Builds a database file at [version] from its exported schema, then runs [rows]. */
    private fun createDatabase(version: Int, rows: List<String>) {
        val schema = JSONObject(schemaFile(version).readText()).getJSONObject("database")
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))

            for (row in rows) sqlite.execSQL(row)
            sqlite.version = version
        } finally {
            sqlite.close()
        }
    }

    private companion object {
        /** The MVP's data: valid in every version, since later versions only add. */
        val V1_ROWS = listOf(
            "INSERT INTO bulbs VALUES (1, 'lifx_lan', 'd073d5000001', '192.168.1.20', 'Left Lamp', 1000)",
            "INSERT INTO light_slots VALUES (1, 'Ceiling', 0)",
            "INSERT INTO slot_bulbs VALUES (1, 1)",
            "INSERT INTO adventures VALUES (1, 'The Lost Mine', 0, 1000, 1000)",
            "INSERT INTO scenes VALUES (1, 'Tavern', NULL, NULL, 0, 1000, 1000, 1000)",
            "INSERT INTO scenes VALUES (2, 'Tavern', 1, 1, 0, 1000, 1000, 1000)",
            "INSERT INTO scene_lights VALUES (1, 1, 1, 'WHITE', 30.0, 0.5, 2700, 0.6)",
        )
    }

    /** Gradle runs unit tests from the app module folder; the IDE may use the project root. */
    private fun schemaFile(version: Int): File {
        val path = "schemas/com.dungeonvibes.data.db.AppDatabase/$version.json"
        return listOf(File(path), File("app/$path")).firstOrNull { it.exists() }
            ?: error("Exported schema $path not found; build the app once so Room exports it")
    }
}
