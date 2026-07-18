package com.lumin.ssh.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val latestVersion: String,
    val hasUpdate: Boolean,
    val releaseUrl: String,
    val apkUrl: String?,
    val apkName: String?,
    val releaseNotes: String?,
)

object UpdateChecker {
    /** GitHub Releases latest API for the Android repo. */
    const val DEFAULT_REPO = "wmwlwmwl/Lumin-SSH-Android"

    fun releasesLatestApi(repo: String = DEFAULT_REPO): String =
        "https://api.github.com/repos/$repo/releases/latest"

    /**
     * Semantic-ish compare: true if [latest] is newer than [current].
     * Strips leading `v` / `android-v` prefixes.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val l = normalizeVersion(latest)
        val c = normalizeVersion(current)
        if (l == c) return false
        val lp = l.split('.').map { it.toIntOrNull() ?: 0 }
        val cp = c.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(lp.size, cp.size)
        for (i in 0 until n) {
            val a = lp.getOrElse(i) { 0 }
            val b = cp.getOrElse(i) { 0 }
            if (a > b) return true
            if (a < b) return false
        }
        return false
    }

    fun normalizeVersion(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("android-v", ignoreCase = true)) {
            s = s.removePrefix("android-v").removePrefix("android-V")
        }
        s = s.trimStart('v', 'V')
        // drop pre-release suffix for comparison: 0.1.0-beta -> 0.1.0
        val cut = s.indexOfAny(charArrayOf('-', '+'))
        if (cut >= 0) s = s.substring(0, cut)
        return s
    }

    suspend fun check(
        currentVersionName: String,
        repo: String = DEFAULT_REPO,
    ): Result<AppUpdateInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(releasesLatestApi(repo)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 12_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Lumin-SSH-Android")
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code == 404) {
                    return@runCatching AppUpdateInfo(
                        latestVersion = currentVersionName,
                        hasUpdate = false,
                        releaseUrl = "https://github.com/$repo/releases",
                        apkUrl = null,
                        apkName = null,
                        releaseNotes = null,
                    )
                }
                if (code !in 200..299) {
                    error("HTTP $code: ${body.take(200)}")
                }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").ifBlank { error("missing tag_name") }
                val htmlUrl = json.optString("html_url").ifBlank { "https://github.com/$repo/releases" }
                val notes = json.optString("body").takeIf { it.isNotBlank() }
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var apkName: String? = null
                if (assets != null) {
                    // Prefer Lumin-V*-android.apk naming from our workflow
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name")
                        if (name.endsWith(".apk", ignoreCase = true) && name.contains("android", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url").ifBlank { null }
                            apkName = name
                            break
                        }
                    }
                    if (apkUrl == null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            val name = a.optString("name")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = a.optString("browser_download_url").ifBlank { null }
                                apkName = name
                                break
                            }
                        }
                    }
                }
                val latest = normalizeVersion(tag)
                AppUpdateInfo(
                    latestVersion = latest,
                    hasUpdate = isNewer(latest, currentVersionName),
                    releaseUrl = htmlUrl,
                    apkUrl = apkUrl,
                    apkName = apkName,
                    releaseNotes = notes,
                )
            } finally {
                conn.disconnect()
            }
        }
    }
}
