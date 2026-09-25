package com.galaxy.airviewdictionary.ui.screen.overlay.targethandle

import android.animation.Animator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalWifiStatusbarConnectedNoInternet4
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.core.OverlayService
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfo
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfoHolder
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.local.vision.WritingDirection
import com.galaxy.airviewdictionary.data.local.vision.model.VisionText
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.extensions.isNetworkAvailable
import com.galaxy.airviewdictionary.extensions.toPx
import com.galaxy.airviewdictionary.extensions.vibrate
import com.galaxy.airviewdictionary.ui.screen.main.SettingsActivity
import com.galaxy.airviewdictionary.ui.screen.overlay.Event
import com.galaxy.airviewdictionary.ui.screen.overlay.OverlayView
import com.galaxy.airviewdictionary.ui.screen.overlay.dialog.DialogView
import com.galaxy.airviewdictionary.ui.screen.overlay.fixedarea.FixedAreaView
import com.galaxy.airviewdictionary.ui.screen.overlay.menubar.MenuBarView
import com.galaxy.airviewdictionary.ui.screen.overlay.selection.AreaSelectionView
import com.galaxy.airviewdictionary.ui.screen.overlay.visiontext.VisionTextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Singleton
import kotlin.math.sqrt


/**
 * 스크린 번역 핸들 뷰
 * 터치 시작시 화면 캡처를 요청하고, 캡처가 되면 화면에 드래그 포인터를 표시한다.
 */
@Singleton
class TargetHandleView private constructor() : OverlayView() {

