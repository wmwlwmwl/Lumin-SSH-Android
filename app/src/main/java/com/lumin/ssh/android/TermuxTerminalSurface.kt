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
    /** 选择文本对话框打开时：冻结自动滚到底，且对话框只用打开瞬间的快照 */
    private var selectTextDialogOpen = false
    /** 原生 AlertDialog 会钉住 View/Context；页面销毁前必须 dismiss */
    private var activeDialog: AlertDialog? = null
    private val pending = ArrayList<ByteArray>()
    /** 待应用的行列：键盘动画期间只记不重排，见 onSizeChanged */
    private var pendingResize: Pair<Int, Int>? = null
    private val applyResize = Runnable {
        val target = pendingResize
        pendingResize = null
        val current = emulator
        if (target != null && current != null) {
            val (columns, rows) = target
            if (current.mColumns != columns || current.mRows != rows) {
                current.resize(columns, rows, renderer.fontWidth.toInt(), renderer.fontLineSpacing)
                // 本地重排与远程 WINCH 同时发生，把花屏窗口压到一次网络往返
                onResize(columns, rows)
                invalidate()
            }
        }
    }
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

    private fun trackDialog(dialog: AlertDialog): AlertDialog {
        activeDialog?.takeIf { it !== dialog && it.isShowing }?.dismiss()
        activeDialog = dialog
        return dialog
    }

    /** Compose 页面 dispose / 会话切换时调用，避免原生对话框泄漏 Activity */
    fun dismissUiDialogs() {
        val dialog = activeDialog
        activeDialog = null
        selectTextDialogOpen = false
        runCatching { dialog?.dismiss() }
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
            // 改字号是单次操作（非动画），立即生效更跟手；
            // 但要丢掉待处理的防抖，否则旧行列会把这次覆盖回去
            removeCallbacks(applyResize)
            pendingResize = null
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
        // 选文本对话框打开时不要强制滚到最新，避免用户在对话框里滑不动
        if (!selectTextDialogOpen) {
            topRow = 0
        }
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
            onResize(columns, rows)
            return
        }
        if (current.mColumns == columns && current.mRows == rows) {
            // 已经是目标尺寸：连带撤掉在途的旧任务，否则它会把缓冲区改回错的行列
            removeCallbacks(applyResize)
            pendingResize = null
            return
        }
        // 键盘动画每帧都会走到这里（实测一轮 14 次）。逐帧 resize 会把备用屏幕（nano 等）
        // 的内容反复重排揉花，故动画期间不动缓冲区，等尺寸稳定后只重排一次并通知远端。
        pendingResize = columns to rows
        removeCallbacks(applyResize)
        // 帧间隔实测 15–66ms，100ms 足够合成一轮动画
        postDelayed(applyResize, 100)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(applyResize)
        pendingResize = null
        super.onDetachedFromWindow()
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

    private fun lineTextAt(screen: com.termux.terminal.TerminalBuffer, cols: Int, row: Int): String =
        runCatching { screen.getSelectedText(0, row, cols - 1, row, false, false) }.getOrDefault("")

    private fun logicalParts(
        screen: com.termux.terminal.TerminalBuffer,
        cols: Int,
        hintRow: Int,
        minRow: Int,
        maxRow: Int,
    ): LogicalLineParts = expandLogicalLine(
        startHintRow = hintRow,
        maxRow = maxRow,
        minRow = minRow,
        isWrapAt = { r -> runCatching { screen.getLineWrap(r) }.getOrDefault(false) },
        lineText = { r -> lineTextAt(screen, cols, r) },
    )

    /**
     * 可见行扫描 URL，只画下划线（不铺底）。
     * wrap 续行拼成逻辑行再匹配，换行 URL 完整高亮/可点。
     */
    private fun drawLinkHighlights(canvas: Canvas, emulator: TerminalEmulator) {
        val screen = emulator.screen
        val cols = emulator.mColumns
        val rows = emulator.mRows
        val fontWidth = renderer.fontWidth
        val step = renderer.fontLineSpacing.toFloat()
        if (step <= 0f) return
        val skipFrom = inputStartRow(emulator)
        val gap = (3.5f * resources.displayMetrics.density).coerceAtMost(step * 0.3f)
        val minRow = topRow
        val maxRow = topRow + rows - 1
        val drawn = HashSet<String>()
        var i = 0
        while (i < rows) {
            val row = topRow + i
            if (row >= skipFrom) break
            // 续行交给逻辑行起点处理，避免重复
            if (row > minRow && runCatching { screen.getLineWrap(row - 1) }.getOrDefault(false)) {
                i++
                continue
            }
            val parts = logicalParts(screen, cols, row, minRow, maxOf(maxRow, skipFrom - 1))
            val spans = findTerminalUrlSpans(parts.joined)
            for (span in spans) {
                val id = "${parts.startRow}:${span.start}-${span.end}:${span.url}"
                if (!drawn.add(id)) continue
                // 按逻辑行分段画每条物理行的下划线
                var cursor = 0
                parts.texts.forEachIndexed { segIdx, text ->
                    val segStart = cursor
                    val segEnd = cursor + text.length
                    cursor = segEnd
                    if (span.end <= segStart || span.start >= segEnd) return@forEachIndexed
                    val physRow = parts.startRow + segIdx
                    val vis = physRow - topRow
                    if (vis < 0 || vis >= rows || physRow >= skipFrom) return@forEachIndexed
                    // span 是字符串下标；中文等宽字符占 2 列，必须映射到终端列再乘 fontWidth
                    val localStart = (span.start - segStart).coerceAtLeast(0)
                    val localEnd = (span.end - segStart).coerceAtMost(text.length)
                    if (localEnd <= localStart) return@forEachIndexed
                    val col0 = stringIndexToColumn(text, localStart)
                    val col1 = stringIndexToColumn(text, localEnd)
                    if (col1 <= col0) return@forEachIndexed
                    val left = col0 * fontWidth
                    val right = col1 * fontWidth
                    val underlineY = (vis + 1) * step + gap
                    canvas.drawLine(left, underlineY, right, underlineY, linkUnderlinePaint)
                }
            }
            i += parts.texts.size.coerceAtLeast(1)
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
        trackDialog(
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
        ).setOnDismissListener {
            if (activeDialog === it) activeDialog = null
        }
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

    /**
     * 复制整个会话：高度贴内容；宽尽量贴屏；字号略小于终端，避免框比终端窄时过早折行。
     */
    private fun showCopyWholeSessionDialog(clipboard: ClipboardManager?) {
        val snapshot = emulator?.screen?.getTranscriptText().orEmpty()
        selectTextDialogOpen = true

        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        val screenH = resources.displayMetrics.heightPixels
        val screenW = resources.displayMetrics.widthPixels
        // 左右只留 8dp，尽量接近终端可视宽
        val dialogW = (screenW - (8 * density).toInt()).coerceAtLeast(1)
        val padH = (6 * density).toInt()
        val padTop = (6 * density).toInt()
        val chromeH = (120 * density).toInt()
        val contentMax = (screenH - chromeH - (12 * density).toInt()).coerceAtLeast((screenH * 0.6f).toInt())
        // 框比终端窄一点时，同字号会更早折行 → 略缩小字号，让每行更接近终端观感
        val dialogTextPx = (textSizePx * 0.88f).coerceAtLeast(12f)
        // 底部多留约 1 行高，避免滚到末尾时最后一行贴边/被裁切
        val padBottom = (dialogTextPx + 8f * density).toInt()

        val editText = EditText(context).apply {
            setText(snapshot)
            setSelectAllOnFocus(false)
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dialogTextPx)
            setTextColor(defaultForegroundColor)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(padH, padTop, padH, padBottom)
            isVerticalScrollBarEnabled = false
            minLines = 1
            maxLines = Integer.MAX_VALUE
            minimumHeight = 0
            setMinHeight(0)
            includeFontPadding = false
            setHorizontallyScrolling(false)
            val end = text?.length ?: 0
            if (end > 0) setSelection(end)
        }

        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = true
            setPadding(0, 0, 0, 0)
            addView(
                editText,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            addView(
                scroll,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.select_text))
            .setView(container)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .setPositiveButton(context.getString(R.string.copy)) { _, _ ->
                val selected = editText.selectionStart.takeIf { it >= 0 }?.let { start ->
                    val end = editText.selectionEnd
                    if (end > start) editText.text.substring(start, end) else null
                }
                clipboard?.setPrimaryClip(
                    ClipData.newPlainText("terminal", selected ?: editText.text.toString()),
                )
            }
            .create()

        fun scrollToEnd() {
            val child = scroll.getChildAt(0) ?: return
            val y = (child.height - scroll.height + scroll.paddingBottom).coerceAtLeast(0)
            scroll.scrollTo(0, y)
        }

        fun fitHeightOnce(thenScrollEnd: Boolean) {
            val width = scroll.width.takeIf { it > 0 } ?: dialogW
            editText.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            val needed = editText.measuredHeight.coerceAtMost(contentMax).coerceAtLeast(1)
            val lp = scroll.layoutParams
            if (lp.height != needed) {
                lp.height = needed
                scroll.layoutParams = lp
            }
            if (thenScrollEnd) {
                // 高度应用后再滚到底，避免 fullScroll 时高度还是旧的
                scroll.post { scrollToEnd() }
            }
        }

        val fitAgain = Runnable { fitHeightOnce(thenScrollEnd = true) }
        val scrollEnd = Runnable { scrollToEnd() }
        dialog.setOnDismissListener {
            selectTextDialogOpen = false
            if (activeDialog === dialog) activeDialog = null
            scroll.removeCallbacks(fitAgain)
            scroll.removeCallbacks(scrollEnd)
        }

        trackDialog(dialog)
        dialog.show()
        dialog.window?.setLayout(dialogW, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        // 去掉 Material 默认 customView 左右大间距，否则有效内容区又窄一截
        (container.parent as? android.view.View)?.setPadding(0, 0, 0, 0)
        dialog.findViewById<android.view.View>(android.R.id.custom)?.setPadding(0, 0, 0, 0)
        (dialog.findViewById<android.view.View>(android.R.id.custom)?.parent as? android.view.View)
            ?.setPadding(0, 0, 0, 0)

        scroll.post { fitHeightOnce(thenScrollEnd = true) }
        scroll.postDelayed(fitAgain, 48)
        scroll.postDelayed(scrollEnd, 100)
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

    /**
     * 短按点在链接附近：弹出复制 / 打开；否则 false 走原 onTap（调键盘）。
     * 按像素矩形命中（非字符下标），上下左右留手指容差；DOWN/UP 任一命中即可。
     */
    private fun handleLinkTap(event: MotionEvent): Boolean {
        val current = emulator ?: run {
            AppLog.d("Link", "tap miss: no emulator")
            return false
        }
        // DOWN 与 UP 都试：抬手微移时仍可能落在链接上
        val hit = findLinkNear(current, event.x, event.y)
            ?: findLinkNear(current, downX, downY)
        if (hit == null) {
            AppLog.d("Link", "tap miss: no near link x=${event.x} y=${event.y}")
            return false
        }
        AppLog.i("Link", "tap hit url=${hit.url} dist=${hit.dist}")
        showLinkActionDialog(hit.url)
        return true
    }

    private data class LinkHit(val url: String, val dist: Float)

    /**
     * 可见区链接段 → 像素包围盒，取离触点最近且在容差内的 URL。
     * 垂直容差约 0.55 行高，水平约 10dp，方便点细下划线。
     */
    private fun findLinkNear(emulator: TerminalEmulator, x: Float, y: Float): LinkHit? {
        val screen = emulator.screen
        val cols = emulator.mColumns
        val rows = emulator.mRows
        val fontWidth = renderer.fontWidth
        val step = renderer.fontLineSpacing.toFloat()
        if (step <= 0f || fontWidth <= 0f) return null
        val density = resources.displayMetrics.density
        val padX = 10f * density
        val padY = (step * 0.55f).coerceAtLeast(10f * density)
        val skipFrom = inputStartRow(emulator)
        val minRow = topRow
        val maxRow = topRow + rows - 1
        var best: LinkHit? = null
        val seen = HashSet<String>()
        var i = 0
        while (i < rows) {
            val row = topRow + i
            if (row >= skipFrom) break
            if (row > minRow && runCatching { screen.getLineWrap(row - 1) }.getOrDefault(false)) {
                i++
                continue
            }
            val parts = logicalParts(screen, cols, row, minRow, maxOf(maxRow, skipFrom - 1))
            val spans = findTerminalUrlSpans(parts.joined)
            for (span in spans) {
                val id = "${parts.startRow}:${span.start}-${span.end}:${span.url}"
                if (!seen.add(id)) continue
                var cursor = 0
                parts.texts.forEachIndexed { segIdx, text ->
                    val segStart = cursor
                    val segEnd = cursor + text.length
                    cursor = segEnd
                    if (span.end <= segStart || span.start >= segEnd) return@forEachIndexed
                    val physRow = parts.startRow + segIdx
                    val vis = physRow - topRow
                    if (vis < 0 || vis >= rows || physRow >= skipFrom) return@forEachIndexed
                    val localStart = (span.start - segStart).coerceAtLeast(0)
                    val localEnd = (span.end - segStart).coerceAtMost(text.length)
                    if (localEnd <= localStart) return@forEachIndexed
                    val col0 = stringIndexToColumn(text, localStart)
                    val col1 = stringIndexToColumn(text, localEnd)
                    if (col1 <= col0) return@forEachIndexed
                    val left = col0 * fontWidth - padX
                    val right = col1 * fontWidth + padX
                    val top = vis * step - padY
                    val bottom = (vis + 1) * step + padY
                    val dx = when {
                        x < left -> left - x
                        x > right -> x - right
                        else -> 0f
                    }
                    val dy = when {
                        y < top -> top - y
                        y > bottom -> y - bottom
                        else -> 0f
                    }
                    // 在扩展盒内：取曼哈顿距离最近（盒内为 0）
                    if (dx == 0f && dy == 0f) {
                        val cand = LinkHit(span.url, 0f)
                        if (best == null || cand.dist < best!!.dist) best = cand
                    }
                }
            }
            i += parts.texts.size.coerceAtLeast(1)
        }
        return best
    }

    private fun showLinkActionDialog(url: String) {
        val items = arrayOf(
            context.getString(R.string.copy),
            context.getString(R.string.open_link),
        )
        trackDialog(
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
        ).setOnDismissListener {
            if (activeDialog === it) activeDialog = null
        }
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
                // 链接命中再放宽移动阈值（点细下划线时手指常微抖）
                val linkSlop = tapSlopPx * 2.2f
                val movedFar = hypot(event.x - downX, event.y - downY) > linkSlop
                val movedTap = hypot(event.x - downX, event.y - downY) > tapSlopPx
                if (longPress) {
                    performLongClick()
                } else if (!movedFar && handleLinkTap(event)) {
                    // 点到链接：已弹窗
                } else if (!movedTap) {
                    onTap()
                }
                return true
            }
        }
        return true
    }
}
