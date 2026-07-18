package com.lumin.ssh.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
fun DataManagementPage(
    store: LocalStore,
    onClose: () -> Unit,
    onExportPlain: () -> Unit,
    onExportCloudKey: () -> Unit,
    onExportCustom: () -> Unit,
    onImport: () -> Unit,
) {
    LuminDialogCard {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LuminDialogHeader(title = stringResource(R.string.data_management), onClose = onClose, closeLabel = stringResource(R.string.close))

            LuminSoftPanel {
                Text(stringResource(R.string.data_export_description), style = MaterialTheme.typography.bodySmall, color = LuminColors.TextMuted)
                LuminSecondaryButton(onClick = onExportPlain, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.export_plain_json))
                }
                LuminPrimaryButton(onClick = onExportCloudKey, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (store.hasRecoveryPassword()) stringResource(R.string.export_encrypted_reuse_recovery_password)
                        else stringResource(R.string.export_encrypted_requires_recovery_password),
                    )
                }
                LuminSecondaryButton(onClick = onExportCustom, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.export_encrypted_custom_password))
                }
            }

            LuminSoftPanel {
                Text(stringResource(R.string.data_import_description), style = MaterialTheme.typography.bodySmall, color = LuminColors.TextMuted)
                LuminPrimaryButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.import_from_file))
                }
            }
        }
    }
}
