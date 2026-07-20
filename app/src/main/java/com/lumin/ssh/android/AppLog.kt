package com.lumin.ssh.android

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用日志：files/logs/lumin.log，设置里开关/分享/清理。
 * 超限后把当前文件挪成 lumin.prev.log，再写新文件。
 * 分享时合并 prev + 当前，便于排查刚轮转后的问题。
 */
object AppLog {
    private const val TAG = "LuminSSH"
    /** 单文件上限，超过则轮转（旧文件 → lumin.prev.log） */
    const val MAX_BYTES: Long = 1_500_000L // ~1.5MB

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lumin-app-log").apply { isDaemon = true }
    }
    @Volatile private var logFile: File? = null
    @Volatile private var prevFile: File? = null
    @Volatile private var exportFile: File? = null
    private val enabled = AtomicBoolean(true)
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context, loggingEnabled: Boolean = true) {
        val dir = File(context.applicationContext.filesDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        logFile = File(dir, "lumin.log")
        prevFile = File(dir, "lumin.prev.log")
        exportFile = File(dir, "lumin-export.log")
        enabled.set(loggingEnabled)
        if (loggingEnabled) {
            i("AppLog", "init enabled path=${logFile?.absolutePath} max=${formatBytes(MAX_BYTES)}")
        } else {
            Log.i(TAG, "AppLog init disabled")
        }
    }

    fun isEnabled(): Boolean = enabled.get()

    fun setEnabled(on: Boolean) {
        val was = enabled.getAndSet(on)
        if (on && !was) {
            write("I", "AppLog", "logging enabled")
        } else if (!on && was) {
            forceAppend("I", "AppLog", "logging disabled")
            Log.i(TAG, "AppLog logging disabled")
        }
    }

    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String, err: Throwable? = null) {
        val detail = if (err != null) "$msg | ${err.javaClass.simpleName}: ${err.message}" else msg
        write("E", tag, detail)
        if (err != null) Log.e(TAG, "$tag $msg", err)
    }

    fun logFileOrNull(): File? = logFile?.takeIf { it.exists() && it.length() > 0 }

    fun prevFileOrNull(): File? = prevFile?.takeIf { it.exists() && it.length() > 0 }

    /** 当前日志 + 上一份轮转合计大小（字节） */
    fun totalSizeBytes(): Long {
        val cur = logFile?.takeIf { it.exists() }?.length() ?: 0L
        val prev = prevFile?.takeIf { it.exists() }?.length() ?: 0L
        return cur + prev
    }

    fun currentSizeBytes(): Long = logFile?.takeIf { it.exists() }?.length() ?: 0L

    fun sizeLabel(): String = formatBytes(totalSizeBytes())

    fun maxSizeLabel(): String = formatBytes(MAX_BYTES)

    /**
     * 分享：有 prev 则合并为 export 文件（prev 在前、当前在后）；
     * 只有当前则直接分享 lumin.log。
     */
    fun shareIntent(context: Context): Intent? {
        val cur = logFileOrNull()
        val prev = prevFileOrNull()
        if (cur == null && prev == null) return null

        val toShare = when {
            prev != null && cur != null -> {
                val out = exportFile ?: return null
                runCatching {
                    out.bufferedWriter().use { w ->
                        w.appendLine("===== lumin.prev.log =====")
                        prev.forEachLine { w.appendLine(it) }
                        w.appendLine()
                        w.appendLine("===== lumin.log =====")
                        cur.forEachLine { w.appendLine(it) }
                    }
                    out
                }.getOrNull() ?: cur
            }
            cur != null -> cur
            else -> prev
        } ?: return null

        if (!toShare.exists() || toShare.length() == 0L) return null

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            toShare,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Lumin SSH log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun clear() {
        io.execute {
            runCatching {
                logFile?.writeText("")
                prevFile?.delete()
                exportFile?.delete()
            }
            if (enabled.get()) {
                forceAppend("I", "AppLog", "cleared")
            }
        }
    }

    private fun write(level: String, tag: String, msg: String) {
        when (level) {
            "E" -> Log.e(TAG, "$tag $msg")
            "W" -> Log.w(TAG, "$tag $msg")
            "I" -> Log.i(TAG, "$tag $msg")
            else -> Log.d(TAG, "$tag $msg")
        }
        if (!enabled.get()) return
        forceAppend(level, tag, msg)
    }

    private fun forceAppend(level: String, tag: String, msg: String) {
        val file = logFile ?: return
        val line = "${timeFmt.format(Date())} $level/$tag: $msg"
        io.execute {
            runCatching {
                if (file.exists() && file.length() > MAX_BYTES) {
                    val bak = prevFile
                    if (bak != null) {
                        bak.delete()
                        file.renameTo(bak)
                    }
                }
                file.appendText(line + "\n")
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }
}
