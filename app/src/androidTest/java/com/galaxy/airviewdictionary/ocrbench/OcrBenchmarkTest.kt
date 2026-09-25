package com.galaxy.airviewdictionary.ocrbench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import timber.log.Timber
import java.nio.FloatBuffer

/**
 * PP-OCRv5 추론 속도를 실기기에서 잰다. 측정용이며 출시 APK 에는 들어가지 않는다.
 *
 * 재려는 것은 하나다 — 폰에서 화면 한 장을 읽는 데 몇 초가 걸리는가.
 * 지금 ML Kit 경로는 수백 ms 라, 여기서 1초를 크게 넘으면 정확도와 무관하게
 * 기존 경로를 대체할 수 없고 설계 자체가 달라진다.
 *
 * 후처리(DB 이진화·윤곽 추출·CTC 디코딩)는 재지 않는다. 비용의 대부분은 신경망
 * 순전파이고, 그 부분만으로 "쓸 수 있는가"의 답이 갈린다.
 */
class OcrBenchmarkTest {

    // 에셋은 테스트 APK 안에 있다. targetContext 는 앱 APK 라 여기서 찾으면 없다.
    private val context get() = InstrumentationRegistry.getInstrumentation().context

    /** 검출 입력 정규화 상수. PP-OCR 기본값. */
    private val detMean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val detStd = floatArrayOf(0.229f, 0.224f, 0.225f)

    private fun readAsset(name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }

    /** 비트맵을 NCHW float 텐서로 편다. 정규화 방식은 검출·인식이 다르다. */
    private fun toTensor(bitmap: Bitmap, normalize: (Int, Float) -> Float): FloatBuffer {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buffer = FloatBuffer.allocate(3 * w * h)
        for (c in 0 until 3) {
            val shift = 16 - c * 8 // R, G, B
            for (i in pixels.indices) {
                val v = ((pixels[i] shr shift) and 0xFF) / 255f
                buffer.put(normalize(c, v))
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun timeRuns(
        session: OrtSession,
        env: OrtEnvironment,
        buffer: FloatBuffer,
        shape: LongArray,
        runs: Int = 5,
    ): Long {
        val timings = mutableListOf<Long>()
        repeat(runs + 1) { i ->
            buffer.rewind()
            OnnxTensor.createTensor(env, buffer, shape).use { tensor ->
                val start = System.nanoTime()
                session.run(mapOf(session.inputNames.first() to tensor)).use { }
                val ms = (System.nanoTime() - start) / 1_000_000
                if (i > 0) timings.add(ms) // 첫 회는 워밍업이라 버린다
            }
        }
        return median(timings)
    }

    @Test
    fun measureDetectionAndRecognition() {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            // 폰에서 실제로 쓸 설정. 코어를 전부 쓰면 발열·배터리가 문제가 되므로 4개로 둔다.
            setIntraOpNumThreads(4)
        }

        val screen = readAsset("devinv_ar.png").let {
            BitmapFactory.decodeByteArray(it, 0, it.size)
        }
        log("화면 캡처 ${screen.width}x${screen.height}")

        env.createSession(readAsset("PP-OCRv5_mobile_det.onnx"), options).use { det ->
            // 검출 입력 크기별 비용. 전체 해상도가 상자를 잘게 잡아 정확했지만 그만큼 비싸다.
            for (longSide in intArrayOf(768, 960, 1280, 2560)) {
                val scale = longSide.toFloat() / maxOf(screen.width, screen.height)
                // DB 검출기는 입력 변이 32 의 배수여야 한다.
                val w = ((screen.width * scale).toInt() / 32) * 32
                val h = ((screen.height * scale).toInt() / 32) * 32
                if (w <= 0 || h <= 0) continue
                val scaled = Bitmap.createScaledBitmap(screen, w, h, true)
                val buffer = toTensor(scaled) { c, v -> (v - detMean[c]) / detStd[c] }
                val ms = timeRuns(det, env, buffer, longArrayOf(1, 3, h.toLong(), w.toLong()))
                log("검출 ${w}x${h} : ${ms}ms")
                scaled.recycle()
            }
        }

        env.createSession(readAsset("arabic_PP-OCRv5_mobile_rec.onnx"), options).use { rec ->
            // 인식기는 크롭을 높이 48 로 맞춘다. 폭은 상자 비율을 따른다.
            // 실측에서 화면 한 장당 상자가 28개, 비율은 8:1 언저리였다.
            // 28개는 화면 전체를 읽을 때다. 실제로는 포인터 주변 크롭만 읽으면 되므로
            // 한 대상에 해당하는 몇 개가 현실적인 수다.
            for (boxes in intArrayOf(1, 4, 8, 28)) {
            for (width in intArrayOf(384)) {
                val buffer = FloatBuffer.allocate(boxes * 3 * 48 * width)
                repeat(boxes * 3 * 48 * width) { buffer.put(0.5f) }
                buffer.rewind()
                val ms = timeRuns(
                    rec, env, buffer,
                    longArrayOf(boxes.toLong(), 3, 48, width.toLong()),
                )
                log("인식 ${boxes}개 x 48x$width : ${ms}ms")
            }
            }
        }
    }

    private fun log(message: String) {
        Timber.tag("OcrBenchmark").i(message)
        println("OcrBenchmark: $message")
    }
}
