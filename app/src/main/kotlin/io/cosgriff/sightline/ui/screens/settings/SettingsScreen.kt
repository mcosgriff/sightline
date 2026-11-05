package io.cosgriff.sightline.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cosgriff.sightline.BuildConfig
import io.cosgriff.sightline.R
import io.cosgriff.sightline.analysis.Thresholds
import io.cosgriff.sightline.analysis.ToneRange
import io.cosgriff.sightline.data.settings.Settings
import io.cosgriff.sightline.data.settings.ThemeSetting
import io.cosgriff.sightline.data.settings.Units
import io.cosgriff.sightline.ui.components.Choice
import io.cosgriff.sightline.ui.components.Choices
import io.cosgriff.sightline.ui.components.rememberHomeFix
import io.cosgriff.sightline.ui.theme.Numbers
import io.cosgriff.sightline.ui.theme.sightlineColors

data class SettingsActions(
    val onBack: () -> Unit,
    val onTheme: (ThemeSetting) -> Unit,
    val onUnits: (Units) -> Unit,
    val onThresholds: (Thresholds) -> Unit,
    val onResetThresholds: () -> Unit,
    val onPollInterval: (Long) -> Unit,
    val onKeepScreenOn: (Boolean) -> Unit,
    val onToneRange: (ToneRange) -> Unit,
    val onRetention: (Int?) -> Unit,
    val onUseLocation: () -> Unit,
    val onForgetHome: () -> Unit,
)

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val useLocation = rememberHomeFix(viewModel::setHome)
    SettingsContent(
        settings = settings,
        version = BuildConfig.VERSION_NAME,
        actions = SettingsActions(
            onBack = onBack,
            onTheme = viewModel::setTheme,
            onUnits = viewModel::setUnits,
            onThresholds = viewModel::setThresholds,
            onResetThresholds = viewModel::resetThresholds,
            onPollInterval = viewModel::setPollInterval,
            onKeepScreenOn = viewModel::setKeepScreenOn,
            onToneRange = viewModel::setToneRange,
            onRetention = viewModel::setRetentionDays,
            onUseLocation = useLocation,
            onForgetHome = viewModel::clearHome,
        ),
    )
}

/**
 * Settings (SPEC Screens › 10): theme and units, the health thresholds, how often to read, keep screen on, the
 * tone's range, home for weather, how long readings are kept, and About with the licences. [settings] is null
 * until DataStore has answered.
 */
@Composable
fun SettingsContent(settings: Settings?, version: String, actions: SettingsActions, modifier: Modifier = Modifier) {
    var licence by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.settings_back))
            }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        if (settings == null) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Appearance(settings, actions)
            ThresholdSection(settings.thresholds, actions)
            Readings(settings, actions)
            Tone(settings.toneRange, actions)
            HomeSection(settings, actions)
            Retention(settings.retentionDays, actions.onRetention)
            About(version, onLicence = { licence = it })
            Spacer(Modifier.height(24.dp))
        }
    }
    licence?.let { asset -> LicenceDialog(asset, onDismiss = { licence = null }) }
}

@Composable
private fun Appearance(settings: Settings, actions: SettingsActions) {
    Section(stringResource(R.string.settings_appearance)) {
        Field(stringResource(R.string.settings_theme)) {
            Choices {
                ThemeSetting.entries.forEach { t -> Choice(stringResource(t.label), settings.theme == t) { actions.onTheme(t) } }
            }
        }
        Field(stringResource(R.string.settings_units)) {
            Choices {
                Choice(stringResource(R.string.settings_units_imperial), settings.units == Units.IMPERIAL) { actions.onUnits(Units.IMPERIAL) }
                Choice(stringResource(R.string.settings_units_metric), settings.units == Units.METRIC) { actions.onUnits(Units.METRIC) }
            }
        }
    }
}

private val ThemeSetting.label: Int
    @StringRes get() = when (this) {
        ThemeSetting.SYSTEM -> R.string.settings_theme_system
        ThemeSetting.LIGHT -> R.string.settings_theme_light
        ThemeSetting.DARK -> R.string.settings_theme_dark
        ThemeSetting.TRUE_BLACK -> R.string.settings_theme_true_black
    }

@Composable
private fun ThresholdSection(t: Thresholds, actions: SettingsActions) {
    val set = actions.onThresholds
    Section(stringResource(R.string.settings_thresholds), stringResource(R.string.settings_thresholds_subtitle)) {
        Stepper(stringResource(R.string.settings_snq_good), t.snqGood) { set(t.copy(snqGood = it)) }
        Stepper(stringResource(R.string.settings_snq_marginal), t.snqMarginal) { set(t.copy(snqMarginal = it)) }
        HorizontalDivider(color = MaterialTheme.sightlineColors.outline)
        Stepper(stringResource(R.string.settings_seq_good), t.seqGood) { set(t.copy(seqGood = it)) }
        Stepper(stringResource(R.string.settings_seq_marginal), t.seqMarginal) { set(t.copy(seqMarginal = it)) }
        HorizontalDivider(color = MaterialTheme.sightlineColors.outline)
        Stepper(stringResource(R.string.settings_ss_good), t.ssGood) { set(t.copy(ssGood = it)) }
        Stepper(stringResource(R.string.settings_ss_marginal), t.ssMarginal) { set(t.copy(ssMarginal = it)) }
        Stepper(stringResource(R.string.settings_ss_overload), t.ssOverload) { set(t.copy(ssOverload = it)) }
        TextButton(onClick = actions.onResetThresholds, enabled = t != Thresholds.DEFAULT) {
            Text(stringResource(R.string.settings_thresholds_reset))
        }
    }
}

