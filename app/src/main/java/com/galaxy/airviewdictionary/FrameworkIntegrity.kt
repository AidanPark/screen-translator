package com.galaxy.airviewdictionary

import android.os.Build
import android.view.WindowInsets

/**
 * 기기 신분(SDK_INT)과 실제 프레임워크의 일치 여부를 판정한다.
 *
 * API 34+ 를 자칭하면 반드시 있어야 하는 WindowInsets.Type.systemOverlays() 가 없으면
 * 빌드 속성을 위장한 가상 안드로이드(에뮬레이터/클라우드폰/개조 ROM)로 본다.
 *
 * 이런 기기에서는 androidx.core 의 WindowInsetsCompat(TypeImpl34) 이 systemOverlays() 를
 * 호출하다 NoSuchMethodError 로 죽는다. Compose 의 WindowInsets(WindowInsetsHolder) 는
 * 인셋을 한 번이라도 읽으면 이 경로를 타므로, 해당 기기에서는 Compose 인셋 API 를
 * 쓰지 말고 플랫폼 인셋을 직접 읽어야 한다. (ui/common/SafeWindowInsets.kt)
 */
object FrameworkIntegrity {

    /** SDK_INT 는 34+ 라면서 API 34 필수 메서드가 없는 위장 프레임워크인가. */
    val isSpoofedApi34: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@lazy false
        try {
            WindowInsets.Type::class.java.getMethod("systemOverlays")
            false
        } catch (t: Throwable) {
            true
        }
    }

    /** Crashlytics 커스텀 키 / Analytics 사용자 속성에 기록할 라벨. */
    val label: String get() = if (isSpoofedApi34) "spoofed_api34" else "ok"
}
