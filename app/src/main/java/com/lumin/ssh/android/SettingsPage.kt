package com.lumin.ssh.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun SettingsPage(
    appLanguage: String,
    appTheme: String,
    terminalFontSize: Int,
    onBack: () -> Unit,
    onAppLanguageChange: (String) -> Unit,
    onAppThemeChange: (String) -> Unit,
    onOpenFontSize: () -> Unit,
    onOpenCredentials: () -> Unit,
    onOpenProxyManager: () -> Unit,
    onOpenQuickCommands: () -> Unit,
    onOpenDataManagement: () -> Unit,
    onOpenSyncSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(LuminColors.SurfaceBase)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LuminPageHeader(
            title = stringResource(R.string.settings_title),
            onBack = onBack,
            backLabel = stringResource(R.string.back),
        )

        LuminSectionTitle(stringResource(R.string.appearance))
        LuminCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.titleMedium, color = LuminColors.TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LuminChoiceChip(
                        label = stringResource(R.string.theme_system),
                        selected = appTheme == THEME_SYSTEM,
                        onClick = { onAppThemeChange(THEME_SYSTEM) },
                        modifier = Modifier.weight(1f),
                    )
                    LuminChoiceChip(
                        label = stringResource(R.string.theme_dark),
                        selected = appTheme == THEME_DARK,
                        onClick = { onAppThemeChange(THEME_DARK) },
                        modifier = Modifier.weight(1f),
                    )
                    LuminChoiceChip(
                        label = stringResource(R.string.theme_light),
                        selected = appTheme == THEME_LIGHT,
                        onClick = { onAppThemeChange(THEME_LIGHT) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        LuminSectionTitle(stringResource(R.string.interface_language))
        LuminCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.choose_interface_language), style = MaterialTheme.typography.bodySmall, color = LuminColors.TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LuminChoiceChip(
                        label = stringResource(R.string.simplified_chinese),
                        selected = appLanguage == APP_LANGUAGE_ZH_CN,
                        onClick = { onAppLanguageChange(APP_LANGUAGE_ZH_CN) },
                        modifier = Modifier.weight(1f),
                    )
                    LuminChoiceChip(
                        label = stringResource(R.string.english),
                        selected = appLanguage == APP_LANGUAGE_EN,
                        onClick = { onAppLanguageChange(APP_LANGUAGE_EN) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        LuminSectionTitle(stringResource(R.string.terminal_settings))
        LuminSettingsRow(
            title = stringResource(R.string.font_size),
            subtitle = stringResource(R.string.font_size_with_value, terminalFontSize),
            onClick = onOpenFontSize,
        )

        LuminSectionTitle(stringResource(R.string.tools_section))
        LuminSettingsRow(
            title = stringResource(R.string.credentials),
            onClick = onOpenCredentials,
        )
        LuminSettingsRow(
            title = stringResource(R.string.quick_command_management),
            onClick = onOpenQuickCommands,
        )
        LuminSettingsRow(
            title = stringResource(R.string.proxy_node_management),
            onClick = onOpenProxyManager,
        )

        LuminSectionTitle(stringResource(R.string.data_management))
        LuminSettingsRow(
            title = stringResource(R.string.data_management),
            onClick = onOpenDataManagement,
        )
        LuminSettingsRow(
            title = stringResource(R.string.sync_and_cloud),
            onClick = onOpenSyncSettings,
        )

        LuminSectionTitle(stringResource(R.string.about_title))
        LuminSettingsRow(
            title = stringResource(R.string.about_title),
            onClick = onOpenAbout,
        )
    }
}
