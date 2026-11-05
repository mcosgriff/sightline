package io.cosgriff.sightline.data.device

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import io.cosgriff.sightline.data.db.ChannelEntity
import io.cosgriff.sightline.data.db.SightlineDatabase
import io.cosgriff.sightline.data.settings.AppStateRepository
import io.cosgriff.sightline.data.settings.HeldLock
import io.cosgriff.sightline.data.settings.LiveSelection
import io.cosgriff.sightline.protocol.ControlClient
import io.cosgriff.sightline.protocol.Discovery
import io.cosgriff.sightline.protocol.HttpApi
import io.cosgriff.sightline.protocol.PlainDiscoveryEnvironment
import io.cosgriff.sightline.protocol.fake.FakeHdhr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/** Room, DataStore and the repositories on them, against the fake tuner. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StorageTest {
    @get:Rule val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), SightlineDatabase::class.java)
        .allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state by lazy {
        AppStateRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("state.preferences_pb").also { it.delete() } })
    }
    private val fake = FakeHdhr()
    private val http = HttpApi(OkHttpClient())
    private val clock = Clock.fixed(Instant.ofEpochMilli(42_000), ZoneOffset.UTC)
    private val access by lazy { TunerAccess(http, state) }
    private val repository by lazy {
        DeviceRepository(
            db.devices(), db.channels(),
            Discovery(PlainDiscoveryEnvironment(listOf(InetAddress.getByName(fake.host))), port = fake.discoveryPort, includeGlobalBroadcast = false),
            http, access, TunerLeases(), clock, Optional.empty(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        fake.close()
    }

    @Test
    fun `scan saves the device with its discover json`() = runBlocking<Unit> {
        assertThat(repository.scan()).containsExactly(fake.deviceIdHex)
        val device = repository.all.first().single()
        assertThat(device.friendlyName).isEqualTo("Demo tuner")
        assertThat(device.model).isEqualTo("HDFX-4K")
        assertThat(device.firmware).isEqualTo("20260326")
        assertThat(device.tunerCount).isEqualTo(4)
        assertThat(device.lastSeen).isEqualTo(42_000)
    }

    @Test
    fun `add by address finds the device directly`() = runBlocking<Unit> {
        val added = repository.addByAddress(" http://${fake.host}/ ") as AddResult.Added
        assertThat(added.device.deviceId).isEqualTo(fake.deviceIdHex)
        assertThat(repository.addByAddress("not a host")).isEqualTo(AddResult.BadAddress)
    }

    @Test
    fun `tuner use tells idle, sightline and other clients apart`() = runBlocking<Unit> {
        repository.scan()
        val device = repository.all.first().single()
        fake.takeTuner(1, "192.168.1.20")
        val held = TunerLeases()
        val repo = DeviceRepository(db.devices(), db.channels(), Discovery(PlainDiscoveryEnvironment()), http, access, held, clock, Optional.empty())
        access.control(device).set("/tuner2/lockkey", "5")
        held.add(device.deviceId, 2)
        assertThat(repo.tunerUse(device)).containsExactly(
            TunerUse.Idle, TunerUse.Other("192.168.1.20"), TunerUse.Sightline, TunerUse.Idle,
        ).inOrder()
        access.control(device).set("/tuner2/lockkey", "none", 5)
    }

    @Test
    fun `a new lineup keeps what tuning learned`() = runBlocking<Unit> {
        repository.scan()
        val device = repository.all.first().single()
        assertThat(repository.refreshLineup(device)).isTrue()
        val first = repository.channelList(device.deviceId)
        assertThat(first.map { it.guideNumber }).containsExactly("2.1", "4.1", "5.1", "7.1", "9.1", "14.1", "102.1").inOrder()
        repository.recordTuned(first[0], 7, 177_000_000, "VHF_HI", false)
        repository.refreshLineup(device)
        val again = repository.channelList(device.deviceId).first()
        assertThat(again.rfChannel).isEqualTo(7)
        assertThat(again.band).isEqualTo(io.cosgriff.sightline.protocol.Band.VHF_HI)
    }

    @Test
    fun `replacing a lineup drops channels no longer in it`() = runBlocking<Unit> {
        db.channels().replaceLineup("D", listOf(ChannelEntity("D", "1.1", "A", "u", 0), ChannelEntity("D", "2.1", "B", "u", 1)))
        db.channels().replaceLineup("D", listOf(ChannelEntity("D", "2.1", "B", "u", 0)))
        assertThat(db.channels().list("D").map { it.guideNumber }).containsExactly("2.1")
    }

    @Test
    fun `lock keys and the live selection are kept`() = runBlocking<Unit> {
        state.remember("ABC", 2, 77)
        state.remember("ABC", 3, 78)
        state.forget("ABC", 3)
        assertThat(state.heldLocks()).containsExactly(HeldLock("ABC", 2, 77))
        state.selectForLive("ABC", "9.1")
        assertThat(state.liveSelection.first()).isEqualTo(LiveSelection("ABC", "9.1"))
    }

    @Test
    fun `stale locks are released at start, and only ours`() = runBlocking<Unit> {
        repository.scan()
        val old = ControlClient(InetAddress.getByName(fake.host), fake.controlPort)
        old.set("/tuner1/lockkey", "4242")
        old.set("/tuner1/vchannel", "2.1", 4242)
        old.close()
        state.remember(fake.deviceIdHex, 1, 4242)
        fake.takeTuner(3, "192.168.1.30")
        state.remember(fake.deviceIdHex, 3, 999) // wrong key: someone else's tuner now
        state.remember("GONE0000", 0, 1)

        StaleLockRecovery(state, repository, access, scope).recover()

        assertThat(fake.isIdle(1)).isTrue()
        assertThat(fake.lockKeyOf(3)).isEqualTo(-1L)
        assertThat(state.heldLocks().map { it.deviceId to it.tuner }).doesNotContain(fake.deviceIdHex to 1)
        assertThat(state.heldLocks().map { it.deviceId }).doesNotContain("GONE0000")
    }
}
