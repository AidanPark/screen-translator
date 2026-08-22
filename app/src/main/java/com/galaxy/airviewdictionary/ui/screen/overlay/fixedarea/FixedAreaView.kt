package com.galaxy.airviewdictionary.ui.screen.overlay.fixedarea


import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.SignalWifiStatusbarConnectedNoInternet4
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.core.OverlayService
import com.galaxy.airviewdictionary.data.local.capture.CapturePreventedException
import com.galaxy.airviewdictionary.data.local.capture.CaptureResponse
import com.galaxy.airviewdictionary.data.local.capture.NoMediaProjectionTokenException
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfo
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfoHolder
import com.galaxy.airviewdictionary.data.local.ads.AdGateState
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.local.vision.model.Transaction
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import com.galaxy.airviewdictionary.extensions.isNetworkAvailable
import com.galaxy.airviewdictionary.extensions.setFromPoints
import com.galaxy.airviewdictionary.extensions.vibrate
import com.galaxy.airviewdictionary.ui.screen.main.SettingsActivity
import com.galaxy.airviewdictionary.ui.screen.overlay.Event
import com.galaxy.airviewdictionary.ui.screen.overlay.OverlayView
import com.galaxy.airviewdictionary.ui.screen.overlay.dialog.DialogView
import com.galaxy.airviewdictionary.ui.screen.overlay.selection.createOverlaidBitmap
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TargetHandleView
import com.galaxy.airviewdictionary.ui.screen.overlay.targethandle.TargetHandleViewModel
import com.galaxy.airviewdictionary.ui.screen.permissions.ScreenCapturePermissionRequesterActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Singleton


/**
 * 고정 선택 영역 뷰
 */
@Singleton
open class FixedAreaView : OverlayView() {

    enum class State {
        Idle, // 화면 진입 상태
        Handling, // TargetHandleView 를 조작하고 영역을 지정하기 까지의 상태
        Translating, // 영역을 지정한 뒤 번역시작 버튼을 누른 상태
        TranslatingHandling, // Translating 상태에서 영역을 터치하여 잠시 메뉴를 보여지게 하는 상태
    }

    companion object {
        val INSTANCE: FixedAreaView by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { FixedAreaView() }

        val fixedAreaViewStateFlow = MutableStateFlow(State.Idle)

        val translationFlow = MutableStateFlow("")

        private const val SELECTION_AREA_RATIO_LIMIT: Int = 50

        private const val BACKGROUND_ALPHA: Float = 0.28f

        private const val BACKGROUND_FADE_OUT_ALPHA: Float = 0.0f

        private const val BACKGROUND_FADE_OUT_DURATION: Int = 8000
    }

    private lateinit var targetHandleViewModel: TargetHandleViewModel

    override lateinit var layoutParams: WindowManager.LayoutParams

    private lateinit var layoutTopCenter: Point

    private var fixedAreaViewStateFlowJob: Job? = null

    private var translateJob: Job? = null

    private var fixedAreaViewBackgroundAlphaJob: Job? = null

    override val composable: @Composable () -> Unit = @Composable {
        val localView = LocalView.current
        val context = LocalContext.current
        val resources = context.resources
        val lifecycleOwner = LocalLifecycleOwner.current
        val composeScope = rememberCoroutineScope()

        val isDarkMode = isSystemInDarkTheme()
        val fixedAreaViewColor = colorResource(if (isDarkMode) R.color.fixed_area_color else R.color.fixed_area_color_dark)
        val fixedAreaViewBackgroundAlpha = remember { Animatable(BACKGROUND_ALPHA) }

        val stoppedDistancePx = with(resources.displayMetrics) {
            resources.getDimensionPixelSize(R.dimen.targethandle_view_pointer_stopped_distance)
        }
        val selectionMinWidthPx = with(resources.displayMetrics) {
            resources.getDimensionPixelSize(R.dimen.area_selection_min_width)
        }
        val selectionMinHeightPx = with(resources.displayMetrics) {
            resources.getDimensionPixelSize(R.dimen.area_selection_min_height)
        }

        val textDetectMode by targetHandleViewModel.preferenceRepository.textDetectModeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = TextDetectMode.FIXED_AREA
        )

