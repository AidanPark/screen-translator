package com.galaxy.airviewdictionary.ui.screen.ads

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.galaxy.airviewdictionary.BuildConfig
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.local.ads.AdGateState
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.galaxy.airviewdictionary.ui.screen.main.GoogleMobileAdsConsentManager
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TargetHandleView
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.TranslationView
import com.galaxy.airviewdictionary.ui.screen.overlay.visiontext.VisionTextView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 광고 게이트 전용 투명 액티비티.
 * 설정 화면을 노출하지 않고, 안내 다이얼로그 → 리워드 광고 순서로 진행한다.
 *
 * 흐름:
 * 1. 안내 다이얼로그 표시 + 광고 로드 시작 (확인 버튼 비활성)
 * 2. 로드 성공/실패가 확정되면 확인 버튼 활성화
 * 3. 확인 클릭 → 로드 성공이면 광고 표시 / 실패면 스킵 취급으로 종료
 *
 * 보상 규칙:
 * - 끝까지 시청(onUserEarnedReward): 앱 종료 시까지 광고 없이 사용
 * - 스킵 / 로드 실패 / 표시 실패 / 동의 미확보 / 뒤로가기: 모두 동일하게 5분 사용권 부여 후 종료
 */
class AdGateActivity : ComponentActivity() {

    private val TAG = javaClass.simpleName

