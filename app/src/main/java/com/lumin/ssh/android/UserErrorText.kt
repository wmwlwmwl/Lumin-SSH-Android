package com.lumin.ssh.android

import android.content.Context

fun Context.userErrorText(error: Throwable?): String {
    if (error == null) return getString(R.string.error_unknown)
    val message = error.message.orEmpty()
    val suffix = message.substringAfter(':', "").trim().takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
    return when {
        error is SyncInProgressException -> getString(R.string.sync_in_progress)
        error is RecoveryPasswordException -> getString(R.string.error_recovery_password)
        error is NoBackupException -> getString(R.string.error_no_cloud_backup)
        error is SnapshotFormatException || message.contains("备份") || message.contains("LUMIN2") || message == "加密数据格式无效" -> getString(R.string.error_invalid_backup)
        message.startsWith("WebDAV ") -> getString(R.string.error_webdav_request, suffix)
        message.startsWith("R2 ") -> getString(R.string.error_r2_request, suffix)
        message.startsWith("SFTP 主机密钥未接受") -> getString(R.string.error_sftp_host_key_rejected, suffix)
        message.startsWith("FTPS 证书未接受") -> getString(R.string.error_ftps_certificate_rejected, suffix)
        message.startsWith("FTP ") || message.startsWith("FTPS ") -> getString(R.string.error_ftp_request, suffix)
        message == "本地数据保存失败" || message == "本地同步快照保存失败" -> getString(R.string.error_local_save)
        message == "未配置同步后端" -> getString(R.string.error_sync_backend_missing)
        message.isNotBlank() -> message
        else -> error.javaClass.simpleName.ifBlank { getString(R.string.error_unknown) }
    }
}
