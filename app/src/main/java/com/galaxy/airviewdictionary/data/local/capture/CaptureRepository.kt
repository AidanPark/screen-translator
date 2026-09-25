package com.galaxy.airviewdictionary.data.local.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.graphics.createBitmap
import com.galaxy.airviewdictionary.data.AVDRepository
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfo
import com.galaxy.airviewdictionary.data.local.screen.ScreenInfoHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE 부터 한번 획득한 토큰으로
 * getMediaProjection 을 다시 호출할 수 없으므로
 * setOnImageAvailableListener 에서 한번 이미지를 얻어낸 다음 종료하는 방식을 사용하지 않는다.
 */
@Singleton
class CaptureRepository @Inject constructor(@ApplicationContext val context: Context) : AVDRepository() {

    private enum class State {
        Uninitialized,
        Ready,
    }

    companion object {
        // request() 가 프레임을 기다리는 최대 시간. 이 안에 프레임이 안 오면 프로젝션이 죽은 것으로 보고
        // 재동의 경로로 유도한다(무한 대기 방지).
        private const val CAPTURE_FRAME_TIMEOUT_MS = 3000L

        var mediaProjectionToken: Intent? = null
            set(value) {
                field = value
                Timber.tag("CaptureRepository").i("#### set mediaProjectionToken $value ####")
            }
    }

    private var state: State = State.Uninitialized

    private var mediaProjection: MediaProjection? = null

    private var mediaProjectionStopCallback: MediaProjection.Callback? = null

    private var imageReader: ImageReader? = null

    private var virtualDisplay: VirtualDisplay? = null

    private val captureResponseFlow = MutableStateFlow<CaptureResponse?>(null)

    private val handler = Handler(Looper.getMainLooper())

