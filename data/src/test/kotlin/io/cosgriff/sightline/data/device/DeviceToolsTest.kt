package io.cosgriff.sightline.data.device

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.data.db.SightlineDatabase
import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.protocol.ControlError
import io.cosgriff.sightline.protocol.ControlResult
import io.cosgriff.sightline.protocol.Discovery
import io.cosgriff.sightline.protocol.FirmwareLookup
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.PlainDiscoveryEnvironment
import io.cosgriff.sightline.protocol.SiliconDustFirmware
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

/** Channel scan and firmware update against the fake tuner, with the busy guard in front of both. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DeviceToolsTest {
    @get:Rule val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SightlineDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state by lazy {
        AppStateRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("state.preferences_pb").also { it.delete() } })
    }
    private val fake = FakeHdhr().apply { restartMs = 300 }
    private val okHttp = OkHttpClient()
    private val http = HttpApi(okHttp)
    private val clock = Clock.fixed(Instant.ofEpochMilli(42_000), ZoneOffset.UTC)
    private val access by lazy { TunerAccess(http, state) }
    private val leases = TunerLeases()
    private val repository by lazy {
        DeviceRepository(
            db.devices(), db.channels(),
            Discovery(PlainDiscoveryEnvironment(listOf(InetAddress.getByName(fake.host))), port = fake.discoveryPort, includeGlobalBroadcast = false),
            http, access, leases, clock, Optional.empty(),
        )
    }
    private val tools by lazy {
        DeviceTools(repository, access, http, { SiliconDustFirmware(okHttp, fake.baseUrl) }, scope).apply {
            settleMs = 100
            pollMs = 100
            giveUpMs = 10_000
        }
    }

    private suspend fun device(): Device {
        repository.scan()
        return repository.all.first().single()
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        fake.close()
    }

    @Test
    fun `nothing blocks with every tuner idle`() = runBlocking<Unit> {
        assertThat(tools.block(device())).isNull()
    }

    @Test
    fun `another client or sightline itself on any tuner blocks`() = runBlocking<Unit> {
        val device = device()
        fake.takeTuner(2, "192.168.1.20")
        assertThat(tools.block(device)).isEqualTo(ToolsBlock.InUse(mapOf(2 to "192.168.1.20")))
        fake.freeTuner(2)

        access.control(device).set("/tuner1/lockkey", "5")
        leases.add(device.deviceId, 1)
        assertThat(tools.block(device)).isEqualTo(ToolsBlock.ThisApp(listOf(1)))
        access.control(device).set("/tuner1/lockkey", "none", 5)
    }

    @Test
    fun `an unreachable device blocks`() = runBlocking<Unit> {
        val device = device()
        fake.vanished = true
        assertThat(tools.block(device)).isEqualTo(ToolsBlock.Unreachable)
    }

    @Test
    fun `a running scan reads as scanning, not as a silent device`() = runBlocking<Unit> {
        val device = device()
        assertThat(tools.startScan(device, "Antenna")).isEqualTo(ScanStart.Started)
        // The device now answers every tuner get with `tuner busy`, as a FLEX 4K does.
        assertThat(access.control(device).get("/tuner2/status"))
            .isEqualTo(ControlResult.Rejected(ControlError.TunerBusy("ERROR: tuner busy")))
        assertThat(tools.block(device)).isEqualTo(ToolsBlock.Scanning)
        tools.abortScan(device)
        assertThat(tools.block(device)).isNull()
    }

    @Test
    fun `a scan is never started while a tuner is in use`() = runBlocking<Unit> {
        val device = device()
        fake.takeTuner(0, "192.168.1.20")
        assertThat(tools.startScan(device, "Antenna")).isInstanceOf(ScanStart.Blocked::class.java)
        assertThat(fake.scansStarted).isEqualTo(0)

        fake.freeTuner(0)
        assertThat(tools.startScan(device, "Antenna")).isEqualTo(ScanStart.Started)
        assertThat(tools.lineupStatus(device)?.scanning).isTrue()
        assertThat(tools.abortScan(device)).isTrue()
    }

    @Test
    fun `an update downloads, uploads, waits for the restart and records the new firmware`() = runBlocking<Unit> {
        val device = device()
        val name = tools.firmwareName(device)!!
        assertThat(tools.latestFirmware(device, name)).isEqualTo(FirmwareLookup.Latest("20261107"))

        assertThat(tools.startUpdate(device, "20261107")).isTrue()
        assertThat(tools.startUpdate(device, "20261107")).isFalse()
        val done = withTimeout(15_000) { tools.updates.first { it[device.deviceId]?.running == false } }
        assertThat(done[device.deviceId]).isEqualTo(UpdateStep.Done("20261107"))
        assertThat(fake.firmwareVersion).isEqualTo("20261107")
        assertThat(repository.device(device.deviceId)?.firmware).isEqualTo("20261107")
    }

    @Test
    fun `an update with a tuner in use never uploads`() = runBlocking<Unit> {
        val device = device()
        fake.takeTuner(3, "192.168.1.20")
        tools.startUpdate(device, "20261107")
        val done = withTimeout(15_000) { tools.updates.first { it[device.deviceId]?.running == false } }
        assertThat((done[device.deviceId] as UpdateStep.Failed).why).isEqualTo(UpdateFailure.BLOCKED)
        assertThat(fake.uploadsReceived).isEqualTo(0)
        assertThat(fake.firmwareVersion).isEqualTo("20260326")
    }

    @Test
    fun `a version SiliconDust doesn't have fails before anything is sent`() = runBlocking<Unit> {
        val device = device()
        tools.startUpdate(device, "20200101")
        val done = withTimeout(15_000) { tools.updates.first { it[device.deviceId]?.running == false } }
        assertThat((done[device.deviceId] as UpdateStep.Failed).why).isEqualTo(UpdateFailure.NOT_FOUND)
        assertThat(fake.uploadsReceived).isEqualTo(0)
        tools.dismissUpdate(device.deviceId)
        assertThat(tools.updates.value).isEmpty()
    }
}
