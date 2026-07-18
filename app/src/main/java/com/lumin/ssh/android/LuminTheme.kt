package com.lumin.ssh.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val THEME_DARK = "dark"
const val THEME_LIGHT = "light"
const val THEME_SYSTEM = "system"

fun normalizeAppTheme(theme: String?): String = when (theme) {
    THEME_LIGHT, THEME_DARK, THEME_SYSTEM -> theme
    else -> THEME_SYSTEM
}

/** Design System v2 palette — cold blue, dark/light. */
data class LuminPalette(
    val SurfaceBase: Color,
    val SurfaceRaised: Color,
    val SurfaceOverlay: Color,
    val SurfaceSunken: Color,
    val SurfaceHover: Color,
    val SurfaceActive: Color,
    val Border: Color,
    val BorderSubtle: Color,
    val BorderFocus: Color,
    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextMuted: Color,
    val Accent: Color,
    val AccentHover: Color,
    val AccentDim: Color,
    val Success: Color,
    val Danger: Color,
    val Warning: Color,
    val Info: Color,
    val TerminalBg: Color,
    val TerminalBar: Color,
    val TerminalKey: Color,
    val TerminalText: Color,
    val TerminalTextMuted: Color,
    val TerminalBorder: Color,
    val TerminalCursor: Color,
    val OnAccent: Color,
    val isDark: Boolean,
)

val LuminDarkPalette = LuminPalette(
    SurfaceBase = Color(0xFF0E1218),
    SurfaceRaised = Color(0xFF131922),
    SurfaceOverlay = Color(0xFF192030),
    SurfaceSunken = Color(0xFF1C2333),
    SurfaceHover = Color(0xFF262F40),
    SurfaceActive = Color(0xFF2F3A50),
    Border = Color(0xB338445A),
    BorderSubtle = Color(0x6638445A),
    BorderFocus = Color(0xFF4D9EFF),
    TextPrimary = Color(0xFFEAF0F7),
    TextSecondary = Color(0xFFD0D8E3),
    TextMuted = Color(0xFFB8C4D4),
    Accent = Color(0xFF4D9EFF),
    AccentHover = Color(0xFF69ADFF),
    AccentDim = Color(0x1F4D9EFF),
    Success = Color(0xFF3FB950),
    Danger = Color(0xFFFF6059),
    Warning = Color(0xFFE6AA32),
    Info = Color(0xFFB88AFF),
    // Cold-blue dark terminal — keys must contrast with the bar.
    TerminalBg = Color(0xFF0A0E14),
    TerminalBar = Color(0xFF111722),
    TerminalKey = Color(0xFF2A3448),
    TerminalText = Color(0xFFEAF0F7),
    TerminalTextMuted = Color(0xFFB8C4D4),
    TerminalBorder = Color(0x804D5D78),
    TerminalCursor = Color(0xFF4D9EFF),
    OnAccent = Color(0xFF041018),
    isDark = true,
)

// Align with desktop body.theme-light tokens.
val LuminLightPalette = LuminPalette(
    SurfaceBase = Color(0xFFF3F4F6),
    SurfaceRaised = Color(0xFFFFFFFF),
    SurfaceOverlay = Color(0xFFFFFFFF),
    SurfaceSunken = Color(0xFFE9ECEF),
    SurfaceHover = Color(0xFFE2E6EB),
    SurfaceActive = Color(0xFFD6DBE2),
    Border = Color(0x241C232D),
    BorderSubtle = Color(0x1A1C232D),
    BorderFocus = Color(0xFF2563EB),
    TextPrimary = Color(0xFF111827),
    TextSecondary = Color(0xFF334155),
    TextMuted = Color(0xFF6B7A8F),
    Accent = Color(0xFF2563EB),
    AccentHover = Color(0xFF1D4ED8),
    AccentDim = Color(0x142563EB),
    Success = Color(0xFF16A34A),
    Danger = Color(0xFFDC2626),
    Warning = Color(0xFFCA8A04),
    Info = Color(0xFF7C3AED),
    // Light terminal — soft paper + dark ink, keys slightly raised.
    TerminalBg = Color(0xFFF7F9FB),
    TerminalBar = Color(0xFFE8EEF5),
    TerminalKey = Color(0xFFFFFFFF),
    TerminalText = Color(0xFF0F172A),
    TerminalTextMuted = Color(0xFF64748B),
    TerminalBorder = Color(0x401C232D),
    TerminalCursor = Color(0xFF2563EB),
    OnAccent = Color(0xFFFFFFFF),
    isDark = false,
)

