package com.lumin.ssh.android

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

const val APP_LANGUAGE_ZH_CN = "zh-CN"
const val APP_LANGUAGE_EN = "en"

fun normalizeAppLanguage(language: String?): String = when (language) {
    APP_LANGUAGE_EN -> APP_LANGUAGE_EN
    else -> APP_LANGUAGE_ZH_CN
}

fun appLocale(language: String): Locale = when (normalizeAppLanguage(language)) {
    APP_LANGUAGE_EN -> Locale.ENGLISH
    else -> Locale.SIMPLIFIED_CHINESE
}

fun Context.withAppLanguage(language: String): Context {
    val locale = appLocale(language)
    Locale.setDefault(locale)
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    return createConfigurationContext(config)
}
