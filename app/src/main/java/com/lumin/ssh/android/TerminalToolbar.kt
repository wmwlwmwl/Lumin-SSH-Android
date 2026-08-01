package com.lumin.ssh.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
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
    ctrlMode: Boolean,
    onCommandChange: (String) -> Unit,
    onSendPrompt: () -> Unit,
    onShortcut: (String) -> Unit,
    onToggleCtrlMode: () -> Unit,
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
                    // 粘滞 CTRL：按下后下一个字母转控制符（nano 的 ^O/^X/^K/^W 等全部可发）
                    TerminalShortcut("CTRL", Modifier.weight(1f), active = ctrlMode) { onToggleCtrlMode() }
                    TerminalShortcut("CTRL+C", Modifier.weight(1f), fontSize = 10.sp) { onShortcut("") }
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
                // 多行时按钮对齐顶部
                verticalAlignment = Alignment.Top,
            ) {
                // 支持多行脚本显示；发送用右侧按钮（回车=换行，不直接发送）
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 160.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(LuminColors.TerminalKey)
                        .border(1.dp, LuminColors.TerminalBorder, RoundedCornerShape(10.dp))
                        .padding(start = 10.dp, end = 18.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "> ",
                        color = LuminColors.TerminalCursor,
                        fontFamily = FontFamily.Monospace,
                    )
                    BasicTextField(
                        value = command,
                        onValueChange = onCommandChange,
                        // 多行：粘贴/输入脚本时完整显示，不只第一行
                        singleLine = false,
                        maxLines = 8,
                        textStyle = TextStyle(
                            color = LuminColors.TerminalText,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 20.sp,
                            letterSpacing = 0.3.sp,
                        ),
                        cursorBrush = SolidColor(LuminColors.TerminalCursor),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 20.dp, max = 140.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(end = 14.dp),
                    )
                }
                LuminPrimaryButton(enabled = command.isNotBlank(), onClick = { onSendPrompt() }) {
                    Text(stringResource(R.string.send))
                }
            }
        }
    }
}

@Composable
fun TerminalShortcut(
    label: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit? = null,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val resolvedFontSize = fontSize ?: when {
        label.length >= 6 -> 10.sp
        label.length >= 4 -> 11.sp
        else -> 13.sp
    }
    // 字号不同的键（10sp 的 HOME / CTRL+C）不能只靠内边距摆位，否则字越小坐得越高
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) LuminColors.Accent else LuminColors.TerminalKey)
            .border(1.dp, if (active) LuminColors.Accent else LuminColors.TerminalBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) LuminColors.OnAccent else LuminColors.TerminalText,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = resolvedFontSize),
        )
    }
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

/**
 * 粘滞 CTRL：把一个可打印字符转成控制符（^A..^Z 与 ^[ ^\ ^] ^^ ^_ ^? ^@）。
 * 只吃单字符输入；多字符（IME 联想、粘贴）或无对应控制符时返回 null，由调用方原样发送。
 */
internal fun ctrlKeyPayload(text: String): String? {
    if (text.length != 1) return null
    val code = when (val ch = text[0].uppercaseChar()) {
        in 'A'..'Z' -> ch - 'A' + 1
        '@', ' ' -> 0
        '[' -> 27
        '\\' -> 28
        ']' -> 29
        '^' -> 30
        '_' -> 31
        '?' -> 127
        else -> return null
    }
    return code.toChar().toString()
}
