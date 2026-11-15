package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.analysis.SyntheticSession.Companion.HOUR
import io.cosgriff.sightline.analysis.SyntheticSession.Companion.MINUTE
import io.cosgriff.sightline.analysis.SyntheticSession.Companion.dipping
import io.cosgriff.sightline.analysis.SyntheticSession.Companion.fixed
import io.cosgriff.sightline.protocol.Band
import io.cosgriff.sightline.protocol.fake.SignalPattern
import io.cosgriff.sightline.protocol.fake.Wind
import org.junit.Test

/** One test per SPEC rule that should fire, and one that shouldn't, on synthetic sessions. */
class DiagnosisTest {
    private val good = fixed(ss = 80, snq = 86)
    private val bad = fixed(ss = 80, snq = 40, seq = 70)

    /** Clean for most of the time, a 40 s dip every [everyMs]. */
    private fun dips(everyMs: Long = 10 * MINUTE) = dipping(good, bad, everyMs)

    private inline fun <reified T : Finding> List<Finding>.find(): T? = filterIsInstance<T>().singleOrNull()

    @Test
    fun `a clean session finds no pattern`() {
        val s = SyntheticSession().apply { read("9.1", good, toMs = 6 * HOUR) }
        assertThat(s.diagnose()).isEmpty()
    }

