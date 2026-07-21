package com.lumin.ssh.android

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val LUMIN2_PREFIX = "LUMIN2:"
private const val LUMIN2_ITERATIONS = 210000
private const val LUMIN2_HEADER_SIZE = 33

open class SnapshotFormatException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
class RecoveryPasswordException(message: String = "恢复密码错误", cause: Throwable? = null) : IllegalStateException(message, cause)
class NoBackupException(message: String = "云端没有 Lumin 备份") : IllegalStateException(message)
class RecoveryPasswordResetRequiredException(message: String = "旧密码和新密码都无法解密最新备份，需要确认重置恢复密码") : IllegalStateException(message)

// 与 PC 一致：20060102_150405.000_-0700 → 本地时区如 ...013_+0800
// WebDAV 路径里的 + 由 encodeWebDavSegment 编成 %2B，列表解码用 decodeWebDavFileName 保留 +。
fun backupTimestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss.SSS_Z", Locale.US).apply {
    timeZone = TimeZone.getDefault()
}.format(Date())

fun encryptLumin2(text: String, password: String): String = encryptLumin2WithSaltNonce(
    text,
    password,
    ByteArray(16).also { SecureRandom().nextBytes(it) },
    ByteArray(12).also { SecureRandom().nextBytes(it) },
)

internal fun encryptLumin2WithSaltNonce(text: String, password: String, salt: ByteArray, nonce: ByteArray): String {
    require(salt.size == 16 && nonce.size == 12) { "LUMIN2 salt/nonce 长度无效" }
    val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(password.toCharArray(), salt, LUMIN2_ITERATIONS, 256)).encoded
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    val header = ByteBuffer.allocate(LUMIN2_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        .put(2).putInt(LUMIN2_ITERATIONS).put(salt).put(nonce).array()
    return LUMIN2_PREFIX + Base64.getEncoder().encodeToString(header + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
}

fun decryptLumin2(text: String, password: String): String {
    require(text.startsWith(LUMIN2_PREFIX)) { "缺少 LUMIN2 前缀" }
    val payload = try {
        Base64.getDecoder().decode(text.removePrefix(LUMIN2_PREFIX))
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("LUMIN2 Base64 无效", e)
    }
    require(payload.size >= LUMIN2_HEADER_SIZE + 16) { "LUMIN2 数据长度不足" }
    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
    require(buffer.get().toInt() == 2) { "不支持的 LUMIN2 版本" }
    val iterations = buffer.int
    require(iterations in 100000..2000000) { "LUMIN2 迭代次数无效" }
    val salt = ByteArray(16).also { buffer.get(it) }
    val nonce = ByteArray(12).also { buffer.get(it) }
    val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    return String(cipher.doFinal(payload.copyOfRange(LUMIN2_HEADER_SIZE, payload.size)), Charsets.UTF_8)
}

fun parseSnapshotPayload(text: String, password: String?): SyncSnapshot {
    val trimmed = text.trim()
    if (trimmed.startsWith("{")) {
        return runCatching { syncSnapshotFromJson(JSONObject(trimmed)) }
            .getOrElse { throw SnapshotFormatException("备份 JSON 格式无效", it) }
    }
    val decrypted = when {
        trimmed.startsWith(LUMIN2_PREFIX) -> {
            if (password == null) throw RecoveryPasswordException("LUMIN2 备份需要恢复密码")
            try {
                decryptLumin2(trimmed, password)
            } catch (e: AEADBadTagException) {
                throw RecoveryPasswordException("恢复密码错误", e)
            } catch (e: Exception) {
                throw SnapshotFormatException("LUMIN2 备份结构无效", e)
            }
        }
        else -> throw SnapshotFormatException("不支持的备份格式：仅支持明文 JSON 与 LUMIN2 密文")
    }
    return runCatching { syncSnapshotFromJson(JSONObject(decrypted)) }
        .getOrElse { throw SnapshotFormatException("备份解密成功，但内容不是有效 SyncSnapshot", it) }
}

fun backupFileName(encrypted: Boolean): String = "connections_backup_${backupTimestamp()}.${if (encrypted) "lumin2" else "json"}"

fun isBackupName(name: String): Boolean {
    return name.startsWith("connections_backup_") && (name.endsWith(".lumin2") || name.endsWith(".json"))
}
