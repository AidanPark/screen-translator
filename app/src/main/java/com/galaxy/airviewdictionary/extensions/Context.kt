package com.galaxy.airviewdictionary.extensions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.edit
import androidx.core.net.toUri
import com.galaxy.airviewdictionary.ACTION_SERVICE_CONTROL
import com.galaxy.airviewdictionary.EXTRA_SERVICE_STOP
import com.galaxy.airviewdictionary.core.OverlayService
import timber.log.Timber
import java.util.UUID


/**
 * UUID
 */
fun Context.getOrCreateAppInstanceId(): String {
    val prefs = getSharedPreferences("app_instance_prefs", Context.MODE_PRIVATE)
    val key = "app_instance_id"
    var id = prefs.getString(key, null)
    if (id == null) {
        id = UUID.randomUUID().toString()
        prefs.edit { putString(key, id) }
    }
    return id
}

/**
 * 네트웍 사용 가능 여부
 */
fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

/**
 * 햅틱
 */
fun Context.vibrate(durationMillis: Long = 10) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    vibrator.vibrate(VibrationEffect.createOneShot(durationMillis, VibrationEffect.DEFAULT_AMPLITUDE))
}

/**
 * 처리할 앱이 없어도(브라우저 미설치·비활성 기기 등) 크래시하지 않는 startActivity.
 * 외부 URL 등 기기 구성에 따라 리졸브가 보장되지 않는 인텐트에 사용한다.
 */
fun Context.startActivitySafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Timber.w("startActivitySafely: no activity to handle $intent")
    }
}

/**
 * 스토어 이동
 */
fun Context.gotoStore(
    newTask: Boolean = true,
    finishService: Boolean = false
) {
    val playStoreIntent = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()).apply {
        setPackage("com.android.vending") // Google Play Store 패키지 지정
    }

    val webIntent = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri())

    try {
        if (newTask) playStoreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(playStoreIntent)
    } catch (e: ActivityNotFoundException) {
        if (newTask) webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivitySafely(webIntent)
    }

    if (finishService) finishService()
}

/**
 * 구글 앱 열기
 */
fun Context.openGoogleApp() {
    try {
        // Google 앱을 열기 위한 Intent 생성
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = "https://www.google.com".toUri() // Google 앱의 메인 페이지로 이동
            setPackage("com.google.android.googlequicksearchbox") // Google 앱의 패키지 명시
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        // Intent 실행
        startActivity(intent)
    } catch (e: Exception) {
        // Google 앱이 설치되지 않은 경우 Google Play 스토어로 이동
        val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
            data = "market://details?id=com.google.android.googlequicksearchbox".toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(playStoreIntent)
    }
}

fun Context.playStoreUpdate() {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = "market://details?id=com.android.vending".toUri()
            setPackage("com.android.vending")
        }
        startActivity(intent)
    } catch (e: Exception) {
        Timber.tag("Context").e("Failed to redirect to Play Store update: ${e.message}")
    }
}

/**
 * 서비스 종료
 */
fun Context.finishService() = Intent().also { intent ->
    intent.setClass(this, OverlayService::class.java)
    intent.putExtra(ACTION_SERVICE_CONTROL, EXTRA_SERVICE_STOP)
    startService(intent)
}