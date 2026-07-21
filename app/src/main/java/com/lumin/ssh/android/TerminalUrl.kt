package com.lumin.ssh.android

import com.termux.terminal.WcWidth
import java.net.URI

// 排除 ; | 等 shell 分隔符，避免 curl ...sh;else 把后续命令粘进链接
private val URL_IN_WORD = Regex(
    """(?i)((?:https?|ftp)://[^\s<>"';|]+|www\.[^\s<>"';|]+|mailto:[^\s<>"';|]+)""",
)

private val TRAILING_PUNCT = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>', '"', '\'')

data class TerminalUrlSpan(
    /** 行内起始下标（含） */
    val start: Int,
    /** 行内结束下标（不含） */
    val end: Int,
    /** 可打开的规范化 URL */
    val url: String,
)

/**
 * 字符串下标 → 终端列（宽字符占 2 列）。
 * [index] 为 UTF-16 下标；中途遇代理对按 code point 前进。
 * 返回该下标处字符的起始列（0-based）；index==length 时返回整串占用列数。
 */
fun stringIndexToColumn(text: String, index: Int): Int {
    if (text.isEmpty() || index <= 0) return 0
    val end = index.coerceAtMost(text.length)
    var col = 0
    var i = 0
    while (i < end) {
        val cp = text.codePointAt(i)
        val w = WcWidth.width(cp)
        if (w > 0) col += w
        i += Character.charCount(cp)
    }
    return col
}

/** 终端列（0-based）→ 不大于该列的最右字符串下标（用于点击命中）。 */
fun columnToStringIndex(text: String, column: Int): Int {
    if (text.isEmpty() || column <= 0) return 0
    var col = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val w = WcWidth.width(cp)
        val advance = if (w > 0) w else 0
        if (column < col + advance) return i
        col += advance
        i += Character.charCount(cp)
        if (column <= col) return i.coerceAtMost(text.length)
    }
    return text.length
}

/**
 * 从终端「词」（空格分隔）里抽出可打开的 URL。
 * 与 PC 常见 weblink 行为对齐：http(s)/ftp/mailto，以及裸 www.；去掉尾部标点。
 */
fun extractTerminalUrl(word: String): String? {
    if (word.isBlank()) return null
    val match = URL_IN_WORD.find(word) ?: return null
    return normalizeTerminalUrlMatch(match.value)
}

/** 扫描一整行（或已拼好的逻辑行），返回 URL 区间。 */
fun findTerminalUrlSpans(line: String): List<TerminalUrlSpan> {
    if (line.isBlank()) return emptyList()
    return URL_IN_WORD.findAll(line).mapNotNull { match ->
        val stripped = stripTrailingPunct(match.value)
        if (stripped.isBlank()) return@mapNotNull null
        val end = match.range.first + stripped.length
        val url = normalizeTerminalUrlMatch(stripped) ?: return@mapNotNull null
        TerminalUrlSpan(match.range.first, end, url)
    }.toList()
}

/**
 * 逻辑行分段（换行 wrap 续写）。rowLengths[i] 为该行字符数，
 * joined 下标可映射回 (row, col)。
 */
data class LogicalLineParts(
    val startRow: Int,
    val texts: List<String>,
) {
    val joined: String get() = texts.joinToString("")
    fun posOf(index: Int): Pair<Int, Int> {
        var rem = index
        texts.forEachIndexed { i, t ->
            if (rem < t.length) return startRow + i to rem
            rem -= t.length
        }
        val last = texts.lastIndex
        return startRow + last to texts[last].length.coerceAtLeast(0)
    }
}

/** 从任意行扩到逻辑行起止（getLineWrap(row)==true 表示该行末会续到下一行）。 */
fun expandLogicalLine(
    startHintRow: Int,
    maxRow: Int,
    minRow: Int,
    isWrapAt: (Int) -> Boolean,
    lineText: (Int) -> String,
): LogicalLineParts {
    var start = startHintRow.coerceIn(minRow, maxRow)
    while (start > minRow && isWrapAt(start - 1)) start--
    val texts = mutableListOf<String>()
    var row = start
    while (row <= maxRow) {
        texts += lineText(row)
        if (!isWrapAt(row)) break
        row++
    }
    return LogicalLineParts(start, texts)
}

private fun stripTrailingPunct(rawInput: String): String {
    var raw = rawInput
    while (raw.isNotEmpty() && raw.last() in TRAILING_PUNCT) {
        if (raw.last() == ')' && raw.count { it == '(' } >= raw.count { it == ')' }) break
        raw = raw.dropLast(1)
    }
    return raw
}

private fun normalizeTerminalUrlMatch(rawInput: String): String? {
    val raw = stripTrailingPunct(rawInput)
    if (raw.isBlank()) return null
    val withScheme = when {
        raw.startsWith("www.", ignoreCase = true) -> "https://$raw"
        else -> raw
    }
    val uri = try {
        URI(withScheme)
    } catch (_: Exception) {
        return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in setOf("http", "https", "ftp", "mailto")) return null
    if (scheme != "mailto" && uri.host.isNullOrBlank()) return null
    return withScheme
}
