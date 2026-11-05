package io.cosgriff.sightline.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.cosgriff.sightline.R
import io.cosgriff.sightline.protocol.Band

/** VHF-lo, VHF-hi or UHF, as every screen names the band. */
@Composable
fun bandLabel(band: Band): String = stringResource(
    when (band) {
        Band.VHF_LO -> R.string.band_vhf_lo
        Band.VHF_HI -> R.string.band_vhf_hi
        Band.UHF -> R.string.band_uhf
    },
)
