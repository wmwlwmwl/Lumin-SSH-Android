package com.lumin.ssh.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ConfirmDialog(title: String, text: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        LuminDialogCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = LuminColors.TextPrimary)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = LuminColors.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LuminSecondaryButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                    LuminDangerButton(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
}

@Composable
fun FontSizePickerDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var value by remember { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss) {
        LuminDialogCard {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.font_size), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.volume_key_font_size_hint), color = LuminColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                LuminSoftPanel {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Text(
                            "−",
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (value > 1) LuminColors.Accent else LuminColors.Accent.copy(alpha = 0.3f),
                            modifier = Modifier
                                .clickable(enabled = value > 1) { value-- }
                                .padding(12.dp),
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (value > 1) (value - 1).toString() else " ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LuminColors.TextMuted,
                            )
                            Text(value.toString(), style = MaterialTheme.typography.headlineLarge, color = LuminColors.TextPrimary)
                            Text(
                                if (value < 30) (value + 1).toString() else " ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LuminColors.TextMuted,
                            )
                        }
                        Text(
                            "+",
                            style = MaterialTheme.typography.headlineMedium,
                            color = if (value < 30) LuminColors.Accent else LuminColors.Accent.copy(alpha = 0.3f),
                            modifier = Modifier
                                .clickable(enabled = value < 30) { value++ }
                                .padding(12.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LuminSecondaryButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                    LuminPrimaryButton(onClick = { onConfirm(value) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.confirm)) }
                }
            }
        }
    }
}

@Composable
fun PasswordPromptDialog(
    title: String,
    message: String? = null,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        LuminDialogCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = LuminColors.TextPrimary)
                if (!message.isNullOrBlank()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = LuminColors.TextSecondary)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    label = { Text(label) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = LuminControlShape,
                    colors = luminTextFieldColors(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    LuminSecondaryButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cancel)) }
                    LuminPrimaryButton(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(confirmLabel) }
                }
            }
        }
    }
}
