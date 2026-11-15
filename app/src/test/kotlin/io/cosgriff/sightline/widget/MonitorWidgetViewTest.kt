package io.cosgriff.sightline.widget

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.EventKind
import io.cosgriff.sightline.analysis.Health
import io.cosgriff.sightline.analysis.SignalEvent
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.TimelineSeries
import io.cosgriff.sightline.data.device.Channel
import io.cosgriff.sightline.data.device.Device
import io.cosgriff.sightline.data.monitor.ComparedUnit
import io.cosgriff.sightline.data.monitor.MonitorEnd
import io.cosgriff.sightline.data.monitor.MonitorPhase
import io.cosgriff.sightline.data.monitor.MonitorPlan
import io.cosgriff.sightline.data.monitor.MonitorStatus
import io.cosgriff.sightline.data.monitor.UnitState
import io.cosgriff.sightline.data.monitor.WatchedTuner
import io.cosgriff.sightline.data.monitor.WatchedUse
import io.cosgriff.sightline.protocol.LostReason
import io.cosgriff.sightline.protocol.StatusParse
import io.cosgriff.sightline.protocol.StatusParser
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/** The widget says what the notification says: quality with its health in a word, dips, time run. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "en-rUS")
class MonitorWidgetViewTest {
    private val context = RuntimeEnvironment.getApplication()
    private val device = Device("D", "Roof", "HDFX-4K", "1", "http://x", null, 4, "192.168.1.50", 65001, 0)
    private val channel = Channel("D", "9.1", "KUSA", "u", null, null, null, false)
    private val plan = MonitorPlan(device, listOf(channel), null, 30_000, null, 1_000, Thresholds.DEFAULT, false, false)

    private fun status(line: String, phase: MonitorPhase = MonitorPhase.Watching(0, false), ended: MonitorEnd? = null) = MonitorStatus(
        plan, 1, START, channel, phase, TimelineSeries(START, 1_000),
        events = listOf(SignalEvent(EventKind.DIP, "9.1", START, START + 5_000), SignalEvent(EventKind.DIP, "9.1", START + 60_000, START + 70_000)),
        latest = (StatusParser.parse(line) as StatusParse.Parsed).status,
        ended = ended,
    )

    private fun view(s: MonitorStatus?) = monitorWidgetView(context, s, Thresholds.DEFAULT, START + 2 * 3_600_000L + 5 * 60_000L)

    @Test
    fun `nothing running offers to set one up`() {
        assertThat(view(null)).isEqualTo(MonitorWidgetView.Idle("No monitor running", "Tap to set one up."))
    }

    @Test
    fun `a running monitor shows quality, health in a word, dips and time`() {
        val v = view(status("ch=8vsb:189000000 lock=8vsb ss=88 snq=72 seq=100 bps=1 pps=0")) as MonitorWidgetView.Running
        assertThat(v.title).isEqualTo("Monitoring 9.1")
        assertThat(v.quality).isEqualTo("72")
        assertThat(v.health).isEqualTo(Health.MARGINAL)
        assertThat(v.healthWord).isEqualTo("Marginal")
        assertThat(v.detail).isEqualTo("2 dips · 2 h 05 min")
    }

    @Test
    fun `no lock and waiting say so, without a number`() {
        val noLock = view(status("ch=8vsb:189000000 lock=none ss=10 snq=0 seq=0 bps=0 pps=0")) as MonitorWidgetView.Running
        assertThat(noLock.quality).isNull()
        assertThat(noLock.healthWord).isEqualTo("No lock")
        val paused = view(status("ch=none lock=none ss=0 snq=0 seq=0", MonitorPhase.Paused(LostReason.Released, 30.seconds))) as MonitorWidgetView.Running
        assertThat(paused.healthWord).isEqualTo("Paused: the tuner was taken")
    }

    @Test
    fun `an ended monitor gives the dip count`() {
        assertThat(view(status("ch=none lock=none ss=0 snq=0 seq=0", ended = MonitorEnd.STOPPED))).isEqualTo(MonitorWidgetView.Ended("Monitor ended", "2 dips"))
    }

    private companion object {
        const val START = 1_793_577_600_000L
    }

    @Test
    fun `a watch of every tuner gives the tuners in use, or the device that stopped answering`() {
        val whole = plan.copy(channels = emptyList(), wholeDevices = listOf(device))
        val tuners = listOf(
            WatchedTuner("D", "Roof", 0, WatchedUse.InUse("192.0.2.20", channel, null)),
            WatchedTuner("D", "Roof", 1, WatchedUse.Idle),
        )
        val running = status("ch=none lock=none ss=0 snq=0 seq=0 bps=0 pps=0", MonitorPhase.WatchingAll()).copy(plan = whole, tuners = tuners)
        val v = view(running) as MonitorWidgetView.Running
        assertThat(v.title).isEqualTo("Watching every tuner on Roof")
        assertThat(v.quality).isNull()
        assertThat(v.healthWord).isEqualTo("1 tuner in use")
        val down = view(running.copy(phase = MonitorPhase.WatchingAll(setOf("D")))) as MonitorWidgetView.Running
        assertThat(down.healthWord).isEqualTo("Roof isn't answering")
    }

    @Test
    fun `a two-unit compare gives each unit's quality, or that it's waiting`() {
        val garage = device.copy(deviceId = "E", friendlyName = "Garage")
        val compare = plan.copy(channels = listOf(channel, channel.copy(deviceId = "E")), compareUnits = listOf(device, garage))
        val locked = (StatusParser.parse("ch=8vsb:189000000 lock=8vsb ss=88 snq=84 seq=100 bps=1 pps=0") as StatusParse.Parsed).status
        val units = listOf(
            ComparedUnit("D", "Roof", TimelineSeries(START, 1_000), UnitState.Holding(3, false), locked),
            ComparedUnit("E", "Garage", TimelineSeries(START, 1_000), UnitState.NoSpare(30.seconds)),
        )
        val running = status("ch=none lock=none ss=0 snq=0 seq=0 bps=0 pps=0", MonitorPhase.Comparing).copy(plan = compare, units = units)
        val v = view(running) as MonitorWidgetView.Running
        assertThat(v.title).isEqualTo("Comparing 9.1 on two units")
        assertThat(v.quality).isNull()
        assertThat(v.healthWord).isEqualTo("Roof 84 · Garage waiting")
        assertThat(v.detail).isEqualTo("2 dips · 2 h 05 min")
    }
}
