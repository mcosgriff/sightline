package io.cosgriff.sightline.data.sweep

import android.app.Application
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.data.db.ChannelEntity
import io.cosgriff.sightline.data.db.SightlineDatabase
import io.cosgriff.sightline.protocol.StatusParse
import io.cosgriff.sightline.protocol.StatusParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Sweeps saved as SWEEP sessions and read back summarised. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SweepStorageTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SightlineDatabase::class.java)
        .allowMainThreadQueries().build()
    private val repository = SweepRepository(db.sessions(), db.channels())

    @After
    fun tearDown() = db.close()

    private fun status(line: String) = (StatusParser.parse(line) as StatusParse.Parsed).status
    private fun locked(snq: Int) = status("ch=8vsb:189000000 lock=8vsb ss=80 snq=$snq seq=100 bps=19392658 pps=0")
    private val noLock = status("ch=8vsb:575000000 lock=none ss=30 snq=0 seq=0 bps=0 pps=0")

    @Test
    fun `a saved sweep reads back per channel, tuned or not, newest first in the list`() = runBlocking<Unit> {
        db.channels().upsertAll(
            listOf(
                ChannelEntity("D", "2.1", "TWO", "u", 0, rfChannel = 7, band = "VHF_HI"),
                ChannelEntity("D", "9.1", "NINE", "u", 1),
            ),
        )
        val first = repository.save(
            "D",
            SweepOutcome.Finished(
                1_000, 9_000, listOf("2.1", "9.1", "102.1"),
                listOf(
                    SweepReading(2_000, "2.1", 0, locked(50)),
                    SweepReading(3_000, "2.1", 0, locked(58)),
                    SweepReading(4_000, "2.1", 0, locked(54)),
                    SweepReading(5_000, "9.1", 1, noLock),
                ),
            ),
            dwellMs = 6_000, pollIntervalMs = 1_000,
        )
        val second = repository.save("D", SweepOutcome.Finished(20_000, 30_000, listOf("2.1"), emptyList()), 6_000, 1_000)

        assertThat(repository.sweeps("D").first().map { it.id }).containsExactly(second, first).inOrder()

        val saved = repository.load(first, Thresholds.DEFAULT)!!
        assertThat(saved.info.dwellMs).isEqualTo(6_000)
        assertThat(saved.info.channelCount).isEqualTo(3)
        val byGuide = saved.summary.channels.associateBy { it.guideNumber }
        assertThat(byGuide.getValue("2.1").medianSnq).isEqualTo(54)
        assertThat(byGuide.getValue("2.1").health).isEqualTo(Health.POOR)
        assertThat(byGuide.getValue("9.1").health).isEqualTo(Health.NO_LOCK)
        assertThat(byGuide.getValue("102.1").measured).isFalse()
        assertThat(saved.channels.getValue("2.1").rfChannel).isEqualTo(7)
        assertThat(saved.channels.keys).containsExactly("2.1", "9.1")
    }

    @Test
    fun `deleting a sweep takes its readings`() = runBlocking<Unit> {
        val id = repository.save("D", SweepOutcome.Finished(1, 2, listOf("2.1"), listOf(SweepReading(1, "2.1", 0, locked(80)))), 6_000, 1_000)
        repository.delete(id)
        assertThat(repository.load(id, Thresholds.DEFAULT)).isNull()
        assertThat(db.sessions().readings(id)).isEmpty()
    }
}
