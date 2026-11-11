package com.universalmusic.player.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

lateinit var androidContext: Context
    private set

fun androidContextOrNull(): Context? =
    if (::androidContext.isInitialized) androidContext else null

fun initAndroidPlatform(context: Context) {
    androidContext = context.applicationContext
    installCrashLogging()
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun isNetworkAvailable(): Boolean {
    val context = runCatching { androidContext }.getOrNull() ?: return true
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager
        ?: return true
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

actual fun isNetworkMetered(): Boolean {
    val context = runCatching { androidContext }.getOrNull() ?: return false
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager
        ?: return false
    val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false
    return !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

actual fun monotonicElapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()

actual fun sha256Bytes(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(SecureRandom()::nextBytes)

actual fun encodeUrl(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

actual fun openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    androidContext.startActivity(intent)
}

actual fun platformLabel(): String = "Android"

actual fun appVersionLabel(): String = runCatching {
    val info = androidContext.packageManager.getPackageInfo(androidContext.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    "${info.versionName} ($code)"
}.getOrDefault("dev")
