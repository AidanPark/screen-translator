package com.galaxy.airviewdictionary.ui.screen.overlay.targethandle

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.MotionEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.local.capture.CapturePreventedException
import com.galaxy.airviewdictionary.data.local.capture.CaptureRepository
import com.galaxy.airviewdictionary.data.local.capture.CaptureResponse
import com.galaxy.airviewdictionary.data.local.capture.NoMediaProjectionTokenException
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.ads.AdGateState
import com.galaxy.airviewdictionary.data.local.secure.SecureRepository
import com.galaxy.airviewdictionary.data.local.secure.UsageInfo
import com.galaxy.airviewdictionary.data.local.tts.TTSReadTarget
import com.galaxy.airviewdictionary.data.local.tts.TTSRepository
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.model.Line
import com.galaxy.airviewdictionary.data.local.vision.model.Paragraph
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.local.vision.model.VisionText
import com.galaxy.airviewdictionary.data.local.vision.model.Word
import com.galaxy.airviewdictionary.data.remote.firebase.AnalyticsRepository
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.TranslationRepository
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import com.galaxy.airviewdictionary.extensions.finishService
import com.galaxy.airviewdictionary.extensions.voiceNameMatchesLanguage
import com.galaxy.airviewdictionary.extensions.gotoStore
import com.galaxy.airviewdictionary.extensions.openGoogleApp
import com.galaxy.airviewdictionary.extensions.toPx
import com.galaxy.airviewdictionary.ui.screen.ads.AdGateActivity
import com.galaxy.airviewdictionary.ui.screen.main.SettingsActivity
import com.galaxy.airviewdictionary.ui.screen.overlay.dialog.DialogView
import com.galaxy.airviewdictionary.ui.screen.overlay.menubar.MenuBarView
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.DismissRunningCommand
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.TTSStatus
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.TranslationView
import com.galaxy.airviewdictionary.ui.screen.overlay.visiontext.VisionTextView
import com.galaxy.airviewdictionary.ui.screen.permissions.ScreenCapturePermissionRequesterActivity
import getAverageTextBlockHeight
import getBoundingBoxUnion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale
import kotlin.math.sqrt


@Suppress("UNCHECKED_CAST")
class TargetHandleViewModelFactory(
    private val applicationContext: Context,
    private val secureRepository: SecureRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
    private val preferenceRepository: PreferenceRepository,
    private val captureRepository: CaptureRepository,
    private val visionRepository: VisionRepository,
    private val translationRepository: TranslationRepository,
    private val ttsRepository: TTSRepository,
    private val analyticsRepository: AnalyticsRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TargetHandleViewModel::class.java)) {
            return TargetHandleViewModel(
                applicationContext = applicationContext,
                secureRepository = secureRepository,
                remoteConfigRepository = remoteConfigRepository,
                preferenceRepository = preferenceRepository,
                captureRepository = captureRepository,
                visionRepository = visionRepository,
                translationRepository = translationRepository,
                ttsRepository = ttsRepository,
                analyticsRepository = analyticsRepository,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel Class")
    }
}

