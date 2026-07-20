package com.lumin.ssh.android

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import kotlin.math.hypot
import kotlin.math.max

class TermuxTerminalSurface(context: Context) : View(context) {
    private var textSizePx = 20
    private var renderer = TerminalRenderer(textSizePx, Typeface.MONOSPACE)
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = onInput(String(data, offset, count))
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = invalidate()
    }
    private val client = LuminTerminalSessionClient { invalidate() }

    var onInput: (String) -> Unit = {}
    var onResize: (Int, Int) -> Unit = { _, _ -> }
    var onTap: () -> Unit = {}
    var onSaveTranscript: (String, String) -> Unit = { _, _ -> }
    var sessionHost: String = "terminal"
    private var emulator: TerminalEmulator? = null
    private var topRow = 0
    private var lastY = 0f
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var copyScreenStep = 0
    private var selectionStart: Pair<Int, Int>? = null
    private var selectionEnd: Pair<Int, Int>? = null
    private val pending = ArrayList<ByteArray>()
    private val tapSlopPx = 24f * resources.displayMetrics.density
    // Defaults match LuminDarkPalette terminal tokens until setSurfaceColors() runs.
    private var surfaceBackgroundColor: Int = Color.rgb(10, 14, 20)
    private var defaultForegroundColor: Int = Color.rgb(234, 240, 247)
    private var defaultCursorColor: Int = Color.rgb(77, 158, 255)
    private val selectionPaint = Paint().apply { color = Color.argb(110, 244, 67, 54) }
    // 链接高亮：只画细下划线，不铺底（铺底会挡字）
    private val linkUnderlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(77, 158, 255)
        strokeWidth = 1.25f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 32f
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    fun setSurfaceColors(background: Int, foreground: Int, cursor: Int) {
        surfaceBackgroundColor = background
        defaultForegroundColor = foreground
        defaultCursorColor = cursor
        applyDefaultColors()
        invalidate()
    }

    private fun applyDefaultColors() {
        val colors = emulator?.mColors?.mCurrentColors ?: return
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = surfaceBackgroundColor
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = defaultForegroundColor
        colors[TextStyle.COLOR_INDEX_CURSOR] = defaultCursorColor
    }

    /** 用户字号档位 1–30，按 density 转像素（上限 96），保证 13–30 可继续放大。 */
    fun setFontSize(fontSize: Int) {
        val level = fontSize.coerceIn(1, 30)
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        textSizePx = (level * density).toInt().coerceIn(1, 96)
        renderer = TerminalRenderer(textSizePx, Typeface.MONOSPACE)
        val current = emulator
        if (current == null) {
            requestLayout()
        } else {
            val columns = max(20, (width / renderer.fontWidth).toInt())
            val rows = max(5, height / renderer.fontLineSpacing)
            current.resize(columns, rows, renderer.fontWidth.toInt(), renderer.fontLineSpacing)
            onResize(columns, rows)
            invalidate()
        }
    }

    fun append(data: ByteArray) {
        val current = emulator
        if (current == null) {
            pending.add(data)
            return
        }
        current.append(data, data.size)
        topRow = 0
        postInvalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val columns = max(20, (w / renderer.fontWidth).toInt())
        val rows = max(5, h / renderer.fontLineSpacing)
        val current = emulator
        if (current == null) {
            emulator = TerminalEmulator(output, columns, rows, renderer.fontWidth.toInt(), renderer.fontLineSpacing, 4000, client)
            applyDefaultColors()
            pending.forEach { emulator?.append(it, it.size) }
            pending.clear()
        } else {
            current.resize(columns, rows, renderer.fontWidth.toInt(), renderer.fontLineSpacing)
        }
        onResize(columns, rows)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(surfaceBackgroundColor)
        val current = emulator ?: return
        renderer.render(current, canvas, topRow, -1, -1, -1, -1)
        drawLinkHighlights(canvas, current)
        drawSelection(canvas)
        drawCopyHint(canvas)
    }

    /**
     * 当前输入逻辑行起始（含 wrap 的上键历史）。该行及之后不识别链接，
     * 只有已经滚到输出区的内容才可点/高亮。
     */
    private fun inputStartRow(emulator: TerminalEmulator): Int {
        val screen = emulator.screen
        var row = emulator.cursorRow
        while (row > 0) {
            // 上一行若换行续写，则仍属当前输入
            if (!runCatching { screen.getLineWrap(row - 1) }.getOrDefault(false)) break
            row--
        }
        return row
    }

    /**
     * 可见行扫描 URL，只画下划线（不铺底）。
     *
     * 不用 renderer 基线推算（容易压进 g/p/q 下伸部），改按可见行格子：
     *   行 i 顶 = i * lineHeight，行底 = (i+1)*lineHeight
     * 线画在行底下方 1～2dp（两行之间的缝里），绝不与字形重叠。
     */
    private fun drawLinkHighlights(canvas: Canvas, emulator: TerminalEmulator) {
        val screen = emulator.screen
        val cols = emulator.mColumns
        val rows = emulator.mRows
        val fontWidth = renderer.fontWidth
        val step = renderer.fontLineSpacing.toFloat()
        if (step <= 0f) return
        val skipFrom = inputStartRow(emulator)
        // 行底再往下一点，落在行间空隙（用户反馈要偏下）
        val gap = (3.5f * resources.displayMetrics.density).coerceAtMost(step * 0.3f)
        for (i in 0 until rows) {
            val row = topRow + i
            if (row >= skipFrom) continue
            // join=false：单行原文，避免 wrap 拼接打乱列下标
            val line = runCatching { screen.getSelectedText(0, row, cols - 1, row, false, false) }.getOrNull() ?: continue
            if (line.isBlank()) continue
            val spans = findTerminalUrlSpans(line)
            if (spans.isEmpty()) continue
            val underlineY = (i + 1) * step + gap
            for (span in spans) {
                val left = span.start * fontWidth
                val right = span.end * fontWidth
                if (right <= left) continue
                canvas.drawLine(left, underlineY, right, underlineY, linkUnderlinePaint)
            }
        }
    }

    private fun drawSelection(canvas: Canvas) {
        val start = selectionStart ?: return
        val end = selectionEnd ?: start
        val startRow = minOf(start.second, end.second)
        val endRow = maxOf(start.second, end.second)
        val left = 0f
        val right = width.toFloat()
        val step = renderer.fontLineSpacing.toFloat()
        // 选区与触摸坐标一致：从 0 起按行高步进（TerminalView.getPointY 同理）
        for (row in startRow..endRow) {
            val y = (row - topRow) * step
            if (y + step >= 0 && y <= height) {
                canvas.drawRect(left, y, right, y + step, selectionPaint)
            }
        }
    }

    private fun drawCopyHint(canvas: Canvas) {
        if (copyScreenStep == 0) return
        val barColor = if (copyScreenStep == 1) Color.rgb(244, 67, 54) else Color.rgb(76, 175, 80)
        val text = context.getString(if (copyScreenStep == 1) R.string.copy_start_hint else R.string.copy_end_hint)
        val top = height - 56f
        val paint = Paint().apply { color = barColor }
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), paint)
        canvas.drawText(text, width / 2f, top + 37f, hintPaint)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> onInput("\r")
            KeyEvent.KEYCODE_DEL -> onInput("\u007F")
            KeyEvent.KEYCODE_DPAD_UP -> onInput("\u001B[A")
            KeyEvent.KEYCODE_DPAD_DOWN -> onInput("\u001B[B")
            KeyEvent.KEYCODE_DPAD_LEFT -> onInput("\u001B[D")
            KeyEvent.KEYCODE_DPAD_RIGHT -> onInput("\u001B[C")
            else -> event.unicodeChar.takeIf { it != 0 }?.toChar()?.toString()?.let(onInput) ?: return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun performLongClick(): Boolean {
        super.performLongClick()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val items = intArrayOf(
            R.string.copy_from_screen,
            R.string.copy_whole_session,
            R.string.paste,
            R.string.save_transcript,
            R.string.share_transcript,
            R.string.keep_screen_on,
            R.string.clear,
        )
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.terminal))
            .setItems(items.map(context::getString).toTypedArray()) { _, which ->
                when (which) {
                    0 -> startCopyFromScreen()
                    1 -> showCopyWholeSessionDialog(clipboard)
                    2 -> {
                        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (text.isNotEmpty()) onInput(text)
                    }
                    3 -> saveTranscriptToFile()
                    4 -> shareTranscript()
                    5 -> keepScreenOn = !keepScreenOn
                    6 -> clearTerminalScreen()
                }
            }
            .show()
        return true
    }

    private fun clearTerminalScreen() {
        val current = emulator ?: return
        current.screen.clearTranscript()
        val clearSequence = "\u001B[H\u001B[2J\u001B[3J".toByteArray()
        current.append(clearSequence, clearSequence.size)
        topRow = 0
        invalidate()
    }

    private fun saveTranscriptToFile() {
        val text = emulator?.screen?.getTranscriptText().orEmpty()
        val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        val fileName = "${sessionHost}_${timestamp}.txt"
        onSaveTranscript(fileName, text)
    }

    private fun showCopyWholeSessionDialog(clipboard: ClipboardManager?) {
        val editText = EditText(context).apply {
            setText(emulator?.screen?.getTranscriptText().orEmpty())
            setSelectAllOnFocus(false)
            setTextIsSelectable(true)
            minLines = 6
            maxLines = 10
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.select_text))
            .setView(editText)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .setPositiveButton(context.getString(R.string.copy)) { _, _ ->
                val selected = editText.selectionStart.takeIf { it >= 0 }?.let { start ->
                    val end = editText.selectionEnd
                    if (end > start) editText.text.substring(start, end) else null
                }
                clipboard?.setPrimaryClip(ClipData.newPlainText("terminal", selected ?: editText.text.toString()))
            }
            .show()
    }

    private fun shareTranscript() {
        val text = emulator?.screen?.getTranscriptText() ?: ""
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                context.getString(R.string.share_transcript),
            )
        )
    }

    private fun startCopyFromScreen() {
        copyScreenStep = 1
        selectionStart = null
        selectionEnd = null
        invalidate()
    }

    private fun positionFromTouch(event: MotionEvent): Pair<Int, Int> {
        val column = max(0, (event.x / renderer.fontWidth).toInt())
        val row = topRow + max(0, (event.y / renderer.fontLineSpacing).toInt())
        return column to row
    }

    private fun finishCopyFromScreen() {
        val rawStart = selectionStart ?: return
        val rawEnd = selectionEnd ?: return
        val (start, end) = if (rawStart.second < rawEnd.second || (rawStart.second == rawEnd.second && rawStart.first <= rawEnd.first)) {
            rawStart to rawEnd
        } else {
            rawEnd to rawStart
        }
        val text = emulator?.screen?.getSelectedText(start.first, start.second, end.first, end.second).orEmpty()
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        copyScreenStep = 0
        selectionStart = null
        selectionEnd = null
        invalidate()
    }

    /** 短按点在链接上：弹出复制 / 打开；否则 false 走原 onTap（调键盘）。 */
    private fun handleLinkTap(event: MotionEvent): Boolean {
        val current = emulator ?: return false
        val screen = current.screen
        val (column, row) = positionFromTouch(event)
        // 输入行（含上键历史）不点链接
        if (row >= inputStartRow(current)) return false
        val word = runCatching { screen.getWordAtLocation(column, row) }.getOrNull().orEmpty()
        val url = extractTerminalUrl(word) ?: return false
        showLinkActionDialog(url)
        return true
    }

    private fun showLinkActionDialog(url: String) {
        val items = arrayOf(
            context.getString(R.string.copy),
            context.getString(R.string.open_link),
        )
        AlertDialog.Builder(context)
            .setTitle(url)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("url", url))
                        Toast.makeText(context, context.getString(R.string.link_copied), Toast.LENGTH_SHORT).show()
                    }
                    1 -> openUrl(url)
                }
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.open_link_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = System.currentTimeMillis()
                downX = event.x
                downY = event.y
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (copyScreenStep != 0) {
                    if (copyScreenStep == 1) selectionStart = positionFromTouch(event) else selectionEnd = positionFromTouch(event)
                    invalidate()
                    return true
                }
                val deltaRows = ((event.y - lastY) / renderer.fontLineSpacing).toInt()
                if (deltaRows != 0) {
                    val activeRows = emulator?.screen?.activeTranscriptRows ?: 0
                    topRow = (topRow - deltaRows).coerceIn(-activeRows, 0)
                    lastY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (copyScreenStep == 1) {
                    selectionStart = positionFromTouch(event)
                    selectionEnd = selectionStart
                    copyScreenStep = 2
                    invalidate()
                    return true
                }
                if (copyScreenStep == 2) {
                    selectionEnd = positionFromTouch(event)
                    finishCopyFromScreen()
                    return true
                }
                val longPress = System.currentTimeMillis() - downAt > 500
                val moved = hypot(event.x - downX, event.y - downY) > tapSlopPx
                if (longPress) {
                    performLongClick()
                } else if (!moved && handleLinkTap(event)) {
                    // 点到链接：已弹窗
                } else {
                    onTap()
                }
                return true
            }
        }
        return true
    }
}
