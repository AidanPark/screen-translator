package com.galaxy.airviewdictionary.ui.screen.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.galaxy.airviewdictionary.data.local.capture.CaptureRepository
import com.galaxy.airviewdictionary.ui.screen.AVDActivity
import timber.log.Timber


/**
 * 화면 캡처 권한을 요청하는 Activity.
 * 권한이 획득 되면 CaptureRepository 에 획득한 intent 를 저장한다.
 * @see CaptureRepository
 */
class ScreenCapturePermissionRequesterActivity : AVDActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mediaProjectionManager: MediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val screenCaptureLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            Timber.tag(TAG).d("ActivityResultContracts MediaProjection resultCode ${result.resultCode} token ${result.data} ")
            val resultIntent = Intent()
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                CaptureRepository.mediaProjectionToken = result.data!!.clone() as Intent
                setResult(Activity.RESULT_OK, resultIntent)
            } else {
                setResult(Activity.RESULT_CANCELED, resultIntent)
            }
            finish()
        }

        // 화면 캡처 권한 요청.
        // Android 14(API 34)+ 에서는 '단일 앱 / 전체 화면' 선택지가 뜨는데,
        // 전체 화면 캡처로 고정하여 사용자가 헷갈리지 않고 전체 화면만 공유하도록 한다.
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 일부 기기(커스텀 ROM 등)는 API 34 를 표방하면서도 MediaProjectionConfig 클래스가 없어
            // ClassNotFoundException/NoClassDefFoundError 가 난다 → 기본 캡처 인텐트로 폴백 (2.6.0 크래시)
            try {
                mediaProjectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } catch (t: Throwable) {
                mediaProjectionManager.createScreenCaptureIntent()
            }
        } else {
            mediaProjectionManager.createScreenCaptureIntent()
        }
        screenCaptureLauncher.launch(captureIntent)
    }
}
