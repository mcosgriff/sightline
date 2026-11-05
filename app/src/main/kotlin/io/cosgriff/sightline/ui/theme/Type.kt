package io.cosgriff.sightline.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.cosgriff.sightline.R

private fun variable(res: Int, weights: List<FontWeight>) = FontFamily(
    weights.map { weight ->
        Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

/** Inter, bundled in `res/font` as one variable font (OFL, `assets/licences/`); each weight is a weight-axis setting. */
val Inter = variable(R.font.inter, listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold))

/**
 * Roboto Mono, bundled the same way. Every reading (ss, snq, seq, IPs, frequencies) is set in it, so digits are
 * all one width and don't jitter as they change.
 */
val RobotoMono = variable(R.font.roboto_mono, listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold))

/** The mockups' scale: 20 titles, 17 card titles, 14 body, 12 labels, 11 small labels. */
val SightlineTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Inter),
        displayMedium = displayMedium.copy(fontFamily = Inter),
        displaySmall = displaySmall.copy(fontFamily = Inter),
        headlineLarge = headlineLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
        titleSmall = titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = Inter),
        bodyMedium = bodyMedium.copy(fontFamily = Inter, fontSize = 14.sp),
        bodySmall = bodySmall.copy(fontFamily = Inter, fontSize = 12.sp),
        labelLarge = labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontFamily = Inter, fontSize = 12.sp),
        labelSmall = labelSmall.copy(fontFamily = Inter, fontSize = 11.sp),
    )
}

/** Number styles, all in [RobotoMono]. Not Material roles, so they live here rather than in [SightlineTypography]. */
object Numbers {
    /** The big gauge value on Live (SPEC: 56 sp). */
    val gaugeLive = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 60.sp)

    /** The one giant snq on Aim (SPEC: 120+ sp). */
    val aim = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 128.sp, lineHeight = 132.sp)

    /** IPs, device IDs, frequencies in running text. */
    val inline = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp)
}
