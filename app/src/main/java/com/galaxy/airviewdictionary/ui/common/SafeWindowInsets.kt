package com.galaxy.airviewdictionary.ui.common

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.galaxy.airviewdictionary.FrameworkIntegrity
import android.view.WindowInsets as PlatformWindowInsets

/**
 * Compose 의 인셋 API 는 WindowInsetsHolder 생성 시 WindowInsetsCompat.isVisible 을 거쳐
 * API 34 의 WindowInsets.Type.systemOverlays() 를 호출한다. 프레임워크를 위장한 기기
 * (SDK_INT 34+ 인데 실제 메서드가 없음)에서는 여기서 NoSuchMethodError 로 앱이 죽는다.
 *
 * 아래 헬퍼들은 정상 기기에서는 Compose 인셋을 그대로 쓰고, 위장 기기에서만
 * 플랫폼 인셋을 직접 읽는(또는 인셋을 포기하는) 우회 경로를 탄다.
 *
 * @see FrameworkIntegrity
 */

/** systemBarsPadding() 의 안전판. 위장 기기에서는 플랫폼 인셋으로 같은 패딩을 만든다. */
@Composable
fun Modifier.safeSystemBarsPadding(): Modifier =
    if (FrameworkIntegrity.isSpoofedApi34) padding(platformSystemBarsPadding())
    else systemBarsPadding()

/** Scaffold 의 contentWindowInsets 안전판. 위장 기기에서는 인셋을 읽지 않는다. */
val safeScaffoldContentWindowInsets: WindowInsets
    @Composable get() =
        if (FrameworkIntegrity.isSpoofedApi34) WindowInsets(0, 0, 0, 0)
        else ScaffoldDefaults.contentWindowInsets

/**
 * Compose 를 거치지 않고 데코뷰의 rootWindowInsets 에서 상태바+내비게이션바 인셋을 읽는다.
 * 프레임워크가 더 낮아 statusBars()/navigationBars() 마저 없을 수 있으므로 통째로 감싼다.
 */
@Composable
private fun platformSystemBarsPadding(): PaddingValues {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view, density) {
        val insets = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@runCatching null
            view.rootWindowInsets?.getInsets(
                PlatformWindowInsets.Type.statusBars() or PlatformWindowInsets.Type.navigationBars()
            )
        }.getOrNull()
        if (insets == null) {
            PaddingValues(0.dp)
        } else {
            with(density) {
                PaddingValues(
                    start = insets.left.toDp(),
                    top = insets.top.toDp(),
                    end = insets.right.toDp(),
                    bottom = insets.bottom.toDp(),
                )
            }
        }
    }
}