    private fun start() {
        Timber.tag(TAG).d("#### request() #### $mediaProjectionToken")

        clearResources()

        /**
         * Returns a new Rect describing the bounds of the area the window occupies.
         * Note that the size of the reported bounds can have different size than Display#getSize.
         * This method reports the window size including all system decorations,
         * while Display#getSize reports the area excluding navigation bars and display cutout areas.
         * Returns:
         * window bounds in pixels.
         */
        val screenInfo: ScreenInfo = ScreenInfoHolder.get()
        val dpi = context.resources.displayMetrics.density.toInt()
        Timber.tag("CaptureRepository").i("#### start width ${screenInfo.width}  height ${screenInfo.height} dpi $dpi ####")

        try {
            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionToken!!)

            mediaProjectionStopCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // 시스템(화면 잠금 등)이 프로젝션을 종료한 경우에만 호출된다.
                    // (앱 스스로 정리할 때는 clearResources() 가 콜백을 먼저 해제한다)
                    // 토큰은 일회용이라 더 이상 유효하지 않으므로 상태를 완전히 초기화해서
                    // 다음 request() 가 대기에 빠지지 않고 재동의 경로(NoMediaProjectionToken)로 빠지게 한다.
                    Timber.tag(TAG).w("#### MediaProjectionStopCallback onStop() ####")
                    clearResources()
                    state = State.Uninitialized
                    mediaProjectionToken = null
                    if (captureResponseFlow.value == null) { // 진행 중인 request() 가 있으면 깨워서 오류로 종료시킨다
                        captureResponseFlow.value = CaptureResponse.Error(NoMediaProjectionTokenException("MediaProjection stopped by system"))
                    }
                }
            }
            mediaProjection!!.registerCallback(mediaProjectionStopCallback!!, null)

            imageReader = ImageReader.newInstance(screenInfo.width, screenInfo.height, PixelFormat.RGBA_8888, 1)

            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "Screen Translator",
                screenInfo.width,
                screenInfo.height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null,
            )

            imageReader!!.setOnImageAvailableListener({ imageReader ->
                val capturedImage = imageReader.acquireLatestImage()
                try {
                    if (captureResponseFlow.value == null) {
                        if (capturedImage != null) {
                            val planes = capturedImage.planes
                            val buffer = planes[0].buffer

                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding: Int = rowStride - pixelStride * screenInfo.width

                            var capturedBitmap = createBitmap(screenInfo.width + rowPadding / pixelStride, screenInfo.height)
                            capturedBitmap.copyPixelsFromBuffer(buffer)
                            // rowPadding > 0 인 경우 rowPadding/pixelStride 만큼 이미지 의 width 가 오른쪽 으로 늘어 나므로, 늘어난 부분을 잘라준다.
                            capturedBitmap = Bitmap.createBitmap(capturedBitmap, 0, 0, screenInfo.width, screenInfo.height)
                            captureResponseFlow.value = CaptureResponse.Success(capturedBitmap)
                        } else {
                            captureResponseFlow.value = CaptureResponse.Error(CapturedImageInvalidException())
                        }
                    }
                } catch (t: Throwable) {
                    Timber.tag(TAG).e(t, "err t $mediaProjectionToken")
                    captureResponseFlow.value = CaptureResponse.Error(NoMediaProjectionTokenException(t.toString()))
                } finally {
                    capturedImage?.close()
                }
            }, handler)

            state = State.Ready
        }
        /*
            NullPointerException:

            SecurityException:
            Don't re-use the resultData to retrieve the same projection instance, and don't use a token that has timed out.
            Don't take multiple captures by invoking MediaProjection#createVirtualDisplay multiple times on the same instance.

            IllegalStateException:
            Cannot start already started MediaProjection
         */
        catch (e: Exception) {
            Timber.tag(TAG).e(e, "err e $mediaProjectionToken")
            captureResponseFlow.value = CaptureResponse.Error(NoMediaProjectionTokenException(e.toString()))
        }
    }

    fun restart() {
        clearResources()
        state = State.Uninitialized
    }

    /**
     * 살아있는 VirtualDisplay 에 surface 를 다시 붙여 캡처 프레임 생산을 재개한다.
     * MediaProjection/VirtualDisplay 객체를 그대로 유지하므로 토큰을 다시 요청하지 않는다.
     */
    private fun resumeFrames() {
        val reader = imageReader ?: return
        virtualDisplay?.setSurface(reader.surface)
    }

    /**
     * VirtualDisplay 에서 surface 를 분리해 프레임 생산(화면 미러링)을 멈춘다.
     * 캡처가 없는 유휴 구간에 가상 디스플레이가 60fps 로 계속 돌며 CPU 를 태우는 것을 막는다.
     * 객체 자체는 살려두므로 다음 캡처는 resumeFrames() 로 즉시 재개할 수 있다(토큰 재사용).
     */
    private fun pauseFrames() {
        virtualDisplay?.setSurface(null)
    }

    private fun removeAlphaChannel(original: Bitmap): Bitmap {
        val bitmapWithoutAlpha = createBitmap(original.width, original.height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmapWithoutAlpha)
        val paint = Paint()
        // paint.color = Color.WHITE // Set the default background color if needed
        canvas.drawBitmap(original, 0f, 0f, paint)
        return bitmapWithoutAlpha
    }

    /**
     * 캡처방지 된(DRM protected) 화면 인지 확인
     * https://stackoverflow.com/questions/42158782/mediaprojection-api-on-protected-drm-content
     * https://support.google.com/googleplay/android-developer/answer/14638385?hl=ko&ref_topic=13878452&sjid=17736376115784780377-AP#zippy=%2Cflag-secure%EA%B0%80-%EC%9D%98%EB%8F%84%ED%95%9C-%EB%8C%80%EB%A1%9C-%EC%9E%91%EB%8F%99%ED%95%98%EB%8A%94-%EB%B0%A9%EC%8B%9D%EC%9D%98-%EC%98%88%EB%8A%94-%EB%AC%B4%EC%97%87%EC%9D%B8%EA%B0%80%EC%9A%94%2Cflag-secure-%EB%B0%8F-require-secure-env-%ED%94%8C%EB%9E%98%EA%B7%B8%EB%A5%BC-%EC%82%AC%EC%9A%A9%ED%95%A0-%EC%88%98-%EC%9E%88%EB%8A%94-%EC%95%B1-%EC%9C%A0%ED%98%95%EC%9D%80-%EB%AC%B4%EC%97%87%EC%9D%B8%EA%B0%80%EC%9A%94%2C%EC%9D%B4%EB%9F%AC%ED%95%9C-%ED%94%8C%EB%9E%98%EA%B7%B8%EB%A5%BC-%EC%82%AC%EC%9A%A9%ED%95%98%EB%A9%B4-%EC%95%B1%EC%97%90-%EB%B6%80%EC%A0%95%EC%A0%81%EC%9D%B8-%EC%98%81%ED%96%A5%EC%9D%84-%EB%AF%B8%EC%B9%98%EB%82%98%EC%9A%94-%EA%B5%AC%ED%98%84%ED%95%98%EB%8A%94-%EB%8D%B0-%EC%8B%9C%EA%B0%84%EC%9D%B4-%EC%96%BC%EB%A7%88%EB%82%98-%EA%B1%B8%EB%A6%AC%EB%82%98%EC%9A%94
     */
    private fun isCapturePrevented(capturedBitmap: Bitmap): Pair<Boolean, Bitmap> {
        var isCapturePrevented = true
        val checker_w = capturedBitmap.width * 3 / 5
        val checker_h = capturedBitmap.height * 3 / 5
        val checkerScreenshotPixels = IntArray(checker_w * checker_h)
        val checkerBitmap = Bitmap.createBitmap( // 검증 대상 이미지 가운데 부분 오려냄
            capturedBitmap,
            capturedBitmap.width / 5,
            capturedBitmap.height / 5,
            checker_w,
            checker_h
        )
        Timber.tag(TAG).d("checkerBitmap width ${checkerBitmap.width} height ${checkerBitmap.height}")
        checkerBitmap.getPixels(checkerScreenshotPixels, 0, checker_w, 0, 0, checker_w, checker_h)
        val firstPixel = checkerScreenshotPixels[0]
        /*

         */
        // 30 픽셀씩 건너 뛰어 가면서 픽셀 값이 같은지 확인. 픽셀 값이 모두 같으면 캡처가 방지된 것으로 간주한다.
        var i = 0
        while (i < checkerScreenshotPixels.size) {
            if (firstPixel != checkerScreenshotPixels[i]) {
                isCapturePrevented = false
                break
            }
            i += 30
        }

        return Pair(isCapturePrevented, checkerBitmap)
    }

    private fun clearResources() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        mediaProjectionStopCallback?.let {
            Handler(Looper.getMainLooper()).post {
                mediaProjection?.unregisterCallback(it)
            }
            mediaProjectionStopCallback = null
        }
        mediaProjection?.stop()
        mediaProjection = null
    }

    suspend fun request(): CaptureResponse {
        Timber.tag(TAG).i("#### request() ####")
        captureResponseFlow.value = null

        Timber.tag(TAG).i("State $state")
        if (state == State.Uninitialized) {
            start() // 최초 캡처: 토큰으로 MediaProjection/VirtualDisplay 를 생성 (surface 부착 상태)
        } else {
            resumeFrames() // 이후 캡처: 살아있는 디스플레이의 프레임 생산만 재개 (토큰 재요청 없음)
        }

        // 프레임을 무한정 기다리지 않는다. 프로젝션이 죽어(onStop 미발생 기기 등) 프레임이 오지 않으면
        // 상태를 초기화하고 재동의 경로(NoMediaProjectionToken)로 유도한다.
        var captureResponse: CaptureResponse =
            withTimeoutOrNull(CAPTURE_FRAME_TIMEOUT_MS) {
                captureResponseFlow.filterNotNull().first()
            } ?: run {
                Timber.tag(TAG).w("no capture frame within ${CAPTURE_FRAME_TIMEOUT_MS}ms — projection likely dead")
                state = State.Uninitialized
                val isScreenOn = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                if (!isScreenOn) mediaProjectionToken = null
                clearResources()
                CaptureResponse.Error(NoMediaProjectionTokenException("no capture frame within timeout"))
            }

        // 캡처가 끝나면 프레임 생산을 멈춰 유휴 시 CPU 낭비를 막는다. (객체는 유지 → 토큰 재사용)
        pauseFrames()

        if (captureResponse is CaptureResponse.Success) {
            captureResponse = CaptureResponse.Success(removeAlphaChannel(captureResponse.bitmap))
            Timber.tag(TAG).d("removeAlphaChannel capturedBitmap.allocationByteCount ${captureResponse.bitmap.allocationByteCount}")

            // 캡처방지된(DRM protected) 화면인지 확인
//            val (isCapturePrevented, checkerBitmap) = isCapturePrevented(capturedBitmap)
//            captureWorkFlow.value =
//                if (isCapturePrevented) {
//                    Response.Error(CapturePreventedException(checkerBitmap))
//                } else {
//                    Response.Success(capturedBitmap)
//                }
        }
        return captureResponse
    }

    override fun onZeroReferences() {
        Timber.tag(TAG).d("====================== mediaProjectionToken = null ============================ ")
        clearResources()
        mediaProjectionToken = null
    }
}

