package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import ai.onnxruntime.OnnxTensor
import android.graphics.Bitmap
import android.graphics.Rect
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv5 모바일 검출기(DB). 화면에서 글 줄의 상자를 찾는다 — 글은 읽지 않는다.
 *
 * 입력은 긴 변 960 으로 줄인다(실측 107ms; 960·1440·1920 이 같은 결과). 후처리는 PaddleOCR 의 DB 후처리를 축 정렬 상자로 옮긴 것이다 —
 * 화면 글은 기울지 않으므로 회전 상자가 필요 없고, 인식은 어차피 축 정렬로 잘라 읽는다(실측: 원근 변환 크롭이 오히려 실패의 원인이었다).
 * 상자 넓히기는 unclip 0.6(기본 1.5 에서 조임. 실측 최적).
 */
internal class PaddleDetector(private val sessions: PaddleSessions) {

    fun isReady(): Boolean = sessions.has(MODEL)

    /** 세션을 만들다 실패해 이 프로세스에서 쓸 수 없는가. ONNX Runtime 을 건드리지 않는다. */
    fun broken(): Boolean = sessions.broken(MODEL)

    /** 세션을 만들어 둔다(이미 있으면 그대로). 만들 수 없으면 false. */
    fun prepare(): Boolean = sessions.session(MODEL, THREADS) != null

    /** 화면 좌표의 줄 상자. */
    fun detect(screen: Bitmap): List<Rect> {
        val session = sessions.session(MODEL, THREADS) ?: throw IllegalStateException("PP-OCRv5 검출기 세션이 없다")
        val scale = min(1f, LONG_SIDE.toFloat() / max(screen.width, screen.height))
        // DB 검출기는 입력 변이 32 의 배수여야 한다
        val w = max(32, (screen.width * scale / 32).roundToInt() * 32)
        val h = max(32, (screen.height * scale / 32).roundToInt() * 32)
        val started = System.nanoTime()
        val input = Bitmap.createScaledBitmap(screen, w, h, true)
        val invert = input.isDark()
        val tensor = input.toBgrTensor(invert) { c, v -> (v - MEAN[c]) / STD[c] }
        if (input !== screen) input.recycle()

        val prepared = System.nanoTime()
        val prob = OnnxTensor.createTensor(sessions.env, tensor, longArrayOf(1, 3, h.toLong(), w.toLong())).use { x ->
            session.run(mapOf(session.inputNames.first() to x)).use { out ->
                val buffer = (out[0] as OnnxTensor).floatBuffer
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        }
        val inferred = System.nanoTime()
        val sx = screen.width.toFloat() / w
        val sy = screen.height.toFloat() / h
        val found = boxes(prob, w, h)
        Timber.tag("PaddleDetector").d(
            "${w}x$h 준비 ${(prepared - started) / 1_000_000}ms 추론 ${(inferred - prepared) / 1_000_000}ms " +
                "후처리 ${(System.nanoTime() - inferred) / 1_000_000}ms 상자 ${found.size}"
        )
        return found.map { r ->
            Rect(
                (r.left * sx).roundToInt().coerceIn(0, screen.width),
                (r.top * sy).roundToInt().coerceIn(0, screen.height),
                (r.right * sx).roundToInt().coerceIn(0, screen.width),
                (r.bottom * sy).roundToInt().coerceIn(0, screen.height),
            )
        }.filter { it.width() > 0 && it.height() > 0 }
    }

    /** 확률 지도 → 검출 좌표의 상자. 이진화 → 8방향 연결 요소 → 점수 → 넓히기. */
    private fun boxes(prob: FloatArray, w: Int, h: Int): List<Rect> {
        // 상자 점수(상자 안 확률 평균)를 상수 시간에 내려고 적분 영상을 만든다
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += prob[y * w + x]
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row
            }
        }
        fun mean(l: Int, t: Int, r: Int, b: Int): Double {
            val s = integral[b * (w + 1) + r] - integral[t * (w + 1) + r] - integral[b * (w + 1) + l] + integral[t * (w + 1) + l]
            return s / ((r - l) * (b - t))
        }

        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val out = mutableListOf<Rect>()
        for (start in 0 until w * h) {
            if (seen[start] || prob[start] <= THRESH) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            while (head < tail) {
                val p = queue[head++]
                val px = p % w
                val py = p / w
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx
                    val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val n = ny * w + nx
                    if (!seen[n] && prob[n] > THRESH) {
                        seen[n] = true
                        queue[tail++] = n
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (min(bw, bh) < MIN_SIZE) continue
            if (mean(minX, minY, maxX + 1, maxY + 1) < BOX_THRESH) continue
            // 줄어든 글자 핵을 원래 글자 크기로 넓힌다: 거리 = 넓이 × 비율 / 둘레
            val d = bw.toDouble() * bh * UNCLIP / (2.0 * (bw + bh))
            val l = (minX - d).roundToInt()
            val t = (minY - d).roundToInt()
            val r = (maxX + 1 + d).roundToInt()
            val b = (maxY + 1 + d).roundToInt()
            if (min(r - l, b - t) < MIN_SIZE + 2) continue
            out.add(Rect(l, t, r, b))
        }
        return out
    }

    companion object {
        const val MODEL = "det.onnx"
        private const val THREADS = 4
        private const val LONG_SIDE = 960
        private const val THRESH = 0.3f
        private const val BOX_THRESH = 0.6
        private const val UNCLIP = 0.6
        private const val MIN_SIZE = 3
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