    @Test
    fun `overload fires on strong noisy signal`() {
        val f = SyntheticSession().apply { read("9.1", fixed(97, 55), toMs = HOUR) }.diagnose().find<Finding.Overload>()
        assertThat(f!!.channels).containsExactly(ChannelLevel("9.1", 97, 55))
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `overload doesn't fire on strong clean signal`() {
        assertThat(SyntheticSession().apply { read("9.1", fixed(97, 85), toMs = HOUR) }.diagnose().find<Finding.Overload>()).isNull()
    }

    @Test
    fun `weak signal fires on low strength and quality, Likely when strength is poor`() {
        val possible = SyntheticSession().apply { read("9.1", fixed(30, 55), toMs = HOUR) }.diagnose().find<Finding.WeakSignal>()
        assertThat(possible!!.strength).isEqualTo(Strength.POSSIBLE)
        val likely = SyntheticSession().apply { read("9.1", fixed(20, 50), toMs = HOUR) }.diagnose().find<Finding.WeakSignal>()
        assertThat(likely!!.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `weak signal doesn't fire when strength is fine`() {
        assertThat(SyntheticSession().apply { read("9.1", fixed(60, 50), toMs = HOUR) }.diagnose().find<Finding.WeakSignal>()).isNull()
    }

    @Test
    fun `one channel bad fires on a channel well under the others`() {
        val f = SyntheticSession().apply {
            read("9.1", fixed(80, 87), toMs = 3 * HOUR, tuner = 0)
            read("4.1", fixed(80, 61), toMs = 3 * HOUR, tuner = 1)
        }.diagnose().find<Finding.OneChannelBad>()
        assertThat(f!!.guideNumber).isEqualTo("4.1")
        assertThat(f.medianSnq).isEqualTo(61)
        assertThat(f.othersMedianSnq).isEqualTo(87)
        assertThat(f.others).containsExactly("9.1")
        assertThat(f.everyHour).isTrue()
        assertThat(f.strength).isEqualTo(Strength.POSSIBLE)
    }

    @Test
    fun `one channel bad doesn't fire on a small difference`() {
        val f = SyntheticSession().apply {
            read("9.1", fixed(80, 87), toMs = HOUR, tuner = 0)
            read("4.1", fixed(80, 75), toMs = HOUR, tuner = 1)
        }.diagnose().find<Finding.OneChannelBad>()
        assertThat(f).isNull()
    }

    @Test
    fun `vhf weak fires when VHF is well under UHF`() {
        val f = SyntheticSession().apply {
            read("7.1", fixed(70, 52), toMs = HOUR, tuner = 0, rf = 7, band = Band.VHF_HI)
            read("30.1", fixed(70, 86), toMs = HOUR, tuner = 1, rf = 30, band = Band.UHF)
            read("31.1", fixed(70, 84), toMs = HOUR, tuner = 2, rf = 31, band = Band.UHF)
        }.diagnose().find<Finding.VhfWeak>()
        assertThat(f!!.vhfChannels).containsExactly("7.1")
        assertThat(f.vhfMedianSnq).isEqualTo(52)
        assertThat(f.uhfMedianSnq).isEqualTo(84)
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `vhf weak doesn't fire when the bands are alike`() {
        val f = SyntheticSession().apply {
            read("7.1", fixed(70, 82), toMs = HOUR, tuner = 0, rf = 7, band = Band.VHF_HI)
            read("30.1", fixed(70, 86), toMs = HOUR, tuner = 1, rf = 30, band = Band.UHF)
        }.diagnose().find<Finding.VhfWeak>()
        assertThat(f).isNull()
    }

    @Test
    fun `upper uhf fires when only RF 30 to 36 dips`() {
        val f = SyntheticSession().apply {
            read("33.1", dips(), toMs = 2 * HOUR, tuner = 0, rf = 33, band = Band.UHF)
            read("20.1", good, toMs = 2 * HOUR, tuner = 1, rf = 20, band = Band.UHF)
        }.diagnose().find<Finding.UpperUhfInterference>()
        assertThat(f!!.upperChannels).containsExactly("33.1")
        assertThat(f.upperDips).isEqualTo(12)
        assertThat(f.lowerDips).isEqualTo(0)
        assertThat(f.ratio).isNull()
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `upper uhf doesn't fire when lower channels dip as much`() {
        val f = SyntheticSession().apply {
            read("33.1", dips(), toMs = 2 * HOUR, tuner = 0, rf = 33, band = Band.UHF)
            read("20.1", dips(), toMs = 2 * HOUR, tuner = 1, rf = 20, band = Band.UHF)
        }.diagnose().find<Finding.UpperUhfInterference>()
        assertThat(f).isNull()
    }

    @Test
    fun `time of day fires when dips come at the same hours every day`() {
        // 2–4 PM UTC each day, three days.
        val afternoons = SignalPattern { t -> if ((t / HOUR) % 24 in 14..15) dips().at(t) else good.at(t) }
        val f = SyntheticSession().apply { read("9.1", afternoons, toMs = 72 * HOUR) }.diagnose().find<Finding.TimeOfDay>()
        assertThat(f!!.share).isEqualTo(1.0)
        assertThat(f.days).isEqualTo(3)
        assertThat(Math.floorMod(14 - f.startHour, 24)).isLessThan(4)
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `time of day doesn't fire on dips spread through the day`() {
        val f = SyntheticSession().apply { read("9.1", dips(everyMs = 2 * HOUR), toMs = 48 * HOUR) }.diagnose().find<Finding.TimeOfDay>()
        assertThat(f).isNull()
    }

    @Test
    fun `time of day doesn't fire on a session too short to see an hour twice`() {
        val evening = SignalPattern { t -> if (t / HOUR in 2..3) dips().at(t) else good.at(t) }
        assertThat(SyntheticSession().apply { read("9.1", evening, toMs = 20 * HOUR) }.diagnose().find<Finding.TimeOfDay>()).isNull()
    }

    @Test
    fun `wind fires when dips come with gusts`() {
        val gusty = SignalPattern { t -> if (t / HOUR in 2..3) dips().at(t) else good.at(t) }
        val f = SyntheticSession().apply {
            read("9.1", gusty, toMs = 6 * HOUR)
            (0 until 6).forEach { hour(it, gustKph = if (it in 2..3) 45.0 else 8.0) }
        }.diagnose().find<Finding.FollowsWeather>()
        assertThat(f!!.cause).isEqualTo(WeatherCause.WIND)
        assertThat(f.dips).isEqualTo(12)
        assertThat(f.totalDips).isEqualTo(12)
        assertThat(f.ratio).isNull()
        assertThat(f.peak).isEqualTo(DipStretch(SyntheticSession.START + 2 * HOUR, SyntheticSession.START + 4 * HOUR, 12))
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `wind gives the ratio when calm hours dip too`() {
        // Gusty hours: a dip every 10 min; calm hours: one an hour. 6× the rate.
        val pattern = SignalPattern { t -> if (t / HOUR in 2..3) dips().at(t) else dips(everyMs = HOUR).at(t) }
        val f = SyntheticSession().apply {
            read("9.1", pattern, toMs = 6 * HOUR)
            (0 until 6).forEach { hour(it, gustKph = if (it in 2..3) 45.0 else 8.0) }
        }.diagnose().find<Finding.FollowsWeather>()
        assertThat(f!!.ratio).isWithin(0.01).of(6.0)
    }

    @Test
    fun `wind doesn't fire when dips ignore the gusts`() {
        val f = SyntheticSession().apply {
            read("9.1", dips(), toMs = 6 * HOUR)
            (0 until 6).forEach { hour(it, gustKph = if (it in 2..3) 45.0 else 8.0) }
        }.diagnose().find<Finding.FollowsWeather>()
        assertThat(f).isNull()
    }

    @Test
    fun `wind doesn't fire without weather`() {
        val gusty = SignalPattern { t -> if (t / HOUR in 2..3) dips().at(t) else good.at(t) }
        assertThat(SyntheticSession().apply { read("9.1", gusty, toMs = 6 * HOUR) }.diagnose().find<Finding.FollowsWeather>()).isNull()
    }

    @Test
    fun `rain fires when dips come with rain`() {
        val wet = SignalPattern { t -> if (t / HOUR == 4L) dips().at(t) else good.at(t) }
        val f = SyntheticSession().apply {
            read("9.1", wet, toMs = 6 * HOUR)
            (0 until 6).forEach { hour(it, precipMm = if (it == 4) 2.5 else 0.0) }
        }.diagnose().find<Finding.FollowsWeather>()
        assertThat(f!!.cause).isEqualTo(WeatherCause.RAIN)
        assertThat(f.dips).isEqualTo(6)
    }

    @Test
    fun `rain doesn't fire when rain changes nothing`() {
        val f = SyntheticSession().apply {
            read("9.1", good, toMs = 6 * HOUR)
            (0 until 6).forEach { hour(it, precipMm = if (it == 4) 2.5 else 0.0) }
        }.diagnose().find<Finding.FollowsWeather>()
        assertThat(f).isNull()
    }

    @Test
    fun `heat fires when dips come in the hottest hours`() {
        val hot = SignalPattern { t -> if (t / HOUR >= 9) dips().at(t) else good.at(t) }
        val f = SyntheticSession().apply {
            read("9.1", hot, toMs = 12 * HOUR)
            (0 until 12).forEach { hour(it, tempC = it.toDouble()) }
        }.diagnose().find<Finding.FollowsWeather>()
        assertThat(f!!.cause).isEqualTo(WeatherCause.HEAT)
        assertThat(f.line).isEqualTo(9.0)
        assertThat(f.dips).isEqualTo(18)
    }

    @Test
    fun `heat doesn't fire with too few hours`() {
        val hot = SignalPattern { t -> if (t / HOUR >= 5) dips().at(t) else good.at(t) }
        val f = SyntheticSession().apply {
            read("9.1", hot, toMs = 6 * HOUR)
            (0 until 6).forEach { hour(it, tempC = it.toDouble()) }
        }.diagnose().find<Finding.FollowsWeather>()
        assertThat(f).isNull()
    }

    @Test
    fun `common path fires when every channel dips together`() {
        val f = SyntheticSession().apply {
            read("9.1", dips(), toMs = HOUR, tuner = 0)
            read("4.1", dips(), toMs = HOUR, tuner = 1)
        }.diagnose().find<Finding.CommonPath>()
        assertThat(f!!.sharedDips).isEqualTo(12)
        assertThat(f.totalDips).isEqualTo(12)
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `common path doesn't fire when channels dip at different times`() {
        val later = SignalPattern { t -> dips().at(t + 5 * MINUTE) }
        val f = SyntheticSession().apply {
            read("9.1", dips(), toMs = HOUR, tuner = 0)
            read("4.1", later, toMs = HOUR, tuner = 1)
        }.diagnose().find<Finding.CommonPath>()
        assertThat(f).isNull()
    }

    @Test
    fun `common path doesn't fire when channels were read in turn`() {
        val f = SyntheticSession().apply {
            read("9.1", dips(), toMs = HOUR, tuner = 0)
            read("4.1", dips(), fromMs = HOUR, toMs = 2 * HOUR, tuner = 1)
        }.diagnose().find<Finding.CommonPath>()
        assertThat(f).isNull()
    }

    @Test
    fun `one tuner fires when a channel dips on one tuner only`() {
        val f = SyntheticSession().apply {
            read("9.1", dips(), toMs = 2 * HOUR, tuner = 2)
            read("9.1", good, fromMs = 2 * HOUR, toMs = 4 * HOUR, tuner = 0)
        }.diagnose().find<Finding.OneTuner>()
        assertThat(f!!.tuner).isEqualTo(2)
        assertThat(f.dips).isEqualTo(12)
        assertThat(f.otherTuners).containsExactly(0)
        assertThat(f.otherDips).isEqualTo(0)
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `one tuner doesn't fire when the channel dips on every tuner`() {
        val f = SyntheticSession().apply {
            read("9.1", dips(), toMs = 2 * HOUR, tuner = 2)
            read("9.1", dips(), fromMs = 2 * HOUR, toMs = 4 * HOUR, tuner = 0)
        }.diagnose().find<Finding.OneTuner>()
        assertThat(f).isNull()
    }

    @Test
    fun `one tuner doesn't compare tuners of two units, so tuner 0 of each is two tuners`() {
        // Unit A's tuner 0 dips while unit B, on its tuner 1, reads the same channel cleanly: a unit difference.
        val s = SyntheticSession().apply {
            read("9.1", dips(), toMs = 2 * HOUR, tuner = 0, device = "A")
            read("9.1", good, toMs = 2 * HOUR, tuner = 1, device = "B")
        }.diagnose()
        assertThat(s.find<Finding.OneTuner>()).isNull()
        assertThat(s.find<Finding.OneUnit>()!!.deviceId).isEqualTo("A")
    }

    @Test
    fun `one tuner keyed by device names the device whose tuner dips`() {
        // Tuner 0 on both units; only B's dips. Grouped by number alone, A's clean tuner 0 would hide B's.
        val f = SyntheticSession().apply {
            read("9.1", good, toMs = 4 * HOUR, tuner = 0, device = "A")
            read("9.1", dips(), toMs = 2 * HOUR, tuner = 0, device = "B")
            read("9.1", good, fromMs = 2 * HOUR, toMs = 4 * HOUR, tuner = 2, device = "B")
        }.diagnose().find<Finding.OneTuner>()
        assertThat(f!!.deviceId).isEqualTo("B")
        assertThat(f.tuner).isEqualTo(0)
        assertThat(f.otherTuners).containsExactly(2)
        assertThat(f.dips).isEqualTo(12)
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `two-unit common path fires when both units dip together`() {
        val f = SyntheticSession().apply {
            read("9.1", dips(), toMs = HOUR, tuner = 2, device = "A")
            read("9.1", dips(), toMs = HOUR, tuner = 3, device = "B")
        }.diagnose().find<Finding.TwoUnitCommonPath>()
        assertThat(f!!.sharedDips).isEqualTo(12)
        assertThat(f.totalDips).isEqualTo(12)
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `two-unit common path widens its window to the poll interval and a second`() {
        // Polls 5 s apart, the second unit's a poll later: starts up to 5 s apart still count as together.
        val f = SyntheticSession(pollMs = 5_000).apply {
            read("9.1", dips(), toMs = HOUR, tuner = 2, device = "A")
            read("9.1", SignalPattern { t -> dips().at(t - 5_000) }, toMs = HOUR, tuner = 3, device = "B")
        }.diagnose().find<Finding.TwoUnitCommonPath>()
        assertThat(f!!.windowMs).isEqualTo(6_000)
        assertThat(f.sharedDips).isEqualTo(f.totalDips)
    }

    @Test
    fun `two-unit common path doesn't fire when the units dip at different times`() {
        val later = SignalPattern { t -> dips().at(t + 5 * MINUTE) }
        val s = SyntheticSession().apply {
            read("9.1", dips(), toMs = HOUR, tuner = 2, device = "A")
            read("9.1", later, toMs = HOUR, tuner = 3, device = "B")
        }.diagnose()
        assertThat(s.find<Finding.TwoUnitCommonPath>()).isNull()
        assertThat(s.find<Finding.CommonPath>()).isNull()
    }

    @Test
    fun `two-unit rules need both units on the same channel at the same time`() {
        // In turn, not side by side: nothing to compare.
        val inTurn = SyntheticSession().apply {
            read("9.1", dips(), toMs = 2 * HOUR, tuner = 2, device = "A")
            read("9.1", good, fromMs = 2 * HOUR, toMs = 4 * HOUR, tuner = 3, device = "B")
        }.diagnose()
        assertThat(inTurn.find<Finding.OneUnit>()).isNull()
        assertThat(inTurn.find<Finding.TwoUnitCommonPath>()).isNull()
        // Side by side on different channels (a whole-device watch's tuners, say): not a compare either.
        val apart = SyntheticSession().apply {
            read("9.1", dips(), toMs = 2 * HOUR, tuner = 2, device = "A")
            read("4.1", good, toMs = 2 * HOUR, tuner = 3, device = "B")
        }.diagnose()
        assertThat(apart.find<Finding.OneUnit>()).isNull()
    }

    @Test
    fun `one unit fires when only one unit dips`() {
        val f = SyntheticSession().apply {
            read("9.1", good, toMs = 2 * HOUR, tuner = 2, device = "A")
            read("9.1", dips(), toMs = 2 * HOUR, tuner = 3, device = "B")
        }.diagnose().find<Finding.OneUnit>()
        assertThat(f!!.deviceId).isEqualTo("B")
        assertThat(f.otherDeviceId).isEqualTo("A")
        assertThat(f.dips).isEqualTo(12)
        assertThat(f.otherDips).isEqualTo(0)
        assertThat(f.ratio).isNull()
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `one unit is only possible when the other unit dips too, and quiet when both dip alike`() {
        val rarely = dipping(good, bad, everyMs = 60 * MINUTE)
        val possible = SyntheticSession().apply {
            read("9.1", rarely, toMs = 2 * HOUR, tuner = 2, device = "A")
            read("9.1", dips(), toMs = 2 * HOUR, tuner = 3, device = "B")
        }.diagnose().find<Finding.OneUnit>()
        assertThat(possible!!.ratio).isWithin(0.01).of(6.0)
        assertThat(possible.strength).isEqualTo(Strength.POSSIBLE)
        val alike = SyntheticSession().apply {
            read("9.1", dips(), toMs = 2 * HOUR, tuner = 2, device = "A")
            read("9.1", dips(), toMs = 2 * HOUR, tuner = 3, device = "B")
        }.diagnose()
        assertThat(alike.find<Finding.OneUnit>()).isNull()
        assertThat(alike.find<Finding.TwoUnitCommonPath>()).isNotNull()
    }

    @Test
    fun `one unit needs an hour of each unit`() {
        val f = SyntheticSession().apply {
            read("9.1", good, toMs = 50 * MINUTE, tuner = 2, device = "A")
            read("9.1", dips(everyMs = 5 * MINUTE), toMs = 50 * MINUTE, tuner = 3, device = "B")
        }.diagnose().find<Finding.OneUnit>()
        assertThat(f).isNull()
    }

    @Test
    fun `flutter doesn't take two units a few points apart for swings`() {
        val f = SyntheticSession().apply {
            read("9.1", fixed(80, 86), toMs = 2 * HOUR, tuner = 2, device = "A")
            read("9.1", fixed(70, 62), toMs = 2 * HOUR, tuner = 3, device = "B")
        }.diagnose().find<Finding.Flutter>()
        assertThat(f).isNull()
    }

    @Test
    fun `impulse noise fires on symbol errors with quality still good`() {
        val f = SyntheticSession().apply { read("9.1", dipping(good, fixed(80, 86, seq = 82), 10 * MINUTE), toMs = HOUR) }
            .diagnose().find<Finding.ImpulseNoise>()
        assertThat(f!!.count).isEqualTo(6)
        assertThat(f.lowestSeq).isEqualTo(82)
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `impulse noise doesn't fire when quality falls too`() {
        assertThat(SyntheticSession().apply { read("9.1", dips(), toMs = HOUR) }.diagnose().find<Finding.ImpulseNoise>()).isNull()
    }

    @Test
    fun `flutter fires on repeated fast swings`() {
        val f = SyntheticSession(pollMs = 1_000).apply { read("4.1", SignalPattern.flutter(), toMs = HOUR) }.diagnose().find<Finding.Flutter>()
        assertThat(f!!.swings).isAtLeast(FlutterRule.LIKELY_SWINGS)
        assertThat(f.channels).containsExactly("4.1")
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `flutter doesn't fire on a slow fade`() {
        val f = SyntheticSession(pollMs = 1_000).apply { read("7.1", SignalPattern.slowFade(), toMs = HOUR) }.diagnose().find<Finding.Flutter>()
        assertThat(f).isNull()
    }

    @Test
    fun `not RF fires on device-unreachable events`() {
        val f = SyntheticSession().apply {
            read("9.1", good, toMs = HOUR)
            extraEvents += SignalEvent(EventKind.DEVICE_UNREACHABLE, null, START + 10 * MINUTE, START + 12 * MINUTE)
            extraEvents += SignalEvent(EventKind.DEVICE_UNREACHABLE, null, START + 30 * MINUTE, START + 31 * MINUTE)
        }.diagnose().find<Finding.NotRf>()
        assertThat(f!!.count).isEqualTo(2)
        assertThat(f.totalMs).isEqualTo(3 * MINUTE)
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `not RF doesn't fire on gaps`() {
        val f = SyntheticSession().apply {
            read("9.1", good, toMs = HOUR)
            extraEvents += SignalEvent(EventKind.GAP, "9.1", START + 10 * MINUTE, START + 12 * MINUTE)
        }.diagnose().find<Finding.NotRf>()
        assertThat(f).isNull()
    }

    /** On Wi-Fi at [atMs] after the start, on access point [bssid] at [rssi] dBm, 5 GHz. */
    private fun SyntheticSession.onWifi(atMs: Long, rssi: Int = -55, bssid: String? = "aa:bb:cc:00:00:01") {
        wifi += WifiSample(START + atMs, connected = true, rssiDbm = rssi, linkMbps = 433, frequencyMhz = 5_180, bssid = bssid)
    }

    private fun SyntheticSession.offWifi(atMs: Long) {
        wifi += WifiSample(START + atMs, connected = false)
    }

    /** An hour of 9.1, clean, and the tuner not answering from [atMs] after the start for a minute. */
    private fun SyntheticSession.outageAt(atMs: Long) {
        extraEvents += SignalEvent(EventKind.DEVICE_UNREACHABLE, null, START + atMs, START + atMs + MINUTE)
    }

    @Test
    fun `phone Wi-Fi takes an outage that starts as the phone goes off Wi-Fi, and Not RF doesn't`() {
        val findings = SyntheticSession().apply {
            read("9.1", good, toMs = HOUR)
            onWifi(0)
            // The phone drops off Wi-Fi 4 s before the first poll goes unanswered, and is back 2 minutes later.
            offWifi(20 * MINUTE - 4_000)
            outageAt(20 * MINUTE)
            onWifi(22 * MINUTE)
        }.diagnose()
        val f = findings.find<Finding.PhoneWifi>()!!
        assertThat(f.linked).isEqualTo(1)
        assertThat(f.outages).isEqualTo(1)
        assertThat(f.offWifi).isEqualTo(1)
        assertThat(f.windowMs).isEqualTo(PhoneWifiRule.WINDOW_MS)
        // Every outage of the session: Likely.
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
        assertThat(findings.find<Finding.NotRf>()).isNull()
    }

    @Test
    fun `phone Wi-Fi takes a roam to another access point, and a signal at the weak line`() {
        val f = SyntheticSession().apply {
            read("9.1", good, toMs = HOUR)
            onWifi(0)
            onWifi(10 * MINUTE + 3_000, bssid = "aa:bb:cc:00:00:02")
            outageAt(10 * MINUTE)
            onWifi(30 * MINUTE - 8_000, rssi = WifiSignal.WEAK_DBM, bssid = "aa:bb:cc:00:00:02")
            outageAt(30 * MINUTE)
            onWifi(31 * MINUTE, rssi = -60, bssid = "aa:bb:cc:00:00:02")
        }.diagnose().find<Finding.PhoneWifi>()!!
        assertThat(f.linked).isEqualTo(2)
        assertThat(f.roams).isEqualTo(1)
        assertThat(f.weak).isEqualTo(1)
        assertThat(f.offWifi).isEqualTo(0)
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `phone Wi-Fi is only possible for one outage of several, and Not RF keeps the rest`() {
        val findings = SyntheticSession().apply {
            read("9.1", good, toMs = HOUR)
            onWifi(0)
            offWifi(10 * MINUTE)
            outageAt(10 * MINUTE + 2_000)
            onWifi(12 * MINUTE)
            outageAt(40 * MINUTE)
            outageAt(50 * MINUTE)
        }.diagnose()
        val wifi = findings.find<Finding.PhoneWifi>()!!
        assertThat(wifi.linked).isEqualTo(1)
        assertThat(wifi.outages).isEqualTo(3)
        assertThat(wifi.strength).isEqualTo(Strength.POSSIBLE)
        val notRf = findings.find<Finding.NotRf>()!!
        assertThat(notRf.count).isEqualTo(2)
        assertThat(notRf.totalMs).isEqualTo(2 * MINUTE)
    }

    @Test
    fun `phone Wi-Fi doesn't fire on strong steady Wi-Fi, or trouble well away from the outage`() {
        val findings = SyntheticSession().apply {
            read("9.1", good, toMs = HOUR)
            onWifi(0)
            // A roam and a weak spell, both minutes before the outage, and the signal back to strong since.
            onWifi(5 * MINUTE, rssi = -84, bssid = "aa:bb:cc:00:00:02")
            onWifi(6 * MINUTE, rssi = -58, bssid = "aa:bb:cc:00:00:02")
            outageAt(20 * MINUTE)
            onWifi(20 * MINUTE + 30_000, rssi = -57, bssid = "aa:bb:cc:00:00:02")
        }.diagnose()
        assertThat(findings.find<Finding.PhoneWifi>()).isNull()
        assertThat(findings.find<Finding.NotRf>()!!.count).isEqualTo(1)
    }

    @Test
    fun `without a BSSID a roam can't be seen, and without samples the outage stays Not RF`() {
        val noBssid = SyntheticSession().apply {
            read("9.1", good, toMs = HOUR)
            onWifi(0, bssid = null)
            onWifi(10 * MINUTE + 1_000, rssi = -60, bssid = null)
            outageAt(10 * MINUTE)
        }.diagnose()
        assertThat(noBssid.find<Finding.PhoneWifi>()).isNull()
        assertThat(noBssid.find<Finding.NotRf>()).isNotNull()

        val old = SyntheticSession().apply {
            read("9.1", good, toMs = HOUR)
            outageAt(10 * MINUTE)
        }.diagnose()
        assertThat(old.find<Finding.PhoneWifi>()).isNull()
        assertThat(old.find<Finding.NotRf>()).isNotNull()
    }

    /** [readings] with running counters: the network count rising [netPerReading] a reading, the signal side [signalPerReading]. */
    private fun SyntheticSession.counted(netPerReading: Long, signalPerReading: Long = 0) {
        readings.replaceAll { r ->
            val i = (r.atMs - START) / pollMs
            r.copy(counters = ErrorCounters(transport = i * signalPerReading, crc = 0, resyncs = 0, network = i * netPerReading))
        }
    }

    @Test
    fun `network loss fires when the network side has the errors`() {
        val f = SyntheticSession().apply {
            read("9.1", good, toMs = HOUR)
            counted(netPerReading = 3)
        }.diagnose().find<Finding.NetworkLoss>()
        // 360 readings, the first a baseline.
        assertThat(f!!.networkErrors).isEqualTo(359 * 3L)
        assertThat(f.signalErrors).isEqualTo(0)
        assertThat(f.strength).isEqualTo(Strength.LIKELY)
    }

    @Test
    fun `network loss is only possible with antenna errors too, and quiet when they lead`() {
        val possible = SyntheticSession().apply {
            read("9.1", good, toMs = HOUR)
            counted(netPerReading = 20, signalPerReading = 1)
        }.diagnose().find<Finding.NetworkLoss>()
        assertThat(possible!!.strength).isEqualTo(Strength.POSSIBLE)

        val none = SyntheticSession().apply {
            read("9.1", good, toMs = HOUR)
            counted(netPerReading = 3, signalPerReading = 1)
        }.diagnose().find<Finding.NetworkLoss>()
        assertThat(none).isNull()
    }

    @Test
    fun `Likely findings come before Possible ones`() {
        val findings = SyntheticSession().apply {
            read("9.1", fixed(80, 87), toMs = HOUR, tuner = 0)
            read("4.1", fixed(80, 61), toMs = HOUR, tuner = 1)
            extraEvents += SignalEvent(EventKind.DEVICE_UNREACHABLE, null, START + 10 * MINUTE, START + 12 * MINUTE)
            extraEvents += SignalEvent(EventKind.DEVICE_UNREACHABLE, null, START + 30 * MINUTE, START + 31 * MINUTE)
        }.diagnose()
        assertThat(findings.map { it::class }).containsExactly(Finding.NotRf::class, Finding.OneChannelBad::class).inOrder()
    }

    /**
     * Milestone 5's "done when": the Demo tuner's wind channel, held for six hours, with a weather fixture from the
     * same gust schedule, gives "Dips follow the wind", Likely, first.
     */
    @Test
    fun `the Demo wind channel with its weather gives dips follow the wind`() {
        val s = SyntheticSession(pollMs = 1_000).apply {
            read("5.1", SignalPattern.windy(), toMs = 6 * HOUR)
            (0 until 6).forEach { hour(it, gustKph = Wind.gustKphAt(it * HOUR + HOUR / 2)) }
        }
        val findings = s.diagnose()
        val wind = findings.first() as Finding.FollowsWeather
        assertThat(wind.cause).isEqualTo(WeatherCause.WIND)
        assertThat(wind.strength).isEqualTo(Strength.LIKELY)
        assertThat(wind.dips).isEqualTo(wind.totalDips)
        assertThat(wind.peak!!.startMs).isEqualTo(SyntheticSession.START + 2 * HOUR)
        assertThat(wind.peak.endMs).isEqualTo(SyntheticSession.START + 4 * HOUR)
    }

    private companion object {
        const val START = SyntheticSession.START
    }
}
