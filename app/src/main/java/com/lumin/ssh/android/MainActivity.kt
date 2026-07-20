package com.lumin.ssh.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private const val SKIP_STARTUP_SYNC = "skip_startup_sync"

class MainActivity : ComponentActivity() {
    private val requestedSessionIdState = mutableStateOf<String?>(null)
    private var onVolumeKeyAdjustFontSize: ((Boolean) -> Boolean)? = null

    fun setVolumeKeyFontSizeCallback(callback: ((Boolean) -> Boolean)?) {
        onVolumeKeyAdjustFontSize = callback
    }

    override fun attachBaseContext(newBase: Context) {
        val language = normalizeAppLanguage(newBase.getSharedPreferences("lumin_lite", Context.MODE_PRIVATE).getString("app_language", APP_LANGUAGE_ZH_CN))
        super.attachBaseContext(newBase.withAppLanguage(language))
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val cb = onVolumeKeyAdjustFontSize
            if (cb != null) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> if (cb.invoke(true)) return true
                    KeyEvent.KEYCODE_VOLUME_DOWN -> if (cb.invoke(false)) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = LocalStore(this)
        AppLog.init(this, loggingEnabled = store.loadAppLogEnabled())
        AppLog.i("Main", "onCreate sdk=${Build.VERSION.SDK_INT} log=${store.loadAppLogEnabled()}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        requestedSessionIdState.value = intent?.getStringExtra(SshBackgroundService.EXTRA_SESSION_ID)
        val runStartupSync = !intent.getBooleanExtra(SKIP_STARTUP_SYNC, false)
        intent.removeExtra(SKIP_STARTUP_SYNC)
        setContent {
            var appLanguage by remember { mutableStateOf(store.loadAppLanguage()) }
            var appTheme by remember { mutableStateOf(store.loadAppTheme()) }
            LuminTheme(themeMode = appTheme) {
                val view = LocalView.current
                val useLightSystemBars = !LuminColors.isDark
                SideEffect {
                    val controller = WindowCompat.getInsetsController(window, view)
                    // Light status/nav bars = dark icons (for light UI). Dark UI needs light icons.
                    controller.isAppearanceLightStatusBars = useLightSystemBars
                    controller.isAppearanceLightNavigationBars = useLightSystemBars
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        LuminLiteApp(
                            store = store,
                            requestedSessionId = requestedSessionIdState.value,
                            appLanguage = appLanguage,
                            appTheme = appTheme,
                            runStartupSync = runStartupSync,
                            onAppLanguageChange = { language ->
                                val next = normalizeAppLanguage(language)
                                if (next != appLanguage) {
                                    store.saveAppLanguage(next)
                                    appLanguage = next
                                    intent.putExtra(SKIP_STARTUP_SYNC, true)
                                    recreate()
                                }
                            },
                            onAppThemeChange = { theme ->
                                val next = normalizeAppTheme(theme)
                                if (next != appTheme) {
                                    store.saveAppTheme(next)
                                    appTheme = next
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedSessionIdState.value = intent.getStringExtra(SshBackgroundService.EXTRA_SESSION_ID)
    }
}