val LocalLuminPalette = staticCompositionLocalOf { LuminDarkPalette }

/** Theme-aware palette access used across UI. Must be read inside composition. */
object LuminColors {
    val SurfaceBase: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.SurfaceBase
    val SurfaceRaised: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.SurfaceRaised
    val SurfaceOverlay: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.SurfaceOverlay
    val SurfaceSunken: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.SurfaceSunken
    val SurfaceHover: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.SurfaceHover
    val SurfaceActive: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.SurfaceActive
    val Border: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.Border
    val BorderSubtle: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.BorderSubtle
    val BorderFocus: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.BorderFocus
    val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.TextPrimary
    val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.TextSecondary
    val TextMuted: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.TextMuted
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.Accent
    val AccentHover: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.AccentHover
    val AccentDim: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.AccentDim
    val Success: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.Success
    val Danger: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.Danger
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.Warning
    val Info: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.Info
    val TerminalBg: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.TerminalBg
    val TerminalBar: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.TerminalBar
    val TerminalKey: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.TerminalKey
    val TerminalText: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.TerminalText
    val TerminalTextMuted: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.TerminalTextMuted
    val TerminalBorder: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.TerminalBorder
    val TerminalCursor: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.TerminalCursor
    val OnAccent: Color @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.OnAccent
    val isDark: Boolean @Composable @ReadOnlyComposable get() = LocalLuminPalette.current.isDark
}

private fun luminMaterialScheme(palette: LuminPalette) = if (palette.isDark) {
    darkColorScheme(
        primary = palette.Accent,
        onPrimary = palette.OnAccent,
        primaryContainer = palette.AccentDim,
        onPrimaryContainer = palette.AccentHover,
        secondary = palette.Info,
        onSecondary = Color(0xFF160B2A),
        tertiary = palette.Success,
        onTertiary = Color(0xFF041208),
        background = palette.SurfaceBase,
        onBackground = palette.TextPrimary,
        surface = palette.SurfaceOverlay,
        onSurface = palette.TextPrimary,
        surfaceVariant = palette.SurfaceSunken,
        onSurfaceVariant = palette.TextSecondary,
        outline = palette.Border,
        outlineVariant = palette.BorderSubtle,
        error = palette.Danger,
        onError = Color.White,
        errorContainer = Color(0x33FF6059),
        onErrorContainer = Color(0xFFFFCDD2),
    )
} else {
    lightColorScheme(
        primary = palette.Accent,
        onPrimary = palette.OnAccent,
        primaryContainer = palette.AccentDim,
        onPrimaryContainer = palette.AccentHover,
        secondary = palette.Info,
        onSecondary = Color.White,
        tertiary = palette.Success,
        onTertiary = Color.White,
        background = palette.SurfaceBase,
        onBackground = palette.TextPrimary,
        surface = palette.SurfaceOverlay,
        onSurface = palette.TextPrimary,
        surfaceVariant = palette.SurfaceSunken,
        onSurfaceVariant = palette.TextSecondary,
        outline = palette.Border,
        outlineVariant = palette.BorderSubtle,
        error = palette.Danger,
        onError = Color.White,
        errorContainer = Color(0x22DC2626),
        onErrorContainer = Color(0xFF7F1D1D),
    )
}

private val LuminTypographyBase = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
)

val LuminCardShape = RoundedCornerShape(14.dp)
val LuminControlShape = RoundedCornerShape(10.dp)
val LuminChipShape = RoundedCornerShape(8.dp)

@Composable
fun LuminTheme(
    themeMode: String = THEME_SYSTEM,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (normalizeAppTheme(themeMode)) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> systemDark
    }
    val palette = if (dark) LuminDarkPalette else LuminLightPalette
    CompositionLocalProvider(LocalLuminPalette provides palette) {
        MaterialTheme(
            colorScheme = luminMaterialScheme(palette),
            typography = LuminTypographyBase,
            content = content,
        )
    }
}

@Composable
fun luminTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LuminColors.BorderFocus,
    unfocusedBorderColor = LuminColors.BorderSubtle,
    focusedContainerColor = LuminColors.SurfaceSunken,
    unfocusedContainerColor = LuminColors.SurfaceSunken,
    focusedLabelColor = LuminColors.Accent,
    unfocusedLabelColor = LuminColors.TextMuted,
    cursorColor = LuminColors.Accent,
    focusedTextColor = LuminColors.TextPrimary,
    unfocusedTextColor = LuminColors.TextPrimary,
)

