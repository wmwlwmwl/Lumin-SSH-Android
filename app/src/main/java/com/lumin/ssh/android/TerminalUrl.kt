package com.lumin.ssh.android

import java.net.URI

private val URL_IN_WORD = Regex(
    """(?i)((?:https?|ftp)://[^\s<>"']+|www\.[^\s<>"']+|mailto:[^\s<>"']+)""",
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
 * 从终端「词」（空格分隔）里抽出可打开的 URL。
 * 与 PC 常见 weblink 行为对齐：http(s)/ftp/mailto，以及裸 www.；去掉尾部标点。
 */
fun extractTerminalUrl(word: String): String? {
    if (word.isBlank()) return null
    val match = URL_IN_WORD.find(word) ?: return null
    return normalizeTerminalUrlMatch(match.value)
}

/** 扫描一整行，返回所有可点击 URL 的区间（用于高亮）。 */
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