    companion object {

        /** 광고 게이트가 떠 있는지 여부 (중복 실행 방지 + 오버레이 가시성 제어용) */
        val liveStateFlow = MutableStateFlow(false)

        private val isMobileAdsInitializeCalled = AtomicBoolean(false)

        /** 광고 로드 대기 한계. 초과 시 로드 실패로 확정한다 */
        private const val LOAD_TIMEOUT_MILLIS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, AdGateActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /** 광고 로드 진행 상태. 확인 버튼은 Loading 이 아닐 때만 활성화된다. */
    private enum class AdLoadState { Loading, Loaded, Failed }

    private val adLoadStateFlow = MutableStateFlow(AdLoadState.Loading)

    private lateinit var googleMobileAdsConsentManager: GoogleMobileAdsConsentManager

    private var isRewardedAdLoading = false

    private var rewardedAd: RewardedAd? = null

    private var finished = false

    /** 광고가 전체화면으로 표시되었는지 여부. 종료 콜백 유실 대비 안전망(onResume)에서 사용. */
    private var adShown = false

    private var timeoutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15(SDK 35)+ edge-to-edge 강제에 맞춰 이전 버전에서도 동일 동작 (Play Console 권장 조치)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        liveStateFlow.value = true

        // 뒤 화면 전체를 어둡게 덮는다 (SplashActivity 와 동일한 검증된 패턴: 윈도우 레벨 dim)
        val layoutParams = window.attributes
        layoutParams.dimAmount = 0.50f
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = layoutParams

        // 광고 게이트~광고 종료 동안 플로팅 오버레이(핸들/번역창/인식 하이라이트)가 광고 위에 떠 있지 않도록 숨긴다.
        // 메뉴바(MenuBarView)는 자체 가시성 로직이 liveStateFlow 를 구독하여 스스로 숨긴다.
        TargetHandleView.INSTANCE.hideTemporarily()
        TranslationView.INSTANCE.hideTemporarily()
        VisionTextView.INSTANCE.hideTemporarily()

        // 뒤로가기로 다이얼로그를 닫는 것은 광고 스킵과 동일 취급
        onBackPressedDispatcher.addCallback(this) {
            finishAsSkip()
        }

        setContent {
            val adLoadState by adLoadStateFlow.collectAsState()

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xF2222222),
                ) {
                    Column(
                        modifier = Modifier
                            .width(300.dp)
                            .height(320.dp)
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 콘텐츠 영역: 남는 공간을 차지하며 세로 중앙 정렬
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // 상태 아이콘 슬롯 (고정 크기 - 상태가 바뀌어도 창 크기가 변하지 않도록)
                            Box(
                                modifier = Modifier.height(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                when (adLoadState) {
                                    AdLoadState.Loading -> CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(40.dp)
                                    )

                                    AdLoadState.Loaded -> Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Ad ready",
                                        tint = Color(0xFF81C784),
                                        modifier = Modifier.size(48.dp)
                                    )

                                    AdLoadState.Failed -> Icon(
                                        imageVector = Icons.Rounded.CloudOff,
                                        contentDescription = "Ad unavailable",
                                        tint = Color(0x99FFFFFF),
                                        modifier = Modifier.size(44.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                            // 로딩 문구 슬롯 (2줄 높이 예약 - 사라져도 창 크기가 변하지 않도록)
                            Text(
                                text = if (adLoadState == AdLoadState.Loading) stringResource(R.string.ad_gate_loading) else "",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                minLines = 2,
                                maxLines = 2,
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.ad_gate_reward_notice),
                                color = Color(0xB3FFFFFF),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        // 확인 버튼: 다이얼로그 하단에 전체 너비로 고정. 로드 확정 전에는 비활성.
                        Button(
                            onClick = {
                                when (adLoadStateFlow.value) {
                                    AdLoadState.Loaded -> showRewardedVideo()
                                    AdLoadState.Failed -> finishAsSkip()
                                    AdLoadState.Loading -> Unit // disabled 상태라 도달하지 않음
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            // 광고 로드 성공/실패가 확정되어야 활성화된다
                            enabled = adLoadState != AdLoadState.Loading,
                        ) {
                            Text(
                                text = stringResource(android.R.string.ok),
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
        }

        // 광고 로드가 오래 걸리면 로드 실패로 확정한다 (버튼이 영영 비활성으로 남지 않도록)
        timeoutJob = lifecycleScope.launch {
            delay(LOAD_TIMEOUT_MILLIS)
            if (!finished && adLoadStateFlow.value == AdLoadState.Loading) {
                Timber.tag(TAG).w("Ad load timeout -> Failed")
                adLoadStateFlow.value = AdLoadState.Failed
            }
        }

        googleMobileAdsConsentManager = GoogleMobileAdsConsentManager.getInstance(this)
        googleMobileAdsConsentManager.gatherConsent(this) { error ->
            if (error != null) {
                Timber.tag(TAG).d("gatherConsent error ${error.errorCode}: ${error.message}")
            }
            if (googleMobileAdsConsentManager.canRequestAds) {
                initializeMobileAdsSdk()
            } else {
                // 동의 미확보 → 로드 실패로 확정
                adLoadStateFlow.value = AdLoadState.Failed
            }
        }

        // 이전 세션에서 확보한 동의로 즉시 로드 시도
        if (googleMobileAdsConsentManager.canRequestAds) {
            initializeMobileAdsSdk()
        }
    }

    override fun onResume() {
        super.onResume()
        // 안전망: 광고가 표시된 후 게이트로 복귀했는데(=광고가 닫혔는데)
        // 종료 콜백(onAdDismissed/onAdFailedToShow)이 유실된 경우에도
        // 게이트가 화면에 남지 않도록 잠시 기다렸다가 스킵 처리로 종료한다.
        if (adShown && !finished) {
            lifecycleScope.launch {
                delay(1000) // 정상 콜백이 먼저 처리될 시간
                if (adShown && !finished) {
                    Timber.tag(TAG).w("Ad closed but no dismiss callback; finishing as skip (safety net)")
                    finishAsSkip()
                }
            }
        }
    }

    override fun onDestroy() {
        liveStateFlow.value = false
        timeoutJob?.cancel()
        // 숨겨둔 플로팅 오버레이 복원 (메뉴바는 liveStateFlow 변경으로 스스로 복원)
        TargetHandleView.INSTANCE.showFromTemporaryHide()
        TranslationView.INSTANCE.showFromTemporaryHide()
        VisionTextView.INSTANCE.showFromTemporaryHide()
        super.onDestroy()
    }

    private fun initializeMobileAdsSdk() {
        if (isMobileAdsInitializeCalled.getAndSet(true)) {
            loadRewardedAd()
            return
        }

        // Set your test devices.
        if (BuildConfig.DEBUG) {
            MobileAds.setRequestConfiguration(
                RequestConfiguration.Builder().setTestDeviceIds(listOf("BA6732E32C6CA0D01FB929ECC2FDA19F")).build()
            )
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Initialize the Google Mobile Ads SDK on a background thread.
            MobileAds.initialize(this@AdGateActivity) {}
            runOnUiThread {
                loadRewardedAd()
            }
        }
    }

    private fun loadRewardedAd() {
        if (finished || isRewardedAdLoading || rewardedAd != null) return
        isRewardedAdLoading = true

        val adUnitId =
            if (BuildConfig.DEBUG) {
                "ca-app-pub-xxxxxxxxxxxxxxxx/xxxxxxxxxx" // Test ad unit ID
            } else {
                FirebaseRemoteConfig.getInstance().getString(RemoteConfigRepository.AD_UNIT_ID)
            }
        Timber.tag(TAG).i("loadRewardedAd adUnitId $adUnitId")

        RewardedAd.load(
            this,
            adUnitId,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Timber.tag(TAG).d("onAdFailedToLoad: ${adError.message}")
                    isRewardedAdLoading = false
                    rewardedAd = null
                    // 로드 실패 확정 → 확인 버튼 활성화 (누르면 스킵 취급으로 종료)
                    adLoadStateFlow.value = AdLoadState.Failed
                }

                override fun onAdLoaded(ad: RewardedAd) {
                    Timber.tag(TAG).d("Ad was loaded.")
                    isRewardedAdLoading = false
                    rewardedAd = ad
                    timeoutJob?.cancel()
                    // 로드 성공 확정 → 확인 버튼 활성화 (누르면 광고 표시)
                    adLoadStateFlow.value = AdLoadState.Loaded
                }
            },
        )
    }

    private fun showRewardedVideo() {
        if (finished || adShown) return

        val ad = rewardedAd
        if (ad == null) {
            finishAsSkip()
            return
        }

        // 광고를 끝까지 시청(onUserEarnedReward)했는지 여부. 닫힐 때 분기에 사용.
        var earned = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Timber.tag(TAG).d("Ad dismissed. earned=$earned")
                rewardedAd = null
                if (!earned) {
                    // 끝까지 보지 않고 닫음(스킵) → 5분 사용권
                    AdGateState.grantSkipWindow()
                }
                finishGate()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Timber.tag(TAG).d("Ad failed to show: ${adError.message}")
                rewardedAd = null
                finishAsSkip()
            }

            override fun onAdShowedFullScreenContent() {
                Timber.tag(TAG).d("Ad showed fullscreen content.")
                adShown = true
            }
        }

        ad.show(this) {
            // 끝까지 시청 → 이번 세션 동안 광고 없이 사용
            earned = true
            AdGateState.grantAdFreeSession()
            Timber.tag(TAG).i("User earned the reward -> ad-free session")
        }
    }

    /** 스킵과 동일 취급: 5분 사용권 부여 후 종료 */
    private fun finishAsSkip() {
        if (finished) return
        AdGateState.grantSkipWindow()
        finishGate()
    }

    private fun finishGate() {
        if (finished) return
        finished = true
        timeoutJob?.cancel()
        finish()
    }
}