@Composable
private fun Readings(settings: Settings, actions: SettingsActions) {
    Section(stringResource(R.string.settings_readings)) {
        Field(stringResource(R.string.settings_poll), stringResource(R.string.settings_poll_hint)) {
            Choices {
                Settings.POLL_INTERVALS_MS.forEach { ms ->
                    val label = if (ms <= 1_000) stringResource(R.string.settings_poll_1s) else stringResource(R.string.settings_poll_every, (ms / 1_000).toInt())
                    Choice(label, settings.pollIntervalMs == ms) { actions.onPollInterval(ms) }
                }
            }
        }
        SwitchRow(stringResource(R.string.settings_keep_screen_on), stringResource(R.string.settings_keep_screen_on_hint), settings.keepScreenOn, actions.onKeepScreenOn)
    }
}

@Composable
private fun Tone(range: ToneRange, actions: SettingsActions) {
    Section(stringResource(R.string.settings_tone), stringResource(R.string.settings_tone_subtitle)) {
        Stepper(stringResource(R.string.settings_tone_low), range.lowHz, step = TONE_STEP_HZ, unit = R.string.settings_hz) { actions.onToneRange(range.copy(lowHz = it)) }
        Stepper(stringResource(R.string.settings_tone_high), range.highHz, step = TONE_STEP_HZ, unit = R.string.settings_hz) { actions.onToneRange(range.copy(highHz = it)) }
    }
}

private const val TONE_STEP_HZ = 50

@Composable
private fun HomeSection(settings: Settings, actions: SettingsActions) {
    val home = settings.home
    Section(stringResource(R.string.settings_home), stringResource(R.string.settings_home_subtitle)) {
        Text(
            if (home == null) stringResource(R.string.settings_home_unset) else stringResource(R.string.settings_home_set, home.latitude, home.longitude),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions.onUseLocation) {
                Text(stringResource(if (home == null) R.string.settings_home_use_location else R.string.settings_home_update))
            }
            if (home != null) TextButton(onClick = actions.onForgetHome) { Text(stringResource(R.string.settings_home_forget)) }
        }
    }
}

@Composable
private fun Retention(days: Int?, onRetention: (Int?) -> Unit) {
    Section(stringResource(R.string.settings_retention), stringResource(R.string.settings_retention_subtitle)) {
        Choices {
            RETENTION_CHOICES.forEach { d -> Choice(pluralStringResource(R.plurals.settings_retention_days, d, d), days == d) { onRetention(d) } }
            Choice(stringResource(R.string.settings_retention_forever), days == null) { onRetention(null) }
        }
    }
}

private val RETENTION_CHOICES = listOf(30, 90, 365)

@Composable
private fun About(version: String, onLicence: (String) -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Section(stringResource(R.string.settings_about)) {
        Text(stringResource(R.string.settings_about_version, version), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.settings_about_body), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Text(stringResource(R.string.settings_about_source), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        HorizontalDivider(color = colors.outline)
        Text(stringResource(R.string.settings_licences), style = MaterialTheme.typography.titleSmall)
        LICENCES.forEach { (name, licence, asset) ->
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(if (asset != null) Modifier.clickable(onClickLabel = stringResource(R.string.settings_licence_read)) { onLicence(asset) } else Modifier)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(stringResource(licence), style = MaterialTheme.typography.bodySmall, color = if (asset != null) colors.accent else colors.textSecondary)
            }
        }
    }
}

/** What Sightline ships with: name, licence, and the bundled licence text where the licence asks for it. */
private val LICENCES = listOf(
    Triple(R.string.settings_licence_sightline, R.string.settings_licence_gpl, null),
    Triple(R.string.settings_licence_inter, R.string.settings_licence_ofl, "licences/inter-OFL.txt"),
    Triple(R.string.settings_licence_roboto_mono, R.string.settings_licence_ofl, "licences/roboto-mono-OFL.txt"),
    Triple(R.string.settings_licence_androidx, R.string.settings_licence_apache, null),
    Triple(R.string.settings_licence_kotlin, R.string.settings_licence_apache, null),
    Triple(R.string.settings_licence_dagger, R.string.settings_licence_apache, null),
    Triple(R.string.settings_licence_okhttp, R.string.settings_licence_apache, null),
    Triple(R.string.settings_licence_vico, R.string.settings_licence_apache, null),
)

@Composable
private fun LicenceDialog(asset: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = rememberSaveable(asset) { runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_licence_close)) } },
    )
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.sightlineColors
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = if (subtitle == null) 8.dp else 2.dp).semantics { heading() },
        )
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
        }
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(colors.surface, MaterialTheme.shapes.medium).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
private fun Field(label: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.sightlineColors.textSecondary)
        content()
    }
}

/** A number with − and + buttons; the repository keeps it in range and in order with the others. */
@Composable
private fun Stepper(label: String, value: Int, step: Int = 1, @StringRes unit: Int? = null, onChange: (Int) -> Unit) {
    val shown = unit?.let { stringResource(it, value) } ?: value.toString()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(value - step) }) {
            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.settings_decrease, label))
        }
        Text(shown, style = Numbers.info, modifier = Modifier.width(if (unit != null) 76.dp else 36.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { onChange(value + step) }) {
            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.settings_increase, label))
        }
    }
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.sightlineColors
    val state = stringResource(if (checked) R.string.settings_on else R.string.settings_off)
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.bg, uncheckedBorderColor = colors.outline),
        )
    }
}
