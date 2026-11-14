package io.cosgriff.sightline.protocol

import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/** The device's own channel scan, SiliconDust firmware files, and the firmware upload, against [FakeHdhr]. */
class DeviceToolsProtocolTest {
    private val now = AtomicLong(1_000_000)
    private val fake = FakeHdhr(clock = now::get).apply { restartMs = 300 }
    private val okHttp = OkHttpClient()
    private val http = HttpApi(okHttp)
    private val firmware = SiliconDustFirmware(okHttp, fake.baseUrl)
    private val control = ControlClient(InetAddress.getByName(fake.host), fake.controlPort, 2.seconds)

    @After
    fun tearDown() {
        control.close()
        fake.close()
    }

    @Test
    fun `a channel scan starts, reports progress, and can be stopped`() = runBlocking<Unit> {
        val idle = (http.lineupStatus(fake.baseUrl) as HttpResult.Ok).value
        assertThat(idle.canScan).isTrue()
        assertThat(idle.sourceList).containsExactly("Antenna", "Cable").inOrder()

        assertThat(http.startLineupScan(fake.baseUrl, "Antenna")).isEqualTo(HttpResult.Ok(Unit))
        now.addAndGet(fake.scanMs / 2)
        val half = (http.lineupStatus(fake.baseUrl) as HttpResult.Ok).value
        assertThat(half.scanning).isTrue()
        assertThat(half.progress).isEqualTo(50)
        assertThat(half.found).isGreaterThan(0)

        assertThat(http.abortLineupScan(fake.baseUrl)).isEqualTo(HttpResult.Ok(Unit))
        assertThat((http.lineupStatus(fake.baseUrl) as HttpResult.Ok).value.scanning).isFalse()
        assertThat(fake.scansStarted).isEqualTo(1)
    }

    @Test
    fun `a scan of a source the device doesn't offer is refused`() = runBlocking<Unit> {
        assertThat(http.startLineupScan(fake.baseUrl, "Satellite")).isEqualTo(HttpResult.Failed(HttpFailure.Status(400)))
        assertThat(fake.scansStarted).isEqualTo(0)
    }

    @Test
    fun `no scan is possible with every tuner in use`() = runBlocking<Unit> {
        (0 until fake.tunerCount).forEach { fake.takeTuner(it, "192.0.2.9") }
        assertThat((http.lineupStatus(fake.baseUrl) as HttpResult.Ok).value.canScan).isFalse()
    }

    @Test
    fun `the newest firmware is named by the redirect, without downloading it`() = runBlocking<Unit> {
        assertThat(firmware.latest(FakeHdhr.FIRMWARE_NAME)).isEqualTo(FirmwareLookup.Latest("20261107"))
        assertThat(firmware.latest("hdhomerun_other")).isEqualTo(FirmwareLookup.UnknownFirmware)
        // A name from the device is never put in a URL unless it's plain.
        assertThat(firmware.latest("../../etc")).isEqualTo(FirmwareLookup.BadName)
        assertThat(firmware.latest("x?y=1")).isEqualTo(FirmwareLookup.BadName)
    }

    @Test
    fun `downloads a firmware file with progress`() = runBlocking<Unit> {
        var last = 0L
        val file = firmware.download(FakeHdhr.FIRMWARE_NAME, "20261107") { read, _ -> last = read }
        file as FirmwareDownload.Ok
        assertThat(file.image).isEqualTo(FakeHdhr.firmwareImage(version = "20261107"))
        assertThat(last).isEqualTo(file.image.size.toLong())
        assertThat(firmware.download(FakeHdhr.FIRMWARE_NAME, "20200101")).isEqualTo(FirmwareDownload.NotFound)
        assertThat(firmware.download(FakeHdhr.FIRMWARE_NAME, "../x")).isEqualTo(FirmwareDownload.BadName)
    }

    @Test
    fun `an accepted upload restarts the device on the new firmware`() = runBlocking<Unit> {
        val image = FakeHdhr.firmwareImage(version = "20261107")
        var sent = 0
        assertThat(control.upgrade(image) { sent = it }).isEqualTo(UpgradeResult.Accepted)
        assertThat(sent).isEqualTo(image.size)
        assertThat(fake.uploadsReceived).isEqualTo(1)

        // Off the network while it restarts, then back on the new version.
        val info = withRetries { (http.deviceInfo(fake.baseUrl) as? HttpResult.Ok)?.value?.takeIf { it.firmwareVersion == "20261107" } }
        assertThat(info).isNotNull()
    }

    @Test
    fun `a wrong image is refused and nothing changes`() = runBlocking<Unit> {
        val result = control.upgrade(ByteArray(5_000) { 1 })
        assertThat(result).isEqualTo(UpgradeResult.Rejected("ERROR: invalid image"))
        assertThat(fake.firmwareVersion).isEqualTo("20260326")
        // The connection is still usable for gets afterwards.
        assertThat(control.get("/sys/version")).isEqualTo(ControlResult.Value("20260326"))
    }

    @Test
    fun `upload pieces carry big-endian offsets and the end marker is all ones`() {
        val frame = Packet.frame(PacketType.UPGRADE_REQUEST, byteArrayOf(0, 0, 4, 0, 9))
        val unframed = Packet.unframe(frame) as FrameResult.Ok
        assertThat(unframed.type).isEqualTo(PacketType.UPGRADE_REQUEST)
        assertThat(unframed.body).isEqualTo(byteArrayOf(0, 0, 4, 0, 9))
        assertThat(frame.copyOfRange(0, 4)).isEqualTo(byteArrayOf(0, 6, 0, 5))
    }

    @Test
    fun `firmware versions compare by build date`() {
        assertThat(FirmwareVersions.isNewer("20261107", "20260326")).isTrue()
        assertThat(FirmwareVersions.isNewer("20260326", "20261107")).isFalse()
        assertThat(FirmwareVersions.isNewer("20261107", "20261107")).isFalse()
        assertThat(FirmwareVersions.isNewer("20120705", "20120704beta1")).isTrue()
        assertThat(FirmwareVersions.isNewer("unknown", "20261107")).isFalse()
        assertThat(SiliconDustFirmware.versionIn("hdhomerun_dvr_atsc3_firmware_20261107.bin", "hdhomerun_dvr_atsc3"))
            .isEqualTo("20261107")
        assertThat(SiliconDustFirmware.versionIn("hdhomerun5_firmware_20261107.bin", "hdhomerun_dvr_atsc3")).isNull()
    }

    private suspend fun <T> withRetries(get: suspend () -> T?): T? {
        repeat(50) {
            get()?.let { return it }
            delay(100)
        }
        return null
    }
}