@Composable
fun luminPrimaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = LuminColors.Accent,
    contentColor = LuminColors.OnAccent,
    disabledContainerColor = LuminColors.SurfaceHover,
    disabledContentColor = LuminColors.TextMuted,
)

@Composable
fun luminSecondaryButtonColors() = ButtonDefaults.outlinedButtonColors(
    contentColor = LuminColors.TextPrimary,
    disabledContentColor = LuminColors.TextMuted,
)

@Composable
fun luminDangerButtonColors() = ButtonDefaults.buttonColors(
    containerColor = LuminColors.Danger,
    contentColor = Color.White,
    disabledContainerColor = LuminColors.SurfaceHover,
    disabledContentColor = LuminColors.TextMuted,
)

@Composable
fun LuminCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = LuminCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (elevated) LuminColors.SurfaceRaised else LuminColors.SurfaceOverlay,
        ),
        border = BorderStroke(1.dp, LuminColors.BorderSubtle),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content,
    )
}

@Composable
fun LuminPageHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) {
            LuminSecondaryButton(onClick = onBack) {
                Text(backLabel ?: "←")
            }
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = LuminColors.TextPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LuminColors.Accent)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
fun LuminSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 4.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleMedium,
        color = LuminColors.TextSecondary,
    )
}

@Composable
fun LuminSettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(LuminControlShape)
            .clickable(onClick = onClick)
            .background(LuminColors.SurfaceSunken)
            .border(1.dp, LuminColors.BorderSubtle, LuminControlShape)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = LuminColors.TextPrimary)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LuminColors.TextMuted)
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            Text("›", color = LuminColors.TextMuted, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun LuminChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val bg = when {
        !enabled -> LuminColors.SurfaceSunken
        selected -> LuminColors.Accent
        else -> LuminColors.SurfaceSunken
    }
    val fg = when {
        !enabled -> LuminColors.TextMuted
        selected -> LuminColors.OnAccent
        else -> LuminColors.TextSecondary
    }
    Text(
        text = label,
        color = fg,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .clip(LuminChipShape)
            .background(bg)
            .then(if (selected) Modifier else Modifier.border(1.dp, LuminColors.BorderSubtle, LuminChipShape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
fun LuminPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = LuminControlShape,
        colors = luminPrimaryButtonColors(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun LuminSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = LuminControlShape,
        colors = luminSecondaryButtonColors(),
        border = BorderStroke(1.dp, LuminColors.Border),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun LuminDangerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = LuminControlShape,
        colors = luminDangerButtonColors(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun LuminTextAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(contentColor = LuminColors.Accent),
        content = content,
    )
}

@Composable
fun LuminDialogCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    LuminCard(modifier = modifier.fillMaxWidth(), elevated = true, content = content)
}

@Composable
fun LuminSpacer(height: Dp = 8.dp) {
    Spacer(Modifier.height(height))
}

@Composable
fun LuminHSpacer(width: Dp = 8.dp) {
    Spacer(Modifier.width(width))
}

@Composable
fun LuminEmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = LuminColors.TextMuted,
    )
}

@Composable
fun LuminEmptyState(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(LuminCardShape)
            .background(LuminColors.SurfaceOverlay)
            .border(1.dp, LuminColors.BorderSubtle, LuminCardShape)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LuminDot(LuminColors.Accent, 10.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, color = LuminColors.TextPrimary)
        if (!description.isNullOrBlank()) {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = LuminColors.TextMuted)
        }
        if (actionLabel != null && onAction != null) {
            LuminPrimaryButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun LuminDot(color: Color = LuminColors.Accent, size: Dp = 8.dp) {
    Spacer(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(color),
    )
}

@Composable
fun LuminPage(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(LuminColors.SurfaceBase)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun LuminDialogHeader(
    title: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    closeLabel: String = "关闭",
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), color = LuminColors.TextPrimary)
        if (onClose != null) {
            LuminSecondaryButton(onClick = onClose) { Text(closeLabel) }
        }
    }
}

@Composable
fun LuminIconChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    Text(
        text = label,
        color = if (selected) LuminColors.OnAccent else LuminColors.TextSecondary,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .clip(LuminChipShape)
            .background(if (selected) LuminColors.Accent else LuminColors.SurfaceSunken)
            .then(if (selected) Modifier else Modifier.border(1.dp, LuminColors.BorderSubtle, LuminChipShape))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
fun LuminSoftPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(LuminControlShape)
            .background(LuminColors.SurfaceSunken)
            .border(1.dp, LuminColors.BorderSubtle, LuminControlShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
