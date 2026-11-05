package io.cosgriff.sightline.analysis

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** [deg] in 0 until 360. */
fun normalizeDegrees(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

/** [deg] rounded to a whole degree in 0..359 (359.6 is 0, not 360). */
fun roundDegrees(deg: Double): Int = normalizeDegrees(deg).roundToInt() % 360

/** The shortest signed turn from [from] to [to], in -180 until 180. */
fun turnDegrees(from: Double, to: Double): Double = normalizeDegrees(to - from + 180.0) - 180.0

/**
 * Compass heading from a rotation matrix, as Android's `SensorManager.getRotationMatrixFromVector` fills it: row
 * major, 3×3, mapping the phone's axes to the world's (x east, y north, z up).
 *
 * Aim is used with the phone held against the mast, often upright, where the usual azimuth (from the phone's top
 * edge) swings wildly. So the heading is of whichever axis is more level: the top edge (+y) when the phone lies
 * flat-ish, the back (−z, the way the camera looks) when it stands up. Null when neither is level enough to say.
 */
object Heading {
    /** Below this horizontal length (of a unit vector) an axis points too nearly up or down to give a heading. */
    private const val MIN_LEVEL = 0.35

    fun fromRotationMatrix(r: FloatArray): Double? {
        require(r.size == 9) { "a 3×3 rotation matrix" }
        // The phone's +y in world terms is the matrix's second column; its −z is the negated third column.
        val topEast = r[1].toDouble()
        val topNorth = r[4].toDouble()
        val backEast = -r[2].toDouble()
        val backNorth = -r[5].toDouble()
        val top = hypot(topEast, topNorth)
        val back = hypot(backEast, backNorth)
        val (east, north) = when {
            top >= back && top >= MIN_LEVEL -> topEast to topNorth
            back >= MIN_LEVEL -> backEast to backNorth
            else -> return null
        }
        return normalizeDegrees(Math.toDegrees(atan2(east, north)))
    }
}

/**
 * Smooths headings on the circle, so 359° and 1° average to 0° and not 180°. Exponential: each new heading moves
 * the estimate [alpha] of the way.
 */
class HeadingSmoother(private val alpha: Double = 0.25) {
    private var x = 0.0
    private var y = 0.0
    private var started = false

    fun add(deg: Double): Double {
        val rad = Math.toRadians(deg)
        if (!started) {
            x = cos(rad)
            y = sin(rad)
            started = true
        } else {
            x += alpha * (cos(rad) - x)
            y += alpha * (sin(rad) - y)
        }
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    fun reset() {
        started = false
    }
}
