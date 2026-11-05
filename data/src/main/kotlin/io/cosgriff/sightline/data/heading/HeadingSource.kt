package io.cosgriff.sightline.data.heading

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.cosgriff.sightline.analysis.Heading
import io.cosgriff.sightline.analysis.HeadingSmoother
import io.cosgriff.sightline.analysis.normalizeDegrees
import io.cosgriff.sightline.data.settings.Home
import io.cosgriff.sightline.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Where the phone points: degrees clockwise from north, from true north when [trueNorth], else magnetic. */
data class CompassHeading(val degrees: Double, val trueNorth: Boolean)

/** The phone's compass heading for Aim (SPEC Screens › Aim). */
interface HeadingSource {
    /** False on a phone without the sensor; Aim then plots nothing and says so. */
    val available: Boolean

    /** Headings while collected; null while the phone is held so no axis is level enough to say. */
    fun headings(): Flow<CompassHeading?>
}

/**
 * From the rotation vector sensor (platform sensors, no Play services: ADR-0002), smoothed. With a saved home, the
 * magnetic declination there (`GeomagneticField`) turns it to true north, which tower bearings are given in.
 */
@Singleton
class RotationVectorHeading @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : HeadingSource {
    private val sensorManager get() = context.getSystemService(SensorManager::class.java)
    private val sensor get() = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    override val available: Boolean get() = sensor != null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun headings(): Flow<CompassHeading?> =
        settings.settings.map { it.home }.distinctUntilChanged().flatMapLatest { home ->
            val declination = home?.let { declination(it) }
            sensorHeadings().map { magnetic ->
                magnetic?.let {
                    if (declination == null) CompassHeading(it, trueNorth = false)
                    else CompassHeading(normalizeDegrees(it + declination), trueNorth = true)
                }
            }
        }

    private fun declination(home: Home): Double =
        GeomagneticField(home.latitude.toFloat(), home.longitude.toFloat(), 0f, clock.millis()).declination.toDouble()

    private fun sensorHeadings(): Flow<Double?> {
        val manager = sensorManager ?: return flowOf(null)
        val rotation = sensor ?: return flowOf(null)
        return callbackFlow {
            val matrix = FloatArray(9)
            val smoother = HeadingSmoother()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    val raw = Heading.fromRotationMatrix(matrix)
                    if (raw == null) smoother.reset()
                    trySend(raw?.let(smoother::add))
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            manager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
            awaitClose { manager.unregisterListener(listener) }
        }
    }
}
