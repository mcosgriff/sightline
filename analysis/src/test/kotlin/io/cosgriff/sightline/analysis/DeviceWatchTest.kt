package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** A watch of every tuner of one device: per-tuner detectors and baselines, device-wide outages. */
class DeviceWatchTest {
    private val watch = DeviceWatch("D", Thresholds.DEFAULT)
    private val events = mutableListOf<SignalEvent>()

    private fun good(snq: Int = 85) = SignalSample(true, 80, snq, 100)
    private fun bad(snq: Int = 40) = SignalSample(true, 80, snq, 80)
    private val noLock = SignalSample(false, 30, 0, 0)
    private fun on(guide: String, s: SignalSample = good(), te: Long? = null) =
        TunerRead.Tuned(guide, s, te?.let { ErrorCounters(it, 0, 0, 0) })

    private fun poll(atS: Long, vararg reads: Pair<Int, TunerRead>): WatchStep =
        watch.polled(atS * 1_000, reads.toMap()).also { events += it.events }

    @Test
    fun `each tuner keeps its own detector, and its events say which tuner`() {
        for (s in 0L until 30) {
            poll(s, 0 to on("2.1", if (s in 5L..8L) bad() else good()), 3 to on("5.1"))
        }
        events += watch.finish(30_000)
        val dip = events.single()
        assertThat(dip.kind).isEqualTo(EventKind.DIP)
        assertThat(dip.guideNumber).isEqualTo("2.1")
        assertThat(dip.deviceId).isEqualTo("D")
        assertThat(dip.tuner).isEqualTo(0)
        assertThat(dip.startMs).isEqualTo(5_000)
    }

    @Test
    fun `an idle tuner records nothing and is never a dropout`() {
        val first = poll(0, 0 to TunerRead.Idle, 1 to on("2.1"))
        assertThat(first.recorded.keys).containsExactly(1)
        for (s in 1L until 20) {
            val step = poll(s, 0 to TunerRead.Idle, 1 to on("2.1"))
            assertThat(step.recorded.keys).containsExactly(1)
        }
        events += watch.finish(20_000)
        assertThat(events).isEmpty()
        assertThat(watch.channels).containsExactly(1, "2.1")
    }

    @Test
    fun `a tuner going idle mid-dip closes it at its last reading`() {
        poll(0, 2 to on("9.1"))
        poll(1, 2 to on("9.1", noLock))
        poll(2, 2 to on("9.1", noLock))
        poll(3, 2 to TunerRead.Idle)
        events += watch.finish(60_000)
        val e = events.single()
        assertThat(e.kind).isEqualTo(EventKind.DROPOUT)
        assertThat(e.startMs).isEqualTo(1_000)
        assertThat(e.endMs).isEqualTo(2_000)
        assertThat(e.tuner).isEqualTo(2)
    }

    @Test
    fun `the seconds after a tune are skipped until it locks with seq above 0`() {
        val tuned = poll(0, 1 to on("4.1", SignalSample(true, 80, 85, 0)))
        assertThat(tuned.retuned).containsExactly(1)
        assertThat(tuned.recorded).isEmpty()
        val locked = poll(1, 1 to on("4.1"))
        assertThat(locked.recorded.keys).containsExactly(1)
        assertThat(locked.firstLocked).containsExactly(1)
        assertThat(poll(2, 1 to on("4.1")).firstLocked).isEmpty()
    }

    @Test
    fun `a tune that never locks is recorded after the settle, and becomes a dropout`() {
        assertThat(poll(0, 1 to on("4.1", noLock)).recorded).isEmpty()
        assertThat(poll(2, 1 to on("4.1", noLock)).recorded).isEmpty()
        val after = poll(3, 1 to on("4.1", noLock))
        assertThat(after.recorded.keys).containsExactly(1)
        assertThat(after.firstLocked).isEmpty()
        assertThat(watch.open.single().kind).isEqualTo(EventKind.DROPOUT)
    }

    @Test
    fun `a channel change starts a fresh baseline and closes the old channel's dip`() {
        poll(0, 0 to on("2.1", te = 100))
        assertThat(poll(1, 0 to on("2.1", te = 104)).recorded[0]?.transport).isEqualTo(4)
        poll(2, 0 to on("2.1", bad(), te = 110))
        poll(3, 0 to on("2.1", bad(), te = 120))
        // The client retunes: the counters start again on the device, the first reading is the baseline.
        val retuned = poll(4, 0 to on("4.1", te = 900))
        assertThat(retuned.retuned).containsExactly(0)
        assertThat(retuned.recorded[0]).isEqualTo(PacketErrors.NONE)
        assertThat(retuned.events.single().guideNumber).isEqualTo("2.1")
        assertThat(retuned.events.single().endMs).isEqualTo(3_000)
        assertThat(poll(5, 0 to on("4.1", te = 905)).recorded[0]?.transport).isEqualTo(5)
    }