    companion object {
        val INSTANCE: TargetHandleView by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TargetHandleView() }
    }

    private lateinit var viewModel: TargetHandleViewModel

    private var viewWidth = 0

    private var viewHeight = 0

    private var pointerDimen = 0

    private val pointerOffsetXState = MutableStateFlow(0)

    private val pointerOffsetYState = MutableStateFlow(0)

    override lateinit var layoutParams: WindowManager.LayoutParams

    override val composable: @Composable () -> Unit = @Composable {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        val isDarkMode = isSystemInDarkTheme()

        val textDetectMode by viewModel.preferenceRepository.textDetectModeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = TextDetectMode.SENTENCE
        )
        val pointerPosition: Point? by viewModel.pointerPositionFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = null
        )
        val pointerStoppedPosition: Point? by viewModel.pointerStoppedPositionFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = null
        )
        LaunchedEffect(pointerStoppedPosition) {
            Timber.tag(TAG).d("LaunchedEffect pointerStoppedPosition $pointerPosition")
        }
        val captureStatus by viewModel.captureStatusFlow.collectAsStateWithLifecycle()
        LaunchedEffect(pointerStoppedPosition) {
            Timber.tag(TAG).d("LaunchedEffect captureStatus $captureStatus")
        }
        val fixedAreaViewState by FixedAreaView.fixedAreaViewStateFlow.collectAsStateWithLifecycle()
        LaunchedEffect(pointerStoppedPosition) {
            Timber.tag(TAG).d("LaunchedEffect fixedAreaViewState $fixedAreaViewState")
        }
        val translateStatus by viewModel.translateStatusFlow.collectAsStateWithLifecycle()
        val targetPending by viewModel.targetPendingFlow.collectAsStateWithLifecycle()
        val motionEventState by viewModel.motionEventFlow.collectAsStateWithLifecycle()
        LaunchedEffect(pointerStoppedPosition) {
            Timber.tag(TAG).d("LaunchedEffect motionEventState $motionEventState")
        }
        val menuOperatingState by MenuBarView.operatingStateFlow.collectAsStateWithLifecycle()
        val pointerOffsetX by pointerOffsetXState.collectAsStateWithLifecycle()
        val pointerOffsetY by pointerOffsetYState.collectAsStateWithLifecycle()
        val translationState by viewModel.translationFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = null
        )
        val pointerPositionedVisionText by viewModel.pointerPositionedVisionTextFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = null
        )
        val dragHandleHaptic by viewModel.preferenceRepository.dragHandleHapticFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = false
        )
        val areaSelecting by viewModel.areaSelectingStateFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = false
        )
        LaunchedEffect(pointerStoppedPosition) {
            Timber.tag(TAG).d("LaunchedEffect areaSelecting $areaSelecting")
        }
        val isWritingRtl = remember { mutableStateOf(false) }
        val sourceLanguageCode by viewModel.preferenceRepository.sourceLanguageCodeFlow.collectAsStateWithLifecycle(
            lifecycle = lifecycleOwner.lifecycle,
            initialValue = "auto"
        )
        LaunchedEffect(sourceLanguageCode) {
            val writingDirection = Language.writingDirection(sourceLanguageCode, false)
            isWritingRtl.value = writingDirection == WritingDirection.RTL
        }

        // 햅틱 출력
        val previousVisionText = remember { mutableStateOf<VisionText?>(null) }
        LaunchedEffect(dragHandleHaptic, pointerStoppedPosition, pointerPositionedVisionText, textDetectMode) {
            if (pointerPositionedVisionText == null) {
                previousVisionText.value = null
            } else if (dragHandleHaptic
                && pointerStoppedPosition != null
                && pointerPositionedVisionText != previousVisionText.value
                && (textDetectMode == TextDetectMode.WORD
                        || textDetectMode == TextDetectMode.SENTENCE
                        || textDetectMode == TextDetectMode.PARAGRAPH)
            ) {
                context.vibrate()
                previousVisionText.value = pointerPositionedVisionText
            }
        }

        // SELECT 모드 AreaSelectionView 런칭
        LaunchedEffect(textDetectMode, pointerStoppedPosition) {
            if (pointerStoppedPosition != null && textDetectMode == TextDetectMode.SELECT && !AreaSelectionView.INSTANCE.isRunning.get()) {
                if (dragHandleHaptic) {
                    context.vibrate()
                }
                AreaSelectionView.INSTANCE.cast(context, pointerStoppedPosition!!)
            }
        }

        // FIXED_AREA 모드 FixedAreaView 런칭
        LaunchedEffect(textDetectMode, pointerStoppedPosition) {
            if (pointerStoppedPosition != null && textDetectMode == TextDetectMode.FIXED_AREA && !FixedAreaView.INSTANCE.isRunning.get()) {
                if (dragHandleHaptic) {
                    context.vibrate()
                }
                FixedAreaView.INSTANCE.cast(context, pointerStoppedPosition!!)
            }
        }

        // 설정 화면이 처음 닫힌 뒤, 핸들 더블탭으로 설정을 다시 열 수 있음을 딱 한 번 안내한다.
        val settingsLive by SettingsActivity.liveStateFlow.collectAsStateWithLifecycle()
        val wasSettingsLive = remember { mutableStateOf(false) }
        LaunchedEffect(settingsLive) {
            val justClosed = wasSettingsLive.value && !settingsLive
            wasSettingsLive.value = settingsLive
            if (justClosed
                && !viewModel.preferenceRepository.isSettingsReopenHintShownFlow.first()
                && !SettingsReopenHintView.INSTANCE.isRunning.get()
            ) {
                delay(600) // 설정이 닫히고 핸들이 자리 잡을 시간
                if (!SettingsActivity.liveStateFlow.value) {
                    SettingsReopenHintView.INSTANCE.cast(context, Point(layoutParams.x, layoutParams.y))
                }
            }
        }

        Column(
            modifier = Modifier
                .wrapContentSize()
//                .background(Color.Cyan)
                .alpha(if (captureStatus == CaptureStatus.Requested || (textDetectMode == TextDetectMode.FIXED_AREA && fixedAreaViewState == FixedAreaView.State.Translating)) 0.01f else 1.0f)
                .width(dimensionResource(id = R.dimen.target_handle_width))
                .height(dimensionResource(id = R.dimen.target_handle_height)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(dimensionResource(id = R.dimen.target_pointer_dimen))
                    .offset { IntOffset(pointerOffsetX, pointerOffsetY) },
                contentAlignment = Alignment.Center
            ) {
                // 포인터가 멈춘 뒤 대상을 찾는 동안(OCR·문단 읽기)과 번역을 기다리는 동안 돈다.
                if ((targetPending || translateStatus == TranslateStatus.Requested) && textDetectMode != TextDetectMode.SELECT) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(dimensionResource(id = R.dimen.target_pointer_progress_dimen)),
                        color = Color(0xFF48baef),
                        strokeWidth = 1.8.dp
                    )
                }
                Image(
                    painter = painterResource(id = if (areaSelecting) R.drawable.drag_selection_pointer else R.drawable.drag_pointer),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (isWritingRtl.value) rotationY = 180f
                        },
                    // 표적은 제스처가 진행 중일 때만 보인다.
                    // UP 만 제외하는 방식이면 ACTION_CANCEL(시스템이 제스처를 가로챈 경우)에서
                    // 표적이 화면에 남는다. 보일 상태를 명시하는 쪽이 안전하다.
                    alpha = if (
                        motionEventState == MotionEvent.ACTION_DOWN
                        || motionEventState == MotionEvent.ACTION_MOVE
                    ) 1.0f else 0.0f,
                    colorFilter = if (areaSelecting) {
                        if (textDetectMode == TextDetectMode.SELECT) ColorFilter.tint(Color(0x883B6FDB)) else ColorFilter.tint(Color(0x88006600))
                    } else null
                )
            }
            Spacer(modifier = Modifier.height(dimensionResource(id = R.dimen.target_handle_pointer_thumb_space)))
            Box(
                modifier = Modifier
//                .background(Color.Blue)
                    .size(dimensionResource(id = R.dimen.target_handle_width)),
                contentAlignment = Alignment.Center
            ) {
                val alpha by rememberInfiniteTransition(label = "service live anim").animateFloat(
                    initialValue = 1.0f,
                    targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ), label = "service live anim spec"
                )

                /**
                 * !!! Important
                 * 오버레이 뷰가 정적인 상태가 되면 capture response 가 유지되지 않는다.
                 */
                Image(
                    painter = painterResource(id = if (isDarkMode) R.drawable.drag_handle_dark else R.drawable.drag_handle),
                    contentDescription = null,
                    modifier = Modifier
                        .size(dimensionResource(id = R.dimen.target_handle_thumb_dimen))
                        .alpha(alpha)
                )
            }
        }

        // 핸들 도킹
        LaunchedEffect(motionEventState, translationState, menuOperatingState) {
            Timber.tag(TAG).d("LaunchedEffect translationState [$translationState]")
            if (menuOperatingState) {
                cancelDockDragHandle()
            } else {
                // ACTION_CANCEL 도 제스처 종료다. UP 만 보면 취소된 뒤 핸들이 도킹되지 않는다.
                if ((motionEventState == MotionEvent.ACTION_UP
                            || motionEventState == MotionEvent.ACTION_CANCEL)
                    && translationState == null
                ) {
                    val loc = IntArray(2)
                    view?.getLocationOnScreen(loc)
                    val posX = loc[0]
                    if (posX < 10.dp.toPx(context)) {
                        scheduleDockDragHandle(context, true, 500)
                    } else if ((screenInfo.width - viewWidth - 10.dp.toPx(context)) < posX) {
                        scheduleDockDragHandle(context, false, 500)
                    } else if (viewModel.dragHandleDocking) {
                        scheduleDockDragHandle(context, posX < screenInfo.width / 2, viewModel.dockingDelay)
                    }
                }
            }
        }
    }

    override val touchListener: (Context) -> View.OnTouchListener? = { applicationContext ->

        val isRTL = (applicationContext.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)

        object : View.OnTouchListener {

            val tapDetector = GestureDetector(applicationContext, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (!SettingsActivity.liveStateFlow.value) {
                        launchInAVDCoroutineScope {
                            delay(200)
                            if (!VisionTextView.INSTANCE.isRunning.get()) {
                                applicationContext.vibrate()
                                SettingsActivity.start(applicationContext)
                            }
                        }
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                }
            })

            var touchStartX = 0f
            var touchStartY = 0f
            var dragStartX = 0
            var dragStartY = 0

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                tapDetector.onTouchEvent(event)

                // actionMasked 를 써야 멀티터치의 포인터 인덱스 비트에 분기가 어긋나지 않는다.
                viewModel.motionEventFlow.value = event.actionMasked
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (applicationContext.isNetworkAvailable()) {
                            touchStartX = event.rawX
                            touchStartY = event.rawY
                            dragStartX = layoutParams.x
                            dragStartY = layoutParams.y
                            dragHandleDockingJob?.cancel()
                            viewModel.dockStateFlow.value = false
                        } else {
                            launchInOverlayViewCoroutineScope {
                                DialogView.INSTANCE.cast(
                                    applicationContext = applicationContext,
                                    icon = Icons.Default.SignalWifiStatusbarConnectedNoInternet4,
                                    dialogTitle = applicationContext.getString(R.string.message_network_unavailable),
                                    dialogText = applicationContext.getString(R.string.message_network_unavailable_detail),
                                    onConfirm = {}
                                )
                            }
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
                        layoutParams.x = (dragStartX + (event.rawX - touchStartX)).toInt()
                        layoutParams.y = (dragStartY + (event.rawY - touchStartY)).toInt()
                        updateLayout(applicationContext)

                        // 화면 가장자리 이동 시 포인터 위치 조정. (포인터 위치는 statusBarHeight 기준 y = 0)
                        val loc = IntArray(2)
                        view?.getLocationOnScreen(loc)

                        val centerX = loc[0] + viewWidth / 2
                        val adjustionPositionWidth = viewWidth * 6 / 10   // 수평방향 가장자리 포인터 위치 조정 범위

                        val _screenStartAdjustionPosition = adjustionPositionWidth
                        val _screenEndAdjustionPosition = screenInfo.width - adjustionPositionWidth

                        val _pointerOffsetX = when {
                            centerX < _screenStartAdjustionPosition -> _screenStartAdjustionPosition - centerX
                            centerX > _screenEndAdjustionPosition -> _screenEndAdjustionPosition - centerX
                            else -> 0
                        } * if (isRTL) 1 else -1

                        pointerOffsetXState.value = _pointerOffsetX

                        val bottomLeft = loc[1] + viewHeight
                        val _screenBottomStart = screenInfo.height - viewHeight
                        val _pointerOffsetY = if (bottomLeft > _screenBottomStart) (bottomLeft - _screenBottomStart) / 2 else 0
                        pointerOffsetYState.value = _pointerOffsetY

                        // 포인터 위치
                        val x = layoutParams.x + viewWidth / 2 + _pointerOffsetX
                        val y = layoutParams.y + pointerDimen / 2 + _pointerOffsetY
                        viewModel.pointerPositionFlow.value = Point(x, y) // 포인터 위치 업데이트
                    }

                    // 삼성 엣지 뒤로가기 제스처처럼 시스템이 터치를 가로채면 ACTION_UP 대신
                    // ACTION_CANCEL 이 온다. 이때도 UP 과 똑같이 정리해야 한다.
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        repositionWithinScreen(applicationContext)
                    }
                }
                return true
            }
        }
    }

    override suspend fun cast(applicationContext: Context) {
        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        viewWidth = applicationContext.resources.getDimensionPixelSize(R.dimen.target_handle_width)
        viewHeight = applicationContext.resources.getDimensionPixelSize(R.dimen.target_handle_height)
        pointerDimen = applicationContext.resources.getDimensionPixelSize(R.dimen.target_pointer_dimen)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            screenInfo.width / 2 - viewWidth / 2,
            screenInfo.height / 2 - viewHeight / 2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        if (isRunning.get()) {
            setAtStartPosition(applicationContext)
        } else {
            super.cast(applicationContext)
        }
    }

    private fun setAtStartPosition(context: Context) {
        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        SayHereView.INSTANCE.clear()
        cancelDockDragHandle()
        layoutParams.x = screenInfo.width / 2 - viewWidth / 2
        layoutParams.y = screenInfo.height / 2 - viewHeight / 2
        updateLayout(context)
    }

    override fun onServiceConnected(overlayService: OverlayService) {
        viewModel = overlayService.getTargetHandleViewModel()
        super.onServiceConnected(overlayService)
    }

    /**
     * 외부에서 포인터 위치를 조작하여 번역을 실행시키기 위한 도구
     */
    fun runTranslation(point: Point, textDetectMode: TextDetectMode) {
        launchInAVDCoroutineScope {
            if (textDetectMode == TextDetectMode.SELECT || textDetectMode == TextDetectMode.FIXED_AREA) {
                /**
                 * 번역을 보여 주기 위한 임시 변경.
                 * 복원은 closeTranslation 에서 수행
                 */
                viewModel.updateTextDetectMode(TextDetectMode.SENTENCE)
                delay(100L)
            }
            viewModel.motionEventFlow.value = MotionEvent.ACTION_DOWN
            delay(1200L) // 번역결과 대기
            viewModel.pointerPositionFlow.value = point
            delay(200L) // 포인터가 일정 시간 이상 머무름 알림
            viewModel.pointerPositionFlow.value = Point(point.x + 1, point.y)
        }
    }

    fun closeTranslation(textDetectMode: TextDetectMode?) {
        if (::viewModel.isInitialized) {
            viewModel.motionEventFlow.value = MotionEvent.ACTION_UP
            textDetectMode?.let { viewModel.updateTextDetectMode(it) }
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                  OverlayServiceEventListener                               //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    override fun onOverlayServiceEvent(overlayService: OverlayService, event: Event) {
        when (event) {
            Event.ConfigurationChanged -> {
                onConfigurationChanged(overlayService.applicationContext)
            }

            Event.Unbind -> {
                onServiceUnbind()
            }

            Event.DockTargetHandleView -> {
                dockDragHandle(overlayService.applicationContext, true)
            }
        }
        super.onOverlayServiceEvent(overlayService, event)
    }

    private fun onServiceUnbind() {
        Timber.tag(TAG).i("#### onServiceUnbind() ####")
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                      핸들 자동 포지셔닝                                       //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 삼성 기기 여부. 삼성은 화면 세로 중앙 가장자리에 '엣지 패널' 핸들이 있어
     * 우리 앱의 도킹과 충돌하므로, 삼성에서는 도킹 기능을 제공하지 않는다.
     */
    private val isSamsungDevice: Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    // 삼성 엣지 패널과 충돌하지 않도록, 삼성에서는 도킹 시 핸들 폭의 이 비율만큼만 화면 밖으로 얕게 숨긴다.
    private val samsungDockHiddenRatio = 0.20

    private var dragHandleDockingJob: Job? = null

    private var dockAnimator: ValueAnimator? = null

    /**
     * 화면 회전 되었을 시 위치 재설정
     */
    private fun onConfigurationChanged(context: Context) {
        Timber.tag(TAG).d("#### onConfigurationChanged() ####")
        viewModel.restartCaptureRepository()

        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        Timber.tag(TAG).d("layoutParams.x ${layoutParams.x} layoutParams.y ${layoutParams.y} screenInfo $screenInfo")

        // 화면 회전 전의 위치에 비례하게 위치 재설정
        layoutParams.x = when (layoutParams.x + viewWidth) {
            viewWidth -> 0
            screenInfo.height -> screenInfo.width - viewWidth
            screenInfo.width -> screenInfo.height - viewWidth
            else -> (layoutParams.x + viewWidth / 2) * screenInfo.width / screenInfo.height - viewWidth / 2
        }

        if (layoutParams.x < 0) {
            layoutParams.x = 0
        } else if ((layoutParams.x + viewWidth) > screenInfo.width) {
            layoutParams.x = screenInfo.width - viewWidth
        }

        layoutParams.y = when (layoutParams.y + viewHeight) {
            viewHeight -> 0
            screenInfo.height -> screenInfo.width - viewHeight
            screenInfo.width -> screenInfo.height - viewHeight
            else -> (layoutParams.y + viewHeight / 2) * screenInfo.height / screenInfo.width - viewHeight / 2
        }

        if (layoutParams.y < 0) {
            layoutParams.y = 0
        } else if ((layoutParams.y + viewHeight) > screenInfo.height) {
            layoutParams.y = screenInfo.height - viewHeight
        }

        // 정중앙 위치 재설정
//         layoutParams.x = screenInfo.width / 2 - viewWidth / 2
//         layoutParams.y = screenInfo.height / 2 - viewHeight / 2

        updateLayout(context)
        Timber.tag(TAG).d("updateLayout layoutParams.x ${layoutParams.x} layoutParams.y ${layoutParams.y} screenInfo $screenInfo")

        // 일정 시간 대기 후 도킹 애니메이션 시작
//        scheduleDockDragHandle(context)
    }

    /**
     * 핸들 드래그 종료가 화면 밖에 되었을 시 화면 안으로 이동
     */
    private fun repositionWithinScreen(applicationContext: Context) {
        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        val loc = IntArray(2)
        view?.getLocationOnScreen(loc)

        val topLeft = Point(loc[0], loc[1])
        val topRight = Point(loc[0] + viewWidth, loc[1])
        val bottomLeft = Point(loc[0], loc[1] + viewHeight)
        val bottomRight = Point(loc[0] + viewWidth, loc[1] + viewHeight)

        val moveX =
            if (topLeft.x < 0) 0 - topLeft.x
            else if (topRight.x > screenInfo.width) (topRight.x - screenInfo.width) * -1
            else 0

        val moveY =
            if (topLeft.y < 0) 0 - topLeft.y
            else if (bottomLeft.y > screenInfo.height) (bottomLeft.y - screenInfo.height) * -1
            else 0

        val move = sqrt(moveX.toDouble() * moveX + moveY * moveY)
        if (move > 0) {
            val fromX = layoutParams.x
            val fromY = layoutParams.y
            SpringAnimation(FloatValueHolder()).apply {
                spring = SpringForce().apply {
                    setStartValue(0f)
                    setFinalPosition(move.toFloat())
                    stiffness = SpringForce.STIFFNESS_LOW
                    dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
                }
                addUpdateListener { _, value, _ ->
                    if (moveX != 0) layoutParams.x = fromX + ((moveX * value) / move).toInt()
                    if (moveY != 0) layoutParams.y = fromY + ((moveY * value) / move).toInt()
                    updateLayout(applicationContext)
                }
            }.start()
        }
    }

    /**
     * 일정 시간 핸들 조작하지 않을 시 화면 왼쪽 가장자리로 핸들 도킹 스케줄링
     */
    private fun scheduleDockDragHandle(context: Context, start: Boolean, delay: Long) {
        dragHandleDockingJob?.cancel()
        if (!AreaSelectionView.INSTANCE.isRunning.get() && !FixedAreaView.INSTANCE.isRunning.get()) {
            dragHandleDockingJob = launchInOverlayViewCoroutineScope {
                delay(delay)
                dockDragHandle(context, start)
            }
        }
    }

    private fun dockDragHandle(context: Context, start: Boolean) {
        Timber.tag(TAG).d("#### dockDragHandle() ####")
        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        val handleWidth = context.resources.getDimensionPixelSize(R.dimen.target_handle_width)
        // 삼성은 엣지 패널과 충돌하므로 핸들의 20%만 얕게 숨긴다. 그 외 기기는 완전히 밀어 넣은 뒤 꼭지만 노출한다.
        val hideDepth = if (isSamsungDevice) (handleWidth * samsungDockHiddenRatio).toInt() else handleWidth
        val targetX = (if (start) -hideDepth else screenInfo.width - handleWidth + hideDepth).toDouble()
        // 도킹 세로 위치 비율. 0.5 = 화면 세로 중앙.
        // 삼성 엣지 패널 핸들(기본 중앙)과 겹치지 않도록 화면 높이의 3/4 지점으로 내린다. (조정 가능)
        val dockYRatio = 0.75
        val targetY = ((screenInfo.height - context.resources.getDimensionPixelSize(R.dimen.target_handle_height) - context.resources.getDimensionPixelSize(R.dimen.target_handle_width)) * dockYRatio).toInt()

        val startX = layoutParams.x
        val startY = layoutParams.y

        // 이동 거리
        val deltaX: Double = targetX - startX
        val deltaY = targetY - startY

        dockAnimator?.cancel()

        // ValueAnimator로 0~1 사이의 비율 값을 생성
        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500 // 애니메이션 지속 시간

            // Ease Out Interpolator 적용
            interpolator = android.view.animation.DecelerateInterpolator()

            addUpdateListener { valueAnimator ->
                // 비율 값을 가져옴 (Ease Out 적용)
                val fraction = valueAnimator.animatedValue as Float

                // 비선형 속도를 위해 fraction 조정 (Ease Out 효과 수동 적용 가능)
                val easedFraction = fraction * fraction * (3 - 2 * fraction) // Smootherstep curve
                val acceleratedFraction = easedFraction * easedFraction // 더 빠르게 시작
                val deceleratedFraction = (1 - easedFraction) * (1 - easedFraction) // 더 느리게 종료

                // 비율(acceleratedFraction)에 따른 x 위치 계산
                val currentX = startX + (deltaX * acceleratedFraction).toInt()

                // 곡선 형태로 y 위치 계산
                val currentY = startY - (deltaY * deceleratedFraction).toInt() + deltaY

                layoutParams.x = currentX
                layoutParams.y = currentY
                updateLayout(context)
            }

            // 도킹 종료 후 핸들 꼭지가 보여지도록 함
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}

                override fun onAnimationEnd(animation: Animator) {
                    // 삼성은 이미 얕게(20%) 도킹돼 있으므로 꼭지 재노출 없이 힌트만 보여준다.
                    if (isSamsungDevice) {
                        showSayHereHintIfNeeded(context, start)
                    } else {
                        exposeTargetHandleKnob(context, start)
                    }
                    viewModel.dockStateFlow.value = true
                    dockAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {}

                override fun onAnimationRepeat(animation: Animator) {}
            })

            start() // 애니메이션 시작
        }
    }

    private fun cancelDockDragHandle() {
        dragHandleDockingJob?.cancel()
        dockAnimator?.cancel()
        dockAnimator = null
        viewModel.dockStateFlow.value = false
    }

    /**
     * 도킹 종료 후 핸들 꼭지가 보여지도록 함
     */
    private fun exposeTargetHandleKnob(context: Context, start: Boolean) {
        Timber.tag(TAG).i("------------- exposeTargetHandleKnob [$start]]")
        val startX = layoutParams.x
        val hideDepth = (context.resources.getDimensionPixelSize(R.dimen.target_handle_width) * if (start) .30 else .32).toInt()
        val deltaX: Int = if (start) hideDepth else -hideDepth
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300 // 애니메이션 지속 시간
            interpolator = android.view.animation.DecelerateInterpolator()

            addUpdateListener { valueAnimator ->
                val fraction = valueAnimator.animatedValue as Float
                val currentX = startX + (deltaX * fraction).toInt()
                layoutParams.x = currentX
                updateLayout(context)
            }

            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}

                override fun onAnimationEnd(animation: Animator) {
                    showSayHereHintIfNeeded(context, start)
                }

                override fun onAnimationCancel(animation: Animator) {}

                override fun onAnimationRepeat(animation: Animator) {}
            })

            start() // 애니메이션 시작
        }
    }

    /**
     * 도킹이 끝난 뒤, 아직 안내한 적 없으면 핸들 위치에 "여기 있어요" 힌트를 띄운다.
     */
    private fun showSayHereHintIfNeeded(context: Context, start: Boolean) {
        launchInAVDCoroutineScope {
            if (start && !viewModel.preferenceRepository.isSayHereLShownFlow.first()) {
                SayHereView.INSTANCE.cast(
                    applicationContext = context,
                    start = true,
                    position = Point(layoutParams.x, layoutParams.y)
                )
            } else if (!start && !viewModel.preferenceRepository.isSayHereRShownFlow.first()) {
                SayHereView.INSTANCE.cast(
                    applicationContext = context,
                    start = false,
                    position = Point(layoutParams.x, layoutParams.y)
                )
            }
        }
    }

    override fun clear() {
        dragHandleDockingJob?.cancel()
        super.clear()
    }
}

