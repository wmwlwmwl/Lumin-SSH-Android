package com.lumin.ssh.android

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.termux.terminal.TerminalOutput
import com.termux.view.TerminalRenderer
import kotlin.math.max

class TermuxTerminalSurface(context: Context) : View(context) {
    private var renderer = TerminalRenderer(20, Typeface.MONOSPACE)
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
    private var copyScreenStep = 0
    private var selectionStart: Pair<Int, Int>? = null
    private var selectionEnd: Pair<Int, Int>? = null
    private val pending = ArrayList<ByteArray>()
    // Defaults match LuminDarkPalette terminal tokens until setSurfaceColors() runs.
    private var surfaceBackgroundColor: Int = Color.rgb(10, 14, 20)
    private var defaultForegroundColor: Int = Color.rgb(234, 240, 247)
    private var defaultCursorColor: Int = Color.rgb(77, 158, 255)
    private val selectionPaint = Paint().apply { color = Color.argb(110, 244, 67, 54) }
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

    fun setFontSize(fontSize: Int) {
        renderer = TerminalRenderer(fontSize.coerceIn(1, 30), Typeface.MONOSPACE)
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
        drawSelection(canvas)
        drawCopyHint(canvas)
    }

    private fun drawSelection(canvas: Canvas) {
        val start = selectionStart ?: return
        val end = selectionEnd ?: start
        val startRow = minOf(start.second, end.second)
        val endRow = maxOf(start.second, end.second)
        val left = 0f
        val right = width.toFloat()
        for (row in startRow..endRow) {
            val y = ((row - topRow) * renderer.fontLineSpacing).toFloat()
            if (y + renderer.fontLineSpacing >= 0 && y <= height) {
                canvas.drawRect(left, y, right, y + renderer.fontLineSpacing, selectionPaint)
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

    private fun copyTranscriptToClipboard(label: String, clipboard: ClipboardManager?) {
        val text = emulator?.screen?.getTranscriptText() ?: ""
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = System.currentTimeMillis()
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
                if (System.currentTimeMillis() - downAt > 500) {
                    performLongClick()
                } else {
                    onTap()
                }
                return true
            }
        }
        return true
    }
}
