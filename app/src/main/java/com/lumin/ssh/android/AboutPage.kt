package com.lumin.ssh.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
@Composable
fun AboutPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionCode = remember(packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
    }
    val currentVersion = packageInfo.versionName?.takeIf { it.isNotBlank() } ?: "0.0.0"
    val androidRepo = stringResource(R.string.github_repo)
    val desktopRepo = stringResource(R.string.github_desktop_repo)
    val androidUrl = "https://github.com/$androidRepo"
    val desktopUrl = "https://github.com/$desktopRepo"

    var checkingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }

    fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun checkForUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        updateStatus = null
        updateInfo = null
        scope.launch {
            val result = UpdateChecker.check(currentVersion, androidRepo)
            checkingUpdate = false
            result.onSuccess { info ->
                updateInfo = info
                updateStatus = when {
                    info.hasUpdate -> context.getString(R.string.update_available, info.latestVersion)
                    else -> context.getString(R.string.update_already_latest, info.latestVersion.ifBlank { currentVersion })
                }
            }.onFailure {
                updateStatus = context.getString(R.string.update_check_failed, context.userErrorText(it))
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(LuminColors.SurfaceBase)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LuminPageHeader(
            title = stringResource(R.string.about_title),
            onBack = onBack,
            backLabel = stringResource(R.string.back),
        )

        LuminCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = LuminColors.TextPrimary)
                Text(
                    stringResource(R.string.about_version, packageInfo.versionName ?: stringResource(R.string.unknown), versionCode),
                    color = LuminColors.Accent,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium, color = LuminColors.TextSecondary)
            }
        }

        LuminCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.update_section), style = MaterialTheme.typography.titleMedium, color = LuminColors.TextPrimary)
                Text(stringResource(R.string.update_section_hint), style = MaterialTheme.typography.bodySmall, color = LuminColors.TextMuted)
                LuminPrimaryButton(
                    onClick = { checkForUpdate() },
                    enabled = !checkingUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (checkingUpdate) stringResource(R.string.checking_update)
                        else stringResource(R.string.check_update),
                    )
                }
                updateStatus?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (updateInfo?.hasUpdate == true) LuminColors.Accent else LuminColors.TextSecondary,
                    )
                }
                val info = updateInfo
                if (info?.hasUpdate == true) {
                    val apk = info.apkUrl
                    if (!apk.isNullOrBlank()) {
                        LuminPrimaryButton(
                            onClick = { openUrl(apk) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.download_update)) }
                    }
                    LuminSecondaryButton(
                        onClick = { openUrl(info.releaseUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.open_release_page)) }
                }
            }
        }

        LuminCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.android_project), style = MaterialTheme.typography.titleMedium, color = LuminColors.TextPrimary)
                Text(androidRepo, color = LuminColors.Accent, style = MaterialTheme.typography.bodyMedium)
                Text(androidUrl, color = LuminColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                LuminSecondaryButton(
                    onClick = { openUrl(androidUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.open_github)) }
            }
        }

        LuminCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.desktop_project), style = MaterialTheme.typography.titleMedium, color = LuminColors.TextPrimary)
                Text(desktopRepo, color = LuminColors.Accent, style = MaterialTheme.typography.bodyMedium)
                Text(desktopUrl, color = LuminColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                LuminSecondaryButton(
                    onClick = { openUrl(desktopUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.open_desktop_github)) }
            }
        }

        LuminCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sync_scope), style = MaterialTheme.typography.titleMedium, color = LuminColors.TextPrimary)
                Text(stringResource(R.string.sync_scope_description), style = MaterialTheme.typography.bodyMedium, color = LuminColors.TextSecondary)
                Text(stringResource(R.string.sync_scope_excludes), style = MaterialTheme.typography.bodySmall, color = LuminColors.TextMuted)
            }
        }

        LuminCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.license_title), style = MaterialTheme.typography.titleMedium, color = LuminColors.TextPrimary)
                Text(stringResource(R.string.license_summary), style = MaterialTheme.typography.bodyMedium, color = LuminColors.TextSecondary)
            }
        }
    }
}
