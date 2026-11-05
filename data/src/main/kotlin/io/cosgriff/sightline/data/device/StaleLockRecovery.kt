package io.cosgriff.sightline.data.device

import io.cosgriff.sightline.data.di.ApplicationScope
import io.cosgriff.sightline.data.settings.AppStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * At app start, releases every tuner a killed process left claimed (SPEC "Polling loop", GLOSSARY "Stale lock").
 * A key whose device is gone, or that the tuner no longer knows, is forgotten; the device refuses the key on a
 * tuner someone else has since taken, so this never frees another client's tuner.
 */
@Singleton
class StaleLockRecovery @Inject constructor(
    private val state: AppStateRepository,
    private val repository: DeviceRepository,
    private val access: TunerAccess,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { recover() }
    }

    suspend fun recover() {
        for (lock in state.heldLocks()) {
            val device = repository.device(lock.deviceId)
            if (device == null) {
                state.forget(lock.deviceId, lock.tuner)
                continue
            }
            // Keeps the key when the device doesn't answer, to try again next start.
            access.sessions(device).releaseStale(lock.tuner, lock.lockKey)
        }
    }
}
