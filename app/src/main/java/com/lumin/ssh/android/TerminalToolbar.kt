package com.lumin.ssh.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalToolbar(
    showShortcutBar: Boolean,
    showInputBar: Boolean,
    command: String,
    onCommandChange: (String) -> Unit,
    onSendPrompt: () -> Unit,
    onShortcut: (String) -> Unit,
    onToggleInputBar: () -> Unit,
    onOpenQuickCommands: () -> Unit,
) {
    Column(Modifier.imePadding()) {
        if (showShortcutBar) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(LuminColors.TerminalBar)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TerminalShortcut(stringResource(R.string.terminal_command), Modifier.weight(1f)) { onOpenQuickCommands() }
                    TerminalShortcut("ESC", Modifier.weight(1f)) { onShortcut("") }
                    TerminalShortcut("TAB", Modifier.weight(1f)) { onShortcut("\t") }
                    TerminalShortcut("↑", Modifier.weight(1f)) { onShortcut("[A") }
                    TerminalShortcut("HOME", Modifier.weight(1f), fontSize = 10.sp) { onShortcut("[H") }
                    TerminalShortcut("END", Modifier.weight(1f)) { onShortcut("[F") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TerminalShortcut("CTRL+C", Modifier.weight(1f), fontSize = 10.sp) { onShortcut("") }
                    TerminalShortcut("CTRL+D", Modifier.weight(1f), fontSize = 10.sp) { onShortcut("") }
                    TerminalShortcut("←", Modifier.weight(1f)) { onShortcut("[D") }
                    TerminalShortcut("↓", Modifier.weight(1f)) { onShortcut("[B") }
                    TerminalShortcut("→", Modifier.weight(1f)) { onShortcut("[C") }
                    TerminalShortcut(stringResource(R.string.terminal_input), Modifier.weight(1f)) { onToggleInputBar() }
                }
            }
        }
        if (showInputBar) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(LuminColors.TerminalBg)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = command,
                    onValueChange = onCommandChange,
                    singleLine = true,
                    textStyle = TextStyle(color = LuminColors.TerminalText, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(LuminColors.TerminalCursor),
                    decorationBox = { innerTextField ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(LuminColors.TerminalKey)
                                .border(1.dp, LuminColors.TerminalBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("> ", color = LuminColors.TerminalCursor, fontFamily = FontFamily.Monospace)
                            innerTextField()
                        }
                    },
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            onSendPrompt()
                            true
                        } else {
                            false
                        }
                    },
                )
                LuminPrimaryButton(enabled = command.isNotBlank(), onClick = { onSendPrompt() }) {
                    Text(stringResource(R.string.send))
                }
            }
        }
    }
}

@Composable
fun TerminalShortcut(label: String, modifier: Modifier = Modifier, fontSize: TextUnit? = null, onClick: () -> Unit) {
    val resolvedFontSize = fontSize ?: when {
        label.length >= 6 -> 10.sp
        label.length >= 4 -> 11.sp
        else -> 13.sp
    }
    Text(
        label,
        color = LuminColors.TerminalText,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(LuminColors.TerminalKey)
            .border(1.dp, LuminColors.TerminalBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = resolvedFontSize),
    )
}

internal fun sanitizeSoftKeyboardInput(text: String): String {
    return buildString {
        text.forEach { char ->
            when (char) {
                // ponytail: pass through Unicode (IME input like Chinese), strip only true control chars
                '\r', '\n', '\t', '', in ' '..'~', in ' '..'￿' -> append(char)
                else -> Unit
            }
        }
    }
}
