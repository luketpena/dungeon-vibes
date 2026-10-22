package com.dungeonvibes.data

import android.app.Application
import androidx.room.Room
import com.dungeonvibes.data.db.AppDatabase
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Base for tests that need a real (in-memory) Room database. Robolectric supplies the Android
 * runtime on the PC's JVM, so these run under `testDebugUnitTest` with no device attached.
 *
 * A plain Application is used instead of the Hilt one; tests build their objects by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
abstract class DatabaseTest {

    protected lateinit var db: AppDatabase

    /** Advances by one second on every read, so timestamps are predictable and always increase. */
    protected val time = object : TimeSource {
        var now = 1_000_000L
        override fun nowMillis(): Long {
            now += 1_000
            return now
        }
    }

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }
}