        if (textDetectMode != TextDetectMode.FIXED_AREA) {
            clear()
        }

        val dragHandleHaptic by targetHandleViewModel.preferenceRepository.dragHandleHapticFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = false
        )

        fun haptic() {
            if (dragHandleHaptic) {
                context.vibrate()
            }
        }

        val selectionStarted = remember { mutableStateOf(true) }
        val selectedCompleted = remember { mutableStateOf(false) }
        val selectionAreaRatio = remember { mutableIntStateOf(0) }
        val selectedArea = remember { mutableStateOf(Rect()) }

        val pointerStoppedPosition: Point? by targetHandleViewModel.pointerStoppedPositionFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = null
        )

        val fixedAreaViewState: State by fixedAreaViewStateFlow.collectAsStateWithLifecycle()

        // SettingsActivity live 상태 flow
        val settingsActivityLiveState by SettingsActivity.liveStateFlow.collectAsStateWithLifecycle()

        // 영역선택 시작
        fun startSelection() {
            FixedAreaTranslationView.INSTANCE.clear()
            fixedAreaViewStateFlowJob?.cancel()
            fixedAreaViewStateFlow.value = State.Idle
            translateJob?.cancel()
            fixedAreaViewBackgroundAlphaJob?.cancel()
            composeScope.launch {
                fixedAreaViewBackgroundAlpha.snapTo(BACKGROUND_ALPHA)
            }
            selectionStarted.value = true
            targetHandleViewModel.areaSelectingStateFlow.value = true
            translationFlow.value = ""
            haptic()
        }

        // 영역선택 리셋
        fun resetSelection(currentPosition: Point) {
            targetHandleViewModel.cancelCapture()

            selectedCompleted.value = false // 영역선택 취소
            targetHandleViewModel.areaSelectingStateFlow.value = false
            translationFlow.value = ""
            selectionStarted.value = false // 영역선택 시작 취소

            layoutParams.x = currentPosition.x
            layoutParams.y = currentPosition.y
            layoutParams.width = layoutParams.x + 1
            layoutParams.height = currentPosition.y + 1
            updateLayout(context)

            view?.post {
                layoutTopCenter = currentPosition // 현재 포인터 지점으로 layoutTopLeft 를 갱신
            }
        }

        // 영역선택 완료
        fun completeSelection(_selectedArea: Rect) {
            selectedCompleted.value = true
            selectedArea.value = _selectedArea
            targetHandleViewModel.areaSelectingStateFlow.value = false
            if (selectionAreaRatio.intValue in 5..SELECTION_AREA_RATIO_LIMIT) {
                haptic()
            } else if (selectionAreaRatio.intValue > SELECTION_AREA_RATIO_LIMIT) {
                clear()
            }
        }

        LaunchedEffect(pointerStoppedPosition) {
            // Timber.tag(TAG).d("LaunchedEffect pointerStoppedPosition $pointerStoppedPosition")

            if (pointerStoppedPosition == null) {
                return@LaunchedEffect
            }

            // 영역선택 시작되지 않은 경우
            if (!selectionStarted.value) {
                startSelection()
                return@LaunchedEffect
            }

            // Selected Area
            val r: Rect = Rect().apply {
                setFromPoints(layoutTopCenter, pointerStoppedPosition!!)
            }
            // Selected Area 가 최소 너비/높이를 만족하는지 확인
            if ((r.width() > selectionMinWidthPx && r.height() > selectionMinHeightPx)
                || (r.width() > selectionMinHeightPx && r.height() > selectionMinWidthPx)
            ) {
                // 영역선택 완료
                completeSelection(r)
            }
        }

        val pointerPosition by targetHandleViewModel.pointerPositionFlow.collectAsStateWithLifecycle()
        val _pointerPosition = remember { mutableStateOf<Point?>(null) }

        pointerPosition?.let { currentPosition ->
            // 포인터를 layoutTopLeft 위나 왼쪽으로 이동한 경우
            if (currentPosition.y + stoppedDistancePx / 2 < layoutTopCenter.y
                || currentPosition.x + stoppedDistancePx / 2 < layoutTopCenter.x
            ) {
                resetSelection(currentPosition) // 선택작업 리셋
            }
            // 포인터를 layoutTopLeft 아래 & 오른쪽으로 이동한 경우
            else {
                // 영역선택이 시작된 경우
                if (selectionStarted.value) {
                    if (_pointerPosition.value != null) {
                        // 이전 포인트 보다 layoutTopLeft 위나 왼쪽으로 포인터를 이동하는 경우
                        if (currentPosition.y + stoppedDistancePx / 2 < _pointerPosition.value!!.y
                            || currentPosition.x + stoppedDistancePx / 2 < _pointerPosition.value!!.x
                        ) {
                            resetSelection(currentPosition) // 선택작업 리셋
                        }
                    }
                }
                // 영역선택이 시작되지 않은 경우
                else {
                    layoutTopCenter = currentPosition // 현재 포인터 지점으로 layoutTopLeft 를 갱신
                }
            }

            _pointerPosition.value = currentPosition

            // layoutTopLeft 부터 현재 포인터 위치 까지의 영역 사각형
            val r: Rect = Rect().apply {
                setFromPoints(layoutTopCenter, currentPosition)
            }

            layoutParams.x = r.left
            layoutParams.y = r.top
            layoutParams.width = r.width()
            layoutParams.height = r.height()

            val area = r.width() * r.height()
            val screenInfo: ScreenInfo = ScreenInfoHolder.get()
            val percentage = (area.toDouble() / screenInfo.safeArea.toDouble()) * 100
            Timber.tag(TAG).d("screenInfo $screenInfo    area $area  percentage $percentage")
            selectionAreaRatio.intValue = percentage.toInt()
            updateLayout(context)
        }

        fun fixedAreaVisible() {
            if (fixedAreaViewStateFlow.value == State.Translating || fixedAreaViewStateFlow.value == State.TranslatingHandling) {
                fixedAreaViewStateFlowJob?.cancel()
                // Box 를 클릭 하면 MenuBarView 와 TargetHandleView 가 보여 지도록 함.
                fixedAreaViewStateFlow.value = State.TranslatingHandling
                // 3초 후 다시 State.Translating 으로
                fixedAreaViewStateFlowJob = launchInOverlayViewCoroutineScope {
                    delay(BACKGROUND_FADE_OUT_DURATION.toLong())
                    fixedAreaViewStateFlow.value = State.Translating
                }

                fixedAreaViewBackgroundAlphaJob?.cancel()
                fixedAreaViewBackgroundAlphaJob = composeScope.launch {
                    fixedAreaViewBackgroundAlpha.snapTo(BACKGROUND_ALPHA)
                    fixedAreaViewBackgroundAlpha.animateTo(
                        targetValue = BACKGROUND_FADE_OUT_ALPHA,
                        animationSpec = tween(durationMillis = BACKGROUND_FADE_OUT_DURATION)
                    )
                }
            }
        }

        LaunchedEffect(settingsActivityLiveState) {
            if (settingsActivityLiveState) {
                fixedAreaVisible()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(fixedAreaViewColor.copy(alpha = fixedAreaViewBackgroundAlpha.value))
                .clickable(
                    indication = null, // 클릭 효과 제거
                    interactionSource = remember { MutableInteractionSource() } // 상태 추적 제거
                ) {
                    fixedAreaVisible()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (fixedAreaViewState == State.Idle || fixedAreaViewState == State.Handling) {
                if (selectionAreaRatio.intValue <= SELECTION_AREA_RATIO_LIMIT) { // 화면 면적의 50% 이하
                    IconButton(
                        onClick = {
                            if (context.isNetworkAvailable()) {
                                fun start() {
                                    startFixedAreaTranslate(context, selectedArea.value)
                                    launchInOverlayViewCoroutineScope {
                                        TargetHandleView.INSTANCE.cast(context, true)
                                        FixedAreaTranslationView.INSTANCE.cast(context, selectedArea.value)
                                    }

                                    fixedAreaViewBackgroundAlphaJob?.cancel()
                                    fixedAreaViewBackgroundAlphaJob = composeScope.launch {
                                        fixedAreaViewBackgroundAlpha.animateTo(
                                            targetValue = BACKGROUND_FADE_OUT_ALPHA,
                                            animationSpec = tween(durationMillis = BACKGROUND_FADE_OUT_DURATION)
                                        )
                                    }
                                }

                                launchInOverlayViewCoroutineScope {
                                    DialogView.INSTANCE.cast(
                                        applicationContext = context,
                                        icon = Icons.Default.BatteryAlert,
                                        dialogTitle = context.getString(R.string.message_translate_fixedarea_warn),
                                        dialogText = context.getString(R.string.message_translate_fixedarea_warn_detail),
                                        onConfirm = {
                                            launchInOverlayViewCoroutineScope {
                                                start()
                                            }
                                        }
                                    )
                                }
                            } else {
                                launchInOverlayViewCoroutineScope {
                                    DialogView.INSTANCE.cast(
                                        applicationContext = context,
                                        icon = Icons.Default.SignalWifiStatusbarConnectedNoInternet4,
                                        dialogTitle = context.getString(R.string.message_network_unavailable),
                                        dialogText = context.getString(R.string.message_network_unavailable_detail),
                                        onConfirm = {
                                            clear()
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .size(96.dp)
                            .padding(1.dp) // 버튼 크기
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PlayCircleOutline,
                            contentDescription = "start translation",
                            tint = fixedAreaViewColor,
                            modifier = Modifier
                                .size(52.dp)
                                .padding(2.dp) // 아이콘 크기
                        )
                    }
                }
            }
        }
    }

    override fun onServiceConnected(overlayService: OverlayService) {
        targetHandleViewModel = overlayService.getTargetHandleViewModel()
        super.onServiceConnected(overlayService)
    }

    open suspend fun cast(
        applicationContext: Context,
        startPosition: Point,
    ) {
        this.layoutTopCenter = startPosition
        layoutParams = WindowManager.LayoutParams(
            1,
            1,
            startPosition.x,
            startPosition.y,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        super.cast(applicationContext)
        targetHandleViewModel.areaSelectingStateFlow.value = true
    }

    override fun onOverlayServiceEvent(overlayService: OverlayService, event: Event) {
        when (event) {
            Event.ConfigurationChanged -> {
                clear()
            }

            else -> {}
        }
        super.onOverlayServiceEvent(overlayService, event)
    }

    override fun clear() {
        FixedAreaTranslationView.INSTANCE.clear()
        fixedAreaViewBackgroundAlphaJob?.cancel()
        fixedAreaViewBackgroundAlphaJob = null
        fixedAreaViewStateFlowJob?.cancel()
        fixedAreaViewStateFlowJob = null
        fixedAreaViewStateFlow.value = State.Idle
        translateJob?.cancel()
        translateJob = null
        targetHandleViewModel.areaSelectingStateFlow.value = false
        super.clear()
    }

    private fun startFixedAreaTranslate(context: Context, selectedArea: Rect) {
        translateJob?.cancel()
        fixedAreaViewStateFlowJob?.cancel()
        fixedAreaViewStateFlow.value = State.Translating
        translateJob = launchInOverlayViewCoroutineScope {
            while (fixedAreaViewStateFlow.value == State.Translating || fixedAreaViewStateFlow.value == State.TranslatingHandling) {
                // 0.1초 간격
                delay(100)
                // 광고 사용권이 없으면(스킵/미시청) 광고 게이트를 열고 종료 — 포인터 모드와 동일한 규칙
                if (!AdGateState.isUsable()) {
                    targetHandleViewModel.showAdGate()
                    clear()
                }
                // 화면 캡처 + OCR 요청
                else {
                    requestVision(context, selectedArea)
                }
            }
        }
    }

    private var detectedString = ""

    private suspend fun requestVision(context: Context, selectedArea: Rect) {
        // 캡처 이미지
        val captureResponse: CaptureResponse = targetHandleViewModel.captureRepository.request()
        Timber.tag(TAG).d("captureResponse $captureResponse")
        if (captureResponse !is CaptureResponse.Success) {
            Timber.tag(TAG).d("CaptureResponse.Error ${(captureResponse as CaptureResponse.Error).t}")
            if (captureResponse.t is NoMediaProjectionTokenException) {
                // 화면 캡처 권한을 요청
                val intent = Intent(context, ScreenCapturePermissionRequesterActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                clear()
            } else if (captureResponse.t is CapturePreventedException) {
                // 캡처 방지 알림
                // Timber.tag(TAG).e("CapturePreventedException: 캡처 방지 알림")
                // captureResponse.t.checkerBitmap 처리
            }
            return
        }

        // 영역선택 이미지
        val selectedAreaBitmap = createOverlaidBitmap(captureResponse.bitmap, selectedArea)

        // Test 캡처 이미지 확인

        val sourceLanguageCode: String = targetHandleViewModel.preferenceRepository.sourceLanguageCodeFlow.first()
        val visionResponse: VisionResponse = targetHandleViewModel.visionRepository.request(
            bitmap = selectedAreaBitmap,
            sourceLanguageCode = sourceLanguageCode,
        )

        if (visionResponse !is VisionResponse.Success) {
            return
        }

        val visionResponseString = visionResponse.result.text.text.replace("\n", " ")
//        Timber.tag(TAG).d("[visionResponseString] [$visionResponseString]")
        if (detectedString == visionResponseString) {
            return
        }

        detectedString = visionResponseString
        Timber.tag(TAG).d("[detectedString] $detectedString")
        requestTranslate(visionResponse.result, detectedString)
    }

    private suspend fun requestTranslate(visionResult: Transaction, sourceText: String) {
        val translationKitType: TranslationKitType = targetHandleViewModel.preferenceRepository.translationKitTypeFlow.first()
        val sourceLanguageCode: String = visionResult.detectedLanguageCode
        val targetLanguageCode: String = targetHandleViewModel.preferenceRepository.targetLanguageCodeFlow.first()

        if (sourceText.trim().isEmpty()) {
            translationFlow.value = ""
        } else {
            targetHandleViewModel.translationRepository.request(
                translationKitType = translationKitType,
                sourceLanguageCode = sourceLanguageCode,
                targetLanguageCode = targetLanguageCode,
                sourceText = sourceText,
            ).also {
                when (it) {
                    is TranslationResponse.Success -> {
                        val transaction = com.galaxy.airviewdictionary.data.remote.translation.Transaction(
                            sourceLanguageCode = it.result.sourceLanguageCode,
                            targetLanguageCode = it.result.targetLanguageCode,
                            sourceText = sourceText,
                            translationKitType = it.result.translationKitType,
                            detectedLanguageCode = it.result.detectedLanguageCode,
                            resultText = it.result.resultText,
                            modelName = it.result.modelName,
                        )
                        Timber.tag(TAG).d("===== $translationKitType ${it.result.resultText}")
                        translationFlow.value = it.result.resultText ?: ""
                        targetHandleViewModel.increaseTrialCount()
                    }

                    is TranslationResponse.Error -> {
                        Timber.tag(TAG).d("Response Error ${it.t}")
                    }
                }
            }
        }
    }
}

















