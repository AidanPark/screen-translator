package com.galaxy.airviewdictionary

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.widget.Toast
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.initialize
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // timber
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        if (!BuildConfig.DEBUG) {
            // firebase
            Firebase.initialize(context = this)

            // firebase app check
            Firebase.appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance(),
            )

            /**
             * Google 애널리틱스에서 동의 설정 확인 및 업데이트하기
             * https://support.google.com/analytics/answer/14275483?hl=ko&utm_id=ad
             */
            val consentMap = mapOf(
                FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to FirebaseAnalytics.ConsentStatus.GRANTED,
                FirebaseAnalytics.ConsentType.AD_STORAGE to FirebaseAnalytics.ConsentStatus.GRANTED,
                FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to FirebaseAnalytics.ConsentStatus.GRANTED,
                FirebaseAnalytics.ConsentType.AD_USER_DATA to FirebaseAnalytics.ConsentStatus.GRANTED
            )
            Firebase.analytics.setConsent(consentMap)

            // 위장 프레임워크 관측: SDK_INT 는 34+ 라면서 API 34 필수 메서드가 없는
            // 가상/개조 기기(에뮬레이터·클라우드폰)를 크래시와 애널리틱스에서 분류한다.
            // (2.6.1 의 WindowInsetsCompat systemOverlays NoSuchMethodError 가 이 부류)
            val integrity = frameworkIntegrityLabel()
            Firebase.crashlytics.apply {
                setCustomKey("framework_integrity", integrity)
                setCustomKey("build_fingerprint", Build.FINGERPRINT)
                setCustomKey("hardware", Build.HARDWARE)
                val dm = resources.displayMetrics
                setCustomKey("screen", "${dm.widthPixels}x${dm.heightPixels}@${dm.densityDpi}dpi")
            }
            Firebase.analytics.setUserProperty("framework_integrity", integrity)
        }

        // 광고 SDK 초기화는 AdGateActivity 가 동의 수집 후 백그라운드에서 수행한다.
        // (여기서의 중복 초기화는 콜드스타트에 SDK 내부 락을 잡아 ANR 을 유발했다 — 2.6.0)
        // OCR 모델 프리페치도 클라이언트 생성(바인더 IPC)이 있어 메인스레드 밖에서 돌린다.
        CoroutineScope(Dispatchers.IO).launch {
            prefetchOcrModels()
        }
    }

    /**
     * 언번들(GMS) ML Kit OCR 모델을 앱 첫 실행 시 미리 내려받는다.
     * 번들 대신 Play 서비스가 모델을 제공하므로 앱 크기가 크게 줄지만,
     * 신규 기기에서는 첫 사용 전 모델을 받아야 한다. 이를 시작 시점에 미리 처리하고,
     * 실제로 다운로드가 필요한 경우에만 "준비 중" 안내를 한 번 띄운다.
     * 이미 설치돼 있으면 아무 동작도 하지 않는다(매 실행 호출해도 안전).
     */
    private fun prefetchOcrModels() {
        val recognizers: List<TextRecognizer> = listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
        )
        val moduleInstall = ModuleInstall.getClient(this)

        moduleInstall.areModulesAvailable(*recognizers.toTypedArray())
            .addOnSuccessListener { response ->
                if (response.areModulesAvailable()) {
                    // 이미 준비됨 — 조용히 종료.
                    recognizers.forEach { it.close() }
                    return@addOnSuccessListener
                }
                // 모델이 없다 → 다운로드하고, 진행 상태를 사용자에게 한 번 알린다.
                val listener = object : InstallStatusListener {
                    private var announced = false
                    override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                        when (update.installState) {
                            InstallState.STATE_PENDING,
                            InstallState.STATE_DOWNLOADING,
                            InstallState.STATE_INSTALLING -> {
                                if (!announced) {
                                    announced = true
                                    showToast(getString(R.string.ocr_model_preparing))
                                }
                            }
                            InstallState.STATE_COMPLETED,
                            InstallState.STATE_FAILED,
                            InstallState.STATE_CANCELED -> {
                                Timber.tag("MLKit").d("OCR 모델 설치 종료: state=${update.installState}")
                                moduleInstall.unregisterListener(this)
                                recognizers.forEach { it.close() }
                            }
                        }
                    }
                }
                val request = ModuleInstallRequest.newBuilder()
                    .apply { recognizers.forEach { addApi(it) } }
                    .setListener(listener)
                    .build()
                moduleInstall.installModules(request)
                    .addOnFailureListener { e ->
                        Timber.tag("MLKit").w(e, "OCR 모델 다운로드 요청 실패 — 첫 사용 시 재시도")
                        moduleInstall.unregisterListener(listener)
                        recognizers.forEach { it.close() }
                    }
            }
            .addOnFailureListener {
                // 가용성 확인 실패 시에도 설치는 시도한다(폴백).
                val request = ModuleInstallRequest.newBuilder()
                    .apply { recognizers.forEach { addApi(it) } }
                    .build()
                moduleInstall.installModules(request)
                    .addOnCompleteListener { recognizers.forEach { it.close() } }
            }
    }

    /**
     * 기기 신분(SDK_INT)과 실제 프레임워크의 일치 여부를 확인한다.
     * API 34+ 를 자칭하면 반드시 있어야 하는 WindowInsets.Type.systemOverlays() 가 없으면
     * 빌드 속성을 위장한 가상 안드로이드(에뮬레이터/클라우드폰/개조 ROM)로 판정한다.
     */
    private fun frameworkIntegrityLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return "ok"
        return try {
            WindowInsets.Type::class.java.getMethod("systemOverlays")
            "ok"
        } catch (t: Throwable) {
            "spoofed_api34"
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }
}