    @Test
    fun `tuned again after idle is a new tune, even on the same channel`() {
        poll(0, 0 to on("2.1", te = 50))
        poll(1, 0 to TunerRead.Idle)
        val again = poll(2, 0 to on("2.1", te = 70))
        assertThat(again.retuned).containsExactly(0)
        assertThat(again.recorded[0]).isEqualTo(PacketErrors.NONE)
    }

    @Test
    fun `a tuner that gives no answer one poll records nothing and keeps its place`() {
        poll(0, 0 to on("2.1", te = 10))
        val step = poll(1, 0 to TunerRead.Unread)
        assertThat(step.recorded).isEmpty()
        assertThat(step.retuned).isEmpty()
        assertThat(poll(2, 0 to on("2.1", te = 15)).recorded[0]?.transport).isEqualTo(5)
    }

    @Test
    fun `a device that stops answering is one event for the device, not one per tuner`() {
        poll(0, 0 to on("2.1"), 1 to on("4.1"), 3 to on("5.1"))
        poll(1, 0 to on("2.1"), 1 to on("4.1", noLock), 3 to on("5.1"))
        events += watch.missed(2_000)
        events += watch.missed(3_000)
        assertThat(watch.unreachable).isFalse()
        events += watch.missed(4_000)
        assertThat(watch.unreachable).isTrue()
        // Tuner 1's dropout closed at its last reading when the device went.
        assertThat(events.single().kind).isEqualTo(EventKind.DROPOUT)
        assertThat(events.single().tuner).isEqualTo(1)
        assertThat(events.single().endMs).isEqualTo(1_000)
        poll(5, 0 to on("2.1"), 1 to on("4.1"), 3 to on("5.1"))
        val outage = events.filter { it.kind == EventKind.DEVICE_UNREACHABLE }
        assertThat(outage).containsExactly(SignalEvent(EventKind.DEVICE_UNREACHABLE, null, 2_000, 5_000, deviceId = "D"))
    }

    @Test
    fun `a long silence is one gap for the device, and closes every tuner's dip`() {
        poll(0, 0 to on("2.1", bad()), 2 to on("9.1", bad()))
        poll(1, 0 to on("2.1", bad()), 2 to on("9.1", bad()))
        poll(200, 0 to on("2.1"), 2 to on("9.1"))
        val gaps = events.filter { it.kind == EventKind.GAP }
        assertThat(gaps).containsExactly(SignalEvent(EventKind.GAP, null, 1_000, 200_000, deviceId = "D"))
        assertThat(events.filter { it.isDip }.map { it.tuner }).containsExactly(0, 2)
        assertThat(events.filter { it.isDip }.all { it.endMs == 1_000L }).isTrue()
    }

    @Test
    fun `open events cover every tuner and the outage`() {
        poll(0, 0 to on("2.1", bad()), 1 to on("4.1", bad()))
        poll(1, 0 to on("2.1", bad()), 1 to on("4.1", bad()))
        assertThat(watch.open.map { it.tuner }).containsExactly(0, 1)
        repeat(3) { watch.missed((it + 2) * 1_000L) }
        assertThat(watch.open.single().kind).isEqualTo(EventKind.DEVICE_UNREACHABLE)
    }

    @Test
    fun `an event concerns its own tuner, and a device-wide one every tuner of its device`() {
        val dip = SignalEvent(EventKind.DIP, "9.1", 0, 1, deviceId = "D", tuner = 2)
        assertThat(dip.concerns(TunerKey("D", 2))).isTrue()
        assertThat(dip.concerns(TunerKey("D", 1))).isFalse()
        assertThat(dip.concerns(TunerKey("E", 2))).isFalse()
        val outage = SignalEvent(EventKind.DEVICE_UNREACHABLE, null, 0, 1, deviceId = "D")
        assertThat(outage.concerns(TunerKey("D", 0))).isTrue()
        assertThat(outage.concerns(TunerKey("E", 0))).isFalse()
        assertThat(SignalEvent(EventKind.GAP, null, 0, 1).concerns(TunerKey("E", 3))).isTrue()
    }
}