class TargetHandleViewModel(
    private val applicationContext: Context,
    private val secureRepository: SecureRepository,
    val remoteConfigRepository: RemoteConfigRepository,
    val preferenceRepository: PreferenceRepository,
    val captureRepository: CaptureRepository,
    val visionRepository: VisionRepository,
    val translationRepository: TranslationRepository,
    val ttsRepository: TTSRepository,
    val analyticsRepository: AnalyticsRepository,
) : ViewModel() {

    private val TAG = javaClass.simpleName

    private var startTime = System.nanoTime()

    private var endTime = System.nanoTime()

    /**
     * target handle 모션 이벤트 flow
     */
    val motionEventFlow = MutableStateFlow(MotionEvent.INVALID_POINTER_ID)

    /**
     * target handle pointer 위치 Flow
     */
    val pointerPositionFlow = MutableStateFlow<Point?>(null)

    /**
     * target handle 이 docking 되었는지의 여부 flow
     */
    val dockStateFlow = MutableStateFlow<Boolean>(false)

    /**
     * 캡처된 bitmap 의 OCR api 요청 결과.
     */
    val visionResultFlow = MutableStateFlow<com.galaxy.airviewdictionary.data.local.vision.model.Transaction?>(null)

    /**
     * screen capture 진행상태의 flow.
     * [CaptureStatus.Idle] 캡처기능 유휴상태
     * [CaptureStatus.Requested] 캡처 요청, 결과 대기 상태
     * [CaptureStatus.Captured] 캡처 결과 수신 상태
     */
    val captureStatusFlow = MutableStateFlow(CaptureStatus.Idle)

    /**
     * 번역 진행상태의 flow.
     * [TranslateStatus.Idle] 번역 요청 유휴상태
     * [TranslateStatus.Requested] 번역 요청, 결과 대기 상태
     * [TranslateStatus.Translated] 번역 결과 수신 상태
     */
    val translateStatusFlow = MutableStateFlow(TranslateStatus.Idle)


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                         SecureInfo                                         //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////



    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                       Service Operation                                    //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private data class RemoteConfig(
        val serviceAvailable: Boolean,
        val latestVersionCode: Long,
        val forceUpdate: Boolean,
    )

    private val serviceOperationInfoFlow: Flow<RemoteConfig> =
        remoteConfigRepository.remoteConfigFlow
            .filterNotNull()
            .map { remoteConfig ->
                Timber.tag(TAG).i("remoteConfig: $remoteConfig")

                val serviceAvailable: Boolean = remoteConfig[RemoteConfigRepository.SERVICE_AVAILABLE_KEY]?.asString()?.let {
                    val jsonObject = JSONObject(it)
                    Timber.tag(TAG).d("jsonObject: $jsonObject")
                    val defaultServiceAvailable = jsonObject.getBoolean("default")
                    Timber.tag(TAG).d("defaultServiceAvailable: $defaultServiceAvailable")
                    jsonObject.optBoolean(Locale.getDefault().country, defaultServiceAvailable)
                } ?: true
                Timber.tag(TAG).i("serviceAvailable: $serviceAvailable")

                val packageInfo = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
                val versionCode: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                }
                Timber.tag(TAG).i("versionCode: $versionCode")
                val forceUpdateVersionCode = remoteConfig[RemoteConfigRepository.FORCE_UPDATE_VERSION_CODE_KEY]?.asLong() ?: 0

                RemoteConfig(
                    serviceAvailable = serviceAvailable,
                    latestVersionCode = remoteConfig[RemoteConfigRepository.LATEST_VERSION_CODE_KEY]?.asLong() ?: 0,
                    forceUpdate = versionCode < forceUpdateVersionCode,
                )
            }
            .distinctUntilChanged()

    private fun collectServiceOperationInfoFlow() {
        viewModelScope.launch {
            serviceOperationInfoFlow
                .collect { remoteConfig: RemoteConfig ->
                    Timber.tag(TAG).i("remoteConfig: $remoteConfig")
                    // 서비스 점검중 입니다.
                    if (!remoteConfig.serviceAvailable) {
                        delay(5000)
                        DialogView.INSTANCE.cast(
                            applicationContext = applicationContext,
                            icon = Icons.Default.Engineering,
                            dialogTitle = applicationContext.getString(R.string.message_service_unavailable),
                            dialogText = applicationContext.getString(R.string.message_service_unavailable_detail),
                            onConfirm = { applicationContext.finishService() }
                        )
                    }
                    // 강제 업데이트
                    else if (remoteConfig.forceUpdate) {
                        delay(5000)
                        DialogView.INSTANCE.cast(
                            applicationContext = applicationContext,
                            icon = Icons.Default.Update,
                            dialogTitle = applicationContext.getString(R.string.message_force_update),
                            dialogText = applicationContext.getString(R.string.message_force_update_detail),
                            onConfirm = { applicationContext.gotoStore(finishService = true) },
                        )
                    }
                }
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                        preference                                          //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private var _textDetectMode = TextDetectMode.SENTENCE

    val textDetectMode: TextDetectMode
        get() = _textDetectMode

    private var _dragHandleDocking = true

    val dragHandleDocking: Boolean
        get() = _dragHandleDocking

    private var _dockingDelay = 3000L

    val dockingDelay: Long
        get() = _dockingDelay

    private var ttsSpeechRate = 1.0f

    private var ttsReadTarget = TTSReadTarget.SOURCE

    private fun collectPreference() {
        viewModelScope.launch {
            preferenceRepository.textDetectModeFlow.collect { newValue ->
                _textDetectMode = newValue
            }
        }

        viewModelScope.launch {
            preferenceRepository.dragHandleDockingFlow.collect { newValue ->
                _dragHandleDocking = newValue
            }
        }

        viewModelScope.launch {
            preferenceRepository.dockingDelayFlow.collect { newValue ->
                _dockingDelay = newValue
            }
        }

        viewModelScope.launch {
            preferenceRepository.ttsSpeechRateFlow
                .collect { ttsSpeechRate_ ->
                    ttsSpeechRate = ttsSpeechRate_
                }
        }

        viewModelScope.launch {
            preferenceRepository.ttsReadTargetFlow
                .collect { ttsReadTarget_ ->
                    ttsReadTarget = ttsReadTarget_
                }
        }
    }

    fun updateTextDetectMode(textDetectMode: TextDetectMode) {
        preferenceRepository.update(PreferenceRepository.TEXT_DETECT_MODE, textDetectMode.name)
    }

    fun updateTranslationKitType(kitType: TranslationKitType) {
        preferenceRepository.update(PreferenceRepository.TRANSLATION_KIT_TYPE, kitType.name)
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                      Capture request                                       //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private fun collectTargetHandleMotionEvent() {
        viewModelScope.launch {
            motionEventFlow
                .filterNotNull()
                .collect { motionEvent ->
                    if (motionEvent == MotionEvent.ACTION_DOWN) {
                        Timber.tag(TAG).i("#### TargetHandle motionEvent MotionEvent.ACTION_DOWN ####")
                        if (
                            textDetectMode == TextDetectMode.WORD
                            || textDetectMode == TextDetectMode.SENTENCE
                            || textDetectMode == TextDetectMode.PARAGRAPH
                        ) {
                            requestCapture()
                        }
                    } else if (motionEvent == MotionEvent.ACTION_UP) {
                        Timber.tag(TAG).i("#### TargetHandle motionEvent MotionEvent.ACTION_UP ####")
                        cancelCapture()
                    }
                }
        }
    }

    /**
     * [TargetHandleView] 의 요청에 따라 화면캡처를 수행한다.
     * 캡처된 bitmap 의 OCR 을 요청한다.
     */
    private fun requestCapture() {
        startTime = System.nanoTime()
        Timber.tag(TAG).i("#### requestCapture() ####")

        visionResultFlow.value = null
        captureStatusFlow.value = CaptureStatus.Requested

        viewModelScope.launch {
            Timber.tag(TAG).d("requestCapture viewModelScope.launch -------------- 0")
            // 캡처 전 화면에 보여지는 OverlayView 들을 숨기기 위한 딜레이
            delay(50)
            Timber.tag(TAG).d("requestCapture viewModelScope.launch -------------- 1")
            val captureResponse: CaptureResponse = captureRepository.request()
            Timber.tag(TAG).d("requestCapture viewModelScope.launch -------------- 2 $captureResponse")
            if (captureResponse is CaptureResponse.Success) {
                endTime = System.nanoTime()
//                val duration = (endTime - startTime) / 1_000_000 // 나노초를 밀리초로 변환
                // Timber.tag(TAG).d("captureRepository.request() 실행 시간: $duration 밀리초")

                Timber.tag(TAG).d("captureResponse.bitmap ${captureResponse.bitmap.width} ${captureResponse.bitmap.height}")

                val motionEventState = motionEventFlow.first()
                Timber.tag(TAG).d("requestCapture motionEventState $motionEventState")
                if (motionEventState == MotionEvent.ACTION_DOWN || motionEventState == MotionEvent.ACTION_MOVE) {
                    captureStatusFlow.value = CaptureStatus.Captured
                    requestVision(captureResponse.bitmap)
                }
            } else if (captureResponse is CaptureResponse.Error) {
                Timber.tag(TAG).d("CaptureResponse.Error ${captureResponse.t.toString()}")
                if (captureResponse.t is NoMediaProjectionTokenException) {
                    captureStatusFlow.value = CaptureStatus.PermissionRequested
                    // 화면 캡처 권한을 요청
                    val intent = Intent(
                        applicationContext,
                        ScreenCapturePermissionRequesterActivity::class.java
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    applicationContext.startActivity(intent)
                } else if (captureResponse.t is CapturePreventedException) {
                    // 캡처 방지 알림
                    // Timber.tag(TAG).e("CapturePreventedException: 캡처 방지 알림")
                    // captureResponse.t.checkerBitmap 처리
                }
            }
        }
    }

    fun cancelCapture() {
        pointerPositionFlow.value = null
        pointerPositionedTranslationFlow.value = null
        if (captureStatusFlow.value != CaptureStatus.PermissionRequested) {
            captureStatusFlow.value = CaptureStatus.Idle
        }
        translateStatusFlow.value = TranslateStatus.Idle
        visionResultFlow.value = null
    }

    fun restartCaptureRepository() {
        captureRepository.restart()
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                        ml-kit vision                                       //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * 캡처된 bitmap 의 OCR 을 요청한다.
     */
    private suspend fun requestVision(capturedBitmap: Bitmap) {
        startTime = System.nanoTime()
        Timber.tag(TAG).i("#### requestVision() ####")

        val sourceLanguageCode: String = preferenceRepository.sourceLanguageCodeFlow.first()
        val visionResponse: VisionResponse = visionRepository.request(
            bitmap = capturedBitmap,
            sourceLanguageCode = sourceLanguageCode,
        )

        if (visionResponse is VisionResponse.Success) {
            endTime = System.nanoTime()
            val duration = (endTime - startTime) / 1_000_000 // 나노초를 밀리초로 변환
            // Timber.tag(TAG).d("visionRepository.request() 실행 시간: $duration 밀리초")

            val motionEventState = motionEventFlow.first()
            if (motionEventState == MotionEvent.ACTION_DOWN || motionEventState == MotionEvent.ACTION_MOVE) {
                Timber.tag(TAG).i("set visionResult :\n====[${visionResponse.result.text}]====")
                visionResultFlow.value = visionResponse.result
            }
        } else if (visionResponse is VisionResponse.Error) {
            Timber.tag(TAG).e("visionResponse err ${visionResponse.t}")
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                         번역 대상 지정                                       //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /** 포인터 멈춤 으로 인정되는 거리 마진 */
    private val POINTER_STOPPED_MARGIN_DISTANCE: Int = applicationContext.resources.getDimensionPixelSize(R.dimen.targethandle_view_pointer_stopped_distance)

    /** 포인터 멈춤 으로 인정되는 시간 마진 */
    private val POINTER_STOPPED_MARGIN_DURATION: Long
        get() = if (textDetectMode == TextDetectMode.SELECT) 220 else 80

    /**
     * [pointerPositionFlow] (포인터 위치) Flow 를 포인터가 머무는 위치로 변환 발행.
     */
    val pointerStoppedPositionFlow: Flow<Point?> = channelFlow {
        var _pointerPosition: Point? = null
        var lastEmittedPoint: Point? = null // 마지막으로 emit된 Point를 추적
        var timerJob: Job? = null

        // Helper function to calculate distance
        fun calculateDistance(point1: Point, point2: Point): Double {
            val dx = point1.x - point2.x
            val dy = point1.y - point2.y
            return sqrt((dx * dx + dy * dy).toDouble())
        }

        // Cancel the timer job
        fun cancelTimer() {
            timerJob?.cancel()
            timerJob = null
        }

        // Start the timer to emit the position
        fun startTimer(pointerPosition: Point?) {
            cancelTimer() // Cancel any existing timer
            timerJob = launch {
                delay(POINTER_STOPPED_MARGIN_DURATION)
                pointerPosition?.let { currentPoint ->
                    // Emit only if the distance to the last emitted point is greater than the margin
                    val isNotDuplicate = lastEmittedPoint?.let {
                        calculateDistance(currentPoint, it) > POINTER_STOPPED_MARGIN_DISTANCE
                    } ?: true // If lastEmittedPoint is null, it's not a duplicate

                    if (isNotDuplicate) {
                        send(currentPoint) // Emit the position using `send`
                        lastEmittedPoint = currentPoint // Update the last emitted point
                    }
                }
                _pointerPosition = null // Reset for the next emit
                cancelTimer()
            }
        }

        // Combine pointerPositionFlow and motionEventFlow
        combine(pointerPositionFlow, motionEventFlow) { pointerPosition, motionEvent ->
            Pair(pointerPosition, motionEvent)
        }.collectLatest { (pointerPosition, motionEvent) ->
            if (pointerPosition != null &&
                (motionEvent == MotionEvent.ACTION_DOWN || motionEvent == MotionEvent.ACTION_MOVE)
            ) {
                if (_pointerPosition == null) {
                    _pointerPosition = pointerPosition
                    startTimer(pointerPosition) // Start the timer for the first time
                } else {
                    if (calculateDistance(pointerPosition, _pointerPosition!!) <= POINTER_STOPPED_MARGIN_DISTANCE) {
                        // Pointer is within the margin, continue waiting
                    } else {
                        cancelTimer() // Cancel the ongoing timer
                        _pointerPosition = null // Reset the pointer position
                        send(null) // Emit null using `send`
                    }
                }
            } else {
                cancelTimer() // Cancel the timer when pointer is invalid
                _pointerPosition = null
                lastEmittedPoint = null
                send(null) // Emit null using `send`
            }
        }
    }

    /**
     * [pointerStoppedPositionFlow] (포인터가 머무는 위치) 와 [visionResultFlow] 를 취합하여 해당 위치의 VisionText 를 발행한다.
     */
    val pointerPositionedVisionTextFlow: Flow<VisionText?> = combine(
        pointerStoppedPositionFlow.filterNotNull(),
        visionResultFlow
    ) { pointerStoppedPosition, visionResult ->
        visionResult?.let {
            getPointerPositionedVisionText(
                visionResult = visionResult,
                pointerPosition = pointerStoppedPosition,
                textDetectMode = textDetectMode
            )
        }
    }.distinctUntilChanged()

    private fun getPointerPositionedVisionText(
        visionResult: com.galaxy.airviewdictionary.data.local.vision.model.Transaction,
        pointerPosition: Point,
        textDetectMode: TextDetectMode,
    ): VisionText? {
        if (textDetectMode == TextDetectMode.SELECT) {
            val boundingBox = visionResult.text.getBoundingBoxUnion()
            val averageTextBlockHeight = visionResult.text.getAverageTextBlockHeight()
            val writingDirection = visionResult.mostFrequentWritingDirection()
            if (boundingBox != null && averageTextBlockHeight > 0 && writingDirection != null) {
                return Word(
                    boundingBox = boundingBox,
                    representation = visionResult.text.text,
                    writingDirection = writingDirection,
                    chars = emptyList(),
                    presetFontHeight = averageTextBlockHeight
                )
            }
            return null
        }

        val positionedParagraph: Paragraph? = visionResult.paragraphs.find { paragraph ->
            paragraph.boundingBox.contains(pointerPosition.x, pointerPosition.y)
        }
//        Timber.tag(TAG).d("pointerPosition.x [${pointerPosition.x}] pointerPosition.x [${pointerPosition.x}] positionedParagraph [${positionedParagraph?.representation}]")
        if (textDetectMode == TextDetectMode.PARAGRAPH) {
            return positionedParagraph
        }

        if (textDetectMode == TextDetectMode.SENTENCE) {
            positionedParagraph?.sentences?.forEach {
//                Timber.tag(TAG).d("sentence : ${it.boundingBox}, ${it.representation}")
//                it.lines.forEach {
//                    Timber.tag(TAG).i("line : ${it.boundingBox}, ${it.representation}")
//                }
//                Timber.tag(TAG).i(
//                    "boundingPolygon : ${it.boundingPolygon.points}, $pointerPosition ${
//                        it.boundingPolygon.contains(pointerPosition)
//                    }"
//                )
            }
            return positionedParagraph?.sentences?.find { sentence ->
                sentence.boundingPolygon.contains(pointerPosition)
            }
        }

        val positionedLine: Line? = positionedParagraph?.lines?.find { line ->
            val expandedRect = expandedRect(line.boundingBox)
            expandedRect.contains(pointerPosition.x, pointerPosition.y)
        }
        val positionedWord: Word? = positionedLine?.words?.find { word ->
            val expandedRect = expandedRect(word.boundingBox)
            expandedRect.contains(pointerPosition.x, pointerPosition.y)
        }
        return positionedWord
    }

    /**
     * TextDetectMode.LINE 과 TextDetectMode.WORD 에 판정 마진을 주기 위해 boundingBox 크기를 늘리기 위한 함수
     */
    private fun expandedRect(rect: Rect, delta: Int = 3.dp.toPx(applicationContext)): Rect {
        return Rect(rect.left, rect.top - delta, rect.right, rect.bottom + delta)
    }

    /**
     * 포인터가 머무는 위치의 VisionText 를 확인하고 아래의 작업 수행.
     * - VisionTextView 를 launch 한다.
     * - 해당 text 에 대한 번역을 요청한다.
     * - 번역이 완료되면 TranslationView 를 launch 한다.
     */
    private fun collectVisionTextForTranslationView() {
        viewModelScope.launch {
            pointerPositionedVisionTextFlow
                .distinctUntilChanged { old, new ->
                    old?.representation == new?.representation
                }
                .filterNotNull()
                .collect { pointerPositionedVisionText ->
                    VisionTextView.INSTANCE.cast(applicationContext, pointerPositionedVisionText)

                    visionResultFlow.value?.let { visionResultTransaction ->
                        val translationKitType: TranslationKitType = preferenceRepository.translationKitTypeFlow.first()
                        val targetLanguageCode: String = preferenceRepository.targetLanguageCodeFlow.first()
                        // Timber.tag(TAG).d("kitType $kitType")
                        // Timber.tag(TAG).d("targetLanguageCode $targetLanguageCode")
                        val motionEventState = motionEventFlow.first()
                        if (motionEventState == MotionEvent.ACTION_DOWN || motionEventState == MotionEvent.ACTION_MOVE) {
                            translateStatusFlow.value = TranslateStatus.Requested

                            Timber.tag(TAG).d("sourceText ${pointerPositionedVisionText.representation}")
                            Timber.tag(TAG).d("translationKitType $translationKitType")

                            // 자동 감지(auto)일 때는 화면 전체 OCR 텍스트가 아니라 실제 번역할 문장으로 언어를 감지한다.
                            // 화면에 앱 오버레이("Auto → 한국어" 등)나 브라우저의 다른 언어 UI 가 섞여 있으면
                            // 전체 텍스트 기반 감지가 엉뚱한 언어를 반환해(예: 영어 문장을 ko 로) 원문→원문 무번역이 되기 때문.
                            val sourceLanguagePref = preferenceRepository.sourceLanguageCodeFlow.first()
                            val sourceLanguageCode = if (sourceLanguagePref.equals("auto", ignoreCase = true)) {
                                val perTextCode = visionRepository.identifyLanguage(pointerPositionedVisionText.representation)
                                if (perTextCode == "und") visionResultTransaction.detectedLanguageCode else perTextCode
                            } else {
                                visionResultTransaction.detectedLanguageCode
                            }
                            Timber.tag(TAG).d("sourceLanguageCode $sourceLanguageCode (pref $sourceLanguagePref)")

                            val motionEventState = motionEventFlow.first()
                            Timber.tag(TAG).d("motionEventState $motionEventState")
                            if (motionEventState == MotionEvent.ACTION_DOWN || motionEventState == MotionEvent.ACTION_MOVE) {
                                translationRepository.request(
                                    translationKitType,
                                    sourceLanguageCode,
                                    targetLanguageCode,
                                    pointerPositionedVisionText.representation,
                                )
                                    .also {
                                        val motionEventState = motionEventFlow.first()
                                        if (motionEventState == MotionEvent.ACTION_DOWN || motionEventState == MotionEvent.ACTION_MOVE) {
                                            translateStatusFlow.value = TranslateStatus.Translated

                                            when (it) {
                                                is TranslationResponse.Success -> {
                                                    val transaction = Transaction(
                                                        sourceLanguageCode = it.result.sourceLanguageCode,
                                                        targetLanguageCode = it.result.targetLanguageCode,
                                                        sourceText = pointerPositionedVisionText.representation,
                                                        translationKitType = it.result.translationKitType,
                                                        detectedLanguageCode = it.result.detectedLanguageCode,
                                                        resultText = it.result.resultText,
                                                        modelName = it.result.modelName,
                                                    )
                                                    Timber.tag(TAG).d("translationRepository Translated transaction $transaction")

                                                    TranslationView.INSTANCE.cast(
                                                        applicationContext,
                                                        transaction,
                                                        pointerPositionedVisionText
                                                    )
                                                    pointerPositionedTranslationFlow.value = transaction
                                                }

                                                is TranslationResponse.Error -> {
                                                    Timber.tag(TAG).d("Response Error ${it.t}")
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                    }
                }
        }
    }

    /**
     * 포인터가 머무는 위치의 번역 결과.
     */
    private val pointerPositionedTranslationFlow = MutableStateFlow<Transaction?>(null)


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                        TranslationState                                    //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * dismiss 제어를 위한 커맨드
     */
    private val dismissRunningCommandFlow = MutableStateFlow(DismissRunningCommand.RESUME)

    fun resumeDismissRunning() {
        dismissRunningCommandFlow.value = DismissRunningCommand.RESUME
    }

    fun pauseDismissRunning() {
        dismissRunningCommandFlow.value = DismissRunningCommand.PAUSE
    }

    fun rerunDismissRunning() {
        dismissRunningCommandFlow.value = DismissRunningCommand.RERUN
    }

    private data class TranslationStateData(
        val visionText: VisionText?,
        val translation: Transaction?,
        val motionEvent: Int?,
        val ttsStatus: TTSStatus,
        val dismissRunningCommand: DismissRunningCommand
    )

    /**
     * 포인터 포지션의 VisionText,
     * 포인터 포지션의 Translation,
     * 포인터 MotionEvent,
     * TTS 재생상태,
     * dismiss 제어 커맨드,
     * dismiss delay time
     * 위 6가지 상태를 종합적으로 고려하여 TranslationView 에서 사용할 데이타를 발행한다.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val translationFlow: Flow<Pair<VisionText, Transaction>?> = combine(
        pointerPositionedVisionTextFlow,
        pointerPositionedTranslationFlow,
        motionEventFlow,
        ttsRepository.ttsStatusFlow,
        dismissRunningCommandFlow
    ) { visionText, translation, motionEvent, ttsStatus, dismissRunningCommand ->
        TranslationStateData(visionText, translation, motionEvent, ttsStatus, dismissRunningCommand)
    }.flatMapLatest { (visionText, translation, motionEvent, ttsStatus, dismissRunningCommand) ->
//        Timber.tag(TAG).d("---------------------------------------------------")
//        Timber.tag(TAG).d("motionEvent != MotionEvent.ACTION_UP : $motionEvent ${motionEvent != MotionEvent.ACTION_UP}")
//        Timber.tag(TAG).d("visionText != null : ${visionText != null}")
//        Timber.tag(TAG).d("translation != null : ${translation != null}")
//        Timber.tag(TAG).d("ttsStatus : $ttsStatus")
//        Timber.tag(TAG).d("visionText.representation == translation.sourceText : ${visionText?.representation == translation?.sourceText}")
//        Timber.tag(TAG).d("dismissRunningCommand : $dismissRunningCommand")

        if (motionEvent == null) {
            emptyFlow()
        }

        // MotionEvent.ACTION_UP인 경우 일정시간 후 null emit
        else if (motionEvent == MotionEvent.ACTION_UP) {
            // TTS 재생 중이 아니라면
            if (ttsStatus != TTSStatus.Playing) {
                // DismissRunningCommand.RESUME 이라면 일정시간 후 null emit.
                if (dismissRunningCommand == DismissRunningCommand.RESUME) {
                    val translationCloseDelay = preferenceRepository.translationCloseDelayFlow.first()
                    delay(translationCloseDelay)
                    flowOf(null)
                }
                // DismissRunningCommand.PAUSE 이라면 아무 데이타도 발행하지 않음.
                else if (dismissRunningCommand == DismissRunningCommand.PAUSE) {
                    emptyFlow()
                }
                // DismissRunningCommand.RERUN 이라면 아무 데이타도 발행하지 않고 DismissRunningCommand.RESUME 상태로 변경해줌.
                else {
                    resumeDismissRunning()
                    emptyFlow()
                }
            }
            // TTS 재생 중 이라면 TTS 종료 대기. 아무 데이타도 발행하지 않음.
            else {
                emptyFlow()
            }
        }

        // MotionEvent.ACTION_UP이 아닌 경우
        else {
            // TargetHandle 위치의 텍스트 번역이 된 경우
            if (
                visionText != null &&
                translation != null &&
                visionText.representation == translation.sourceText
            ) {
                // TTS 재생 중 이라면 stop.
                if (ttsStatus == TTSStatus.Playing) {
                    // 여기에서 stopTTS() 를 하면 Automatic TTS playback 기능이 제대로 작동하지 않음
//                    ttsRepository.stopTTS()
                }
                // 번역 데이타를 emit.
                flowOf(Pair(visionText, translation))
            }
            // TargetHandle 위치의 텍스트 번역이 없는 경우
            else {
                // TTS 재생 중 이라면 stop.
                if (ttsStatus == TTSStatus.Playing) {
                    ttsRepository.stopTTS()
                }
                // null emit.
                flowOf(null)
            }
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                              AreaSelectionView, FixedAreaView                              //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    val areaSelectingStateFlow = MutableStateFlow(false)


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                             TTS                                            //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    // 읽기 대상 설정에 따라 목소리를 맞출 언어. (소스: 번역 시 감지된 언어, 타겟: 번역 대상 언어)
    private val pointerPositionReadLanguageCodeFlow = combine(
        pointerPositionedTranslationFlow,
        preferenceRepository.ttsReadTargetFlow,
    ) { translation, readTarget ->
        when (readTarget) {
            TTSReadTarget.SOURCE -> translation?.detectedLanguageCode
            TTSReadTarget.TARGET -> translation?.targetLanguageCode
        }
    }.distinctUntilChanged()

    private fun collectTranslationVoiceFlow() {
        viewModelScope.launch {
            combine(
                preferenceRepository.ttsOrderedVoiceNamesFlow.distinctUntilChanged(),
                pointerPositionReadLanguageCodeFlow.filterNotNull().distinctUntilChanged()
            ) { orderedVoiceNames, readLanguageCode ->
                Pair(orderedVoiceNames, readLanguageCode)
            }
                .collect { (orderedVoiceNames, readLanguageCode) ->
                    Timber.tag(TAG).i("Ordered Voice Names: $orderedVoiceNames")
                    Timber.tag(TAG).i("Read Language Code: $readLanguageCode")
                    // 우선순위 목록에서 먼저 찾고, 목록에 해당 언어의 목소리가 없으면 기기 목소리 전체에서 찾는다
                    val matchingVoiceName = orderedVoiceNames.firstOrNull { voiceName ->
                        voiceNameMatchesLanguage(voiceName, readLanguageCode)
                    } ?: ttsRepository.availableVoicesFlow.filterNotNull().first().map { voice -> voice.name }.firstOrNull { voiceName ->
                        voiceNameMatchesLanguage(voiceName, readLanguageCode)
                    }
                    Timber.tag(TAG).i("matchingVoiceName: $matchingVoiceName")
                    matchingVoiceName?.let {
                        ttsRepository.setVoice(matchingVoiceName)
                    }
                }
        }
    }

    /**
     * 읽기 대상 설정에 따라 번역의 소스 또는 타겟 텍스트를,
     * 그 텍스트의 언어에 맞는 목소리로 읽는다.
     * (소스 언어가 auto 면 번역 시 감지된 언어를 사용한다)
     */
    fun playTTS(translation: Transaction) {
        val (text, languageCode) = when (ttsReadTarget) {
            TTSReadTarget.SOURCE -> translation.sourceText to (translation.detectedLanguageCode ?: translation.sourceLanguageCode)
            TTSReadTarget.TARGET -> translation.resultText to translation.targetLanguageCode
        }
        if (text == null) return
        ttsRepository.playTTSForLanguage(text, languageCode?.takeIf { it != "auto" }, ttsSpeechRate)
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                           구매 유도                                         //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    fun increaseTrialCount(): Int {
        return secureRepository.increaseTrialCount()
    }

    private fun collectAdGateInfo() {
        // 참여도 분석(elapsed*)의 기준점인 최초 사용 시각을 세션 시작 시 확정한다.
        viewModelScope.launch {
            UsageInfo.getFirstUseTime(applicationContext)
        }

        // 번역 카운트 통계 (앱 리뷰 유도 및 사용량 통계용)
        viewModelScope.launch {
            pointerPositionedTranslationFlow
                .filterNotNull()
                .filter { translation -> translation.resultText != null }
                .distinctUntilChanged { old, new -> old.sourceText == new.sourceText }
                .collect {
                    val trialCount = increaseTrialCount()
                    if (
                        trialCount == 100
                        || trialCount == 200
                        || trialCount == 300
                        || trialCount == 500
                    ) {
                        val hoursTaken = UsageInfo.elapsedHoursSinceFirstUse(applicationContext)
                        analyticsRepository.hoursTakenReport(trialCount, hoursTaken)
                    } else if (trialCount % 1000 == 0) {
                        val daysTaken = UsageInfo.elapsedDaysSinceFirstUse(applicationContext)
                        analyticsRepository.daysTakenReport(trialCount, daysTaken)
                    }
                }
        }

        /**
         * 광고 게이트:
         *   번역 수행 시, 광고 시청/5분 사용권으로 사용 가능한 상태가 아니고(AdGateState.isUsable() == false)
         *   설정 화면 상태가 아니면 리워드 광고를 띄운다.
         *   광고를 끝까지 보면 이번 세션 동안, 스킵/실패하면 5분 동안 다시 뜨지 않는다.
         */
        viewModelScope.launch {
            pointerPositionedTranslationFlow
                .filterNotNull()
                .filter { translation -> translation.resultText != null }
                .distinctUntilChanged { old, new -> old.sourceText == new.sourceText }
                .collect {
                    if (!AdGateState.isUsable() && !SettingsActivity.liveStateFlow.value && !AdGateActivity.liveStateFlow.value) {
                        // 일정 시간 후가 아니라, 핸들에서 손가락을 떼어(ACTION_UP) 번역이 종료된 시점에 광고 게이트를 연다.
                        // (결과 수신 전에 이미 손을 뗀 상태라면 즉시 연다)
                        motionEventFlow.first { motionEvent ->
                            motionEvent == MotionEvent.ACTION_UP
                                    || motionEvent == MotionEvent.ACTION_CANCEL
                                    || motionEvent == MotionEvent.INVALID_POINTER_ID
                        }
                        showAdGate()
                    }
                }
        }
    }

    fun showAdGate() {
        // 리워드 광고 표시를 위해 투명 광고 게이트 액티비티를 연다.
        AdGateActivity.start(applicationContext)
    }

    init {
        Timber.tag(TAG).i("#### init ####")
        secureRepository.acquire()
        captureRepository.acquire()
        translationRepository.acquire()
        ttsRepository.acquire()
        collectServiceOperationInfoFlow()
        collectAdGateInfo()
        collectPreference()
        collectTargetHandleMotionEvent()
        collectVisionTextForTranslationView()
        collectTranslationVoiceFlow()
    }

    override fun onCleared() {
        secureRepository.release()
        captureRepository.release()
        translationRepository.release()
        ttsRepository.release()
        super.onCleared()
    }
}







