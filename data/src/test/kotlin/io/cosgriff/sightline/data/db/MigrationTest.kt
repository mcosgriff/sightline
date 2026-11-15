package io.cosgriff.sightline.data.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.EventKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Room's migrations, against databases built from the schemas Room exported for each version (`data/schemas`):
 * every older version opens as the current one, Room checking the result against the current schema, a version 6
 * database keeps its sessions, readings and events through the move to 7 (a watch of every tuner), and a version 7
 * one through the move to 8 (the phone's Wi-Fi), its sessions having no Wi-Fi samples.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val file: File = context.getDatabasePath(NAME)
    private var room: SightlineDatabase? = null

    @After
    fun tearDown() {
        room?.close()
        file.delete()
    }

    /** A database as Room [version] made it, from its exported schema, with [fill] run on it. */
    private fun create(version: Int, fill: SQLiteDatabase.() -> Unit = {}) {
        file.parentFile!!.mkdirs()
        file.delete()
        val schema = Json.parseToJsonElement(File("$SCHEMAS/$version.json").readText()).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in entity["indices"]?.jsonArray.orEmpty()) {
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            for (query in schema.getValue("setupQueries").jsonArray) db.execSQL(query.jsonPrimitive.content)
            db.fill()
            db.version = version
        }
    }

    private fun open(): SightlineDatabase =
        Room.databaseBuilder(context, SightlineDatabase::class.java, NAME).allowMainThreadQueries().build().also { room = it }

    @Test
    fun `every older version opens as the current one`() = runBlocking<Unit> {
        for (version in 1 until CURRENT) {
            create(version)
            val db = open()
            assertThat(db.openHelper.writableDatabase.version).isEqualTo(CURRENT)
            db.close()
            room = null
        }
    }

    @Test
    fun `a version 6 session keeps its readings and events, read as one device's`() = runBlocking<Unit> {
        create(6) {
            execSQL(
                "INSERT INTO session (id, deviceId, kind, startedAt, endedAt, channels, tuner, pollIntervalMs, weatherFetched, fetchWeather, watched, holder) " +
                    "VALUES (1, 'D', 'MONITOR', 1000, 9000, '9.1', 2, 1000, 0, 0, 1, '192.168.1.20')",
            )
            execSQL("INSERT INTO reading (sessionId, ts, guideNumber, tuner, ss, snq, seq, lock, transportErrors) VALUES (1, 2000, '9.1', 2, 80, 85, 100, '8vsb', 4)")
            execSQL("INSERT INTO event (sessionId, startTs, endTs, guideNumber, kind, minSnq, minSeq, noLockMs) VALUES (1, 3000, 5000, '9.1', 'DIP', 50, 90, 0)")
        }
        val db = open().sessions()

        val session = db.get(1)!!
        assertThat(session.watched).isTrue()
        assertThat(session.holder).isEqualTo("192.168.1.20")
        assertThat(session.tuner).isEqualTo(2)
        assertThat(session.wholeDevice).isFalse()
        assertThat(session.devices).isEmpty()
        val reading = db.readings(1).single()
        assertThat(reading.transportErrors).isEqualTo(4)
        assertThat(reading.deviceId).isNull()
        val event = db.events(1).single()
        assertThat(event.kind).isEqualTo(EventKind.DIP)
        assertThat(event.deviceId).isNull()
        assertThat(event.tuner).isNull()
    }

    @Test
    fun `a version 7 session keeps everything and has no Wi-Fi samples, and new ones can be added`() = runBlocking<Unit> {
        create(7) {
            execSQL(
                "INSERT INTO session (id, deviceId, kind, startedAt, endedAt, channels, tuner, pollIntervalMs, weatherFetched, fetchWeather, watched, holder, wholeDevice, devices) " +
                    "VALUES (1, 'D', 'MONITOR', 1000, 9000, '9.1', null, 1000, 0, 0, 0, null, 0, 'D,E')",
            )
            execSQL("INSERT INTO reading (sessionId, ts, guideNumber, tuner, ss, snq, seq, lock, deviceId) VALUES (1, 2000, '9.1', 2, 80, 85, 100, '8vsb', 'E')")
            execSQL("INSERT INTO event (sessionId, startTs, endTs, guideNumber, kind, minSnq, minSeq, noLockMs, deviceId, tuner) VALUES (1, 3000, 5000, null, 'DEVICE_UNREACHABLE', null, null, 0, 'E', null)")
        }
        val db = open().sessions()

        assertThat(db.get(1)!!.devices).containsExactly("D", "E").inOrder()
        assertThat(db.readings(1).single().deviceId).isEqualTo("E")
        assertThat(db.events(1).single().kind).isEqualTo(EventKind.DEVICE_UNREACHABLE)
        assertThat(db.wifi(1)).isEmpty()

        db.appendWithWifi(emptyList(), emptyList(), listOf(WifiSampleEntity(0, 1, 4000, true, -61, 866, 5180, "aa:bb:cc:00:00:01")))
        assertThat(db.wifi(1).single().rssiDbm).isEqualTo(-61)
        // A session's samples go with it.
        db.delete(1)
        assertThat(db.wifi(1)).isEmpty()
    }

    private companion object {
        const val NAME = "migration-test.db"
        const val CURRENT = 8

        /** Gradle runs unit tests in the module's directory. */
        const val SCHEMAS = "schemas/io.cosgriff.sightline.data.db.SightlineDatabase"
    }
}
