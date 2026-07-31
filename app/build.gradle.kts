plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties
import java.io.File
import java.io.FileInputStream

// versionName 读根目录 VERSION；versionCode 发版时手动 +1
val luminVersionName: String = rootProject.file("VERSION").takeIf { it.exists() }?.readText()?.trim().orEmpty()
    .ifBlank { "0.1.0" }

// 本地：android/keystore/key.properties（勿提交）
// CI：环境变量 LUMIN_STORE_FILE / LUMIN_STORE_PASSWORD / LUMIN_KEY_ALIAS / LUMIN_KEY_PASSWORD
val keystorePropertiesFile = rootProject.file("keystore/key.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

fun propOrEnv(propKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propKey)?.trim()?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.trim()?.takeIf { it.isNotBlank() }

val storeFilePath = propOrEnv("storeFile", "LUMIN_STORE_FILE")
val storePasswordValue = propOrEnv("storePassword", "LUMIN_STORE_PASSWORD")
val keyAliasValue = propOrEnv("keyAlias", "LUMIN_KEY_ALIAS")
val keyPasswordValue = propOrEnv("keyPassword", "LUMIN_KEY_PASSWORD")

// 相对路径一律相对 android/ 工程根，不要相对 app/
val releaseStoreFile: File? = storeFilePath?.let { path ->
    val candidate = File(path)
    if (candidate.isAbsolute) {
        candidate
    } else {
        rootProject.layout.projectDirectory.file(path).asFile
    }
}

val placeholderPasswords = setOf(
    "改成你的仓库密码",
    "改成你的密钥密码",
    "你的仓库密码",
    "你的密钥密码",
)

val hasReleaseSigning = releaseStoreFile != null &&
    releaseStoreFile.exists() &&
    !storePasswordValue.isNullOrBlank() &&
    !keyAliasValue.isNullOrBlank() &&
    !keyPasswordValue.isNullOrBlank() &&
    storePasswordValue !in placeholderPasswords &&
    keyPasswordValue !in placeholderPasswords

if (!hasReleaseSigning) {
    logger.warn(
        "Release signing NOT applied. " +
            "storeFile='$storeFilePath' resolved='${releaseStoreFile?.absolutePath}' " +
            "exists=${releaseStoreFile?.exists()} root=${rootProject.projectDir}",
    )
} else {
    logger.lifecycle("Release signing enabled: ${releaseStoreFile!!.absolutePath}")
}

android {
    namespace = "com.lumin.ssh.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lumin.ssh.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = luminVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // 注意：不要写成 storePassword = storePassword（会把自己赋给自己）
                storeFile = releaseStoreFile
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                // PKCS12 通常 store/key 同一密码；以 storePassword 为准更稳
                keyPassword = storePasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.ui:ui:1.7.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("sh.calvin.reorderable:reorderable-android:2.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.Termux.Termux-app:terminal-emulator:v0.119.0-beta.3")
    implementation("com.github.Termux.Termux-app:terminal-view:v0.119.0-beta.3")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.4")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}
