package io.cosgriff.sightline.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class HeadingTest {
    /** Rotation matrix (phone → world, row major) for a phone turned [yawDeg] clockwise from north and tilted. */
    private fun flat(yawDeg: Double): FloatArray {
        val y = Math.toRadians(yawDeg)
        // Lying flat, top edge pointing at yawDeg: phone x → (cos, −sin), phone y → (sin, cos), z → up.
        return floatArrayOf(
            cos(y).toFloat(), sin(y).toFloat(), 0f,
            (-sin(y)).toFloat(), cos(y).toFloat(), 0f,
            0f, 0f, 1f,
        )
    }

    /** Standing upright, screen toward the user, back (camera) pointing at [yawDeg]. */
    private fun upright(yawDeg: Double): FloatArray {
        val y = Math.toRadians(yawDeg)
        // phone x → right of the viewer (yaw + 90°), phone y → up, phone z → toward the viewer (yaw + 180°).
        val xe = cos(y)
        val xn = -sin(y)
        val ze = -sin(y)
        val zn = -cos(y)
        return floatArrayOf(
            xe.toFloat(), 0f, ze.toFloat(),
            xn.toFloat(), 0f, zn.toFloat(),
            0f, 1f, 0f,
        )
    }

    @Test
    fun `flat phone heads where its top points`() {
        listOf(0.0, 45.0, 247.0, 359.0).forEach { yaw ->
            assertThat(Heading.fromRotationMatrix(flat(yaw))!!).isWithin(0.01).of(yaw)
        }
    }

    @Test
    fun `upright phone heads where its camera looks`() {
        listOf(0.0, 90.0, 180.0, 247.0).forEach { yaw ->
            assertThat(Heading.fromRotationMatrix(upright(yaw))!!).isWithin(0.01).of(yaw)
        }
    }

    @Test
    fun `no level axis gives no heading`() {
        // Top edge straight up and back straight down can't happen for a rigid phone, but a matrix with neither
        // axis level (both near vertical) must not give a number.
        val r = floatArrayOf(1f, 0f, 0f, 0f, 0.1f, 0.1f, 0f, 1f, 1f)
        assertThat(Heading.fromRotationMatrix(r)).isNull()
    }

    @Test
    fun `degrees wrap and turn the short way`() {
        assertThat(normalizeDegrees(-10.0)).isWithin(1e-9).of(350.0)
        assertThat(roundDegrees(359.6)).isEqualTo(0)
        assertThat(turnDegrees(350.0, 10.0)).isWithin(1e-9).of(20.0)
        assertThat(turnDegrees(10.0, 350.0)).isWithin(1e-9).of(-20.0)
    }

    @Test
    fun `smoothing across north stays near north`() {
        val s = HeadingSmoother(alpha = 0.5)
        s.add(358.0)
        val h = s.add(2.0)
        assertThat(turnDegrees(0.0, h)).isWithin(0.5).of(0.0)
    }
}
