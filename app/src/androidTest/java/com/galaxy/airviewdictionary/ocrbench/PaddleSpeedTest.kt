package com.galaxy.airviewdictionary.ocrbench

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.nio.FloatBuffer

/**
 * PP-OCRv5 추론 설정별 속도(스레드 수, XNNPACK). 모델은 앱 쪽 디버그 에셋에서 읽는다. 입력은 실측에서 흔한 크기 —
 * 검출 544x960, 인식 한 줄 48x1100.
 */
class PaddleSpeedTest {

    private fun median(values: List<Long>) = values.sorted()[values.size / 2]

    private fun time(env: OrtEnvironment, session: OrtSession, shape: LongArray, runs: Int = 7): Long {
        val size = shape.fold(1L) { a, b -> a * b }.toInt()
        val buffer = FloatBuffer.allocate(size).apply { repeat(size) { put(((it % 97) - 48) / 48f) }; rewind() }
        val times = mutableListOf<Long>()
        repeat(runs + 1) { i ->
            buffer.rewind()
            OnnxTensor.createTensor(env, buffer, shape).use { x ->
                val t = System.nanoTime()
                session.run(mapOf(session.inputNames.first() to x)).use { }
                if (i > 0) times.add((System.nanoTime() - t) / 1_000_000)
            }
        }
        return median(times)
    }

    /** 한 문단(8줄)을 읽는 시간 — 차례로(스레드 4) 대 동시에 여러 줄(세션 스레드 k, 동시 n줄). */
    @Test
    fun compareParallelLines() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val rec = target.assets.open("paddle/eslav_rec.onnx").use { it.readBytes() }
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, 3, 48, 1100)
        val size = shape.fold(1L) { a, b -> a * b }.toInt()
        fun input() = FloatBuffer.allocate(size).apply { repeat(size) { put(((it % 97) - 48) / 48f) }; rewind() }
        for ((threads, parallel) in listOf(4 to 1, 2 to 2, 2 to 4, 1 to 4, 1 to 8, 4 to 2)) {
            env.createSession(rec, OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }).use { session ->
                fun paragraph(): Long {
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(parallel)
                    val t = System.nanoTime()
                    (1..8).map { pool.submit { OnnxTensor.createTensor(env, input(), shape).use { x -> session.run(mapOf(session.inputNames.first() to x)).use { } } } }.forEach { it.get() }
                    pool.shutdown()
                    return (System.nanoTime() - t) / 1_000_000
                }
                paragraph()
                val times = (1..3).map { paragraph() }
                android.util.Log.i("PaddleSpeed", "세션 스레드 $threads, 동시 $parallel 줄: 8줄 ${median(times)}ms")
            }
        }
    }

    /**
     * 인식기 fp32 대 int8(행렬곱만 양자화, `tools/paddle` §14) — 프로덕션 설정(세션 스레드 2, 줄 넷 동시)으로 한 문단(8줄) 시간.
     * int8 모델은 앱 외부 미디어의 `int8/<문자권>_rec.onnx` 에 adb 로 올려 둔다.
     */
    @Test
    fun compareInt8() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val env = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1, 3, 48, 1100)
        val size = shape.fold(1L) { a, b -> a * b }.toInt()
        fun input() = FloatBuffer.allocate(size).apply { repeat(size) { put(((it % 97) - 48) / 48f) }; rewind() }
        for (kit in listOf("arabic", "eslav", "th")) {
            val models = listOf(
                "fp32" to target.assets.open("paddle/${kit}_rec.onnx").use { it.readBytes() },
                "int8" to java.io.File(target.externalMediaDirs.first(), "int8/${kit}_rec.onnx").readBytes(),
            )
            for ((label, bytes) in models) {
                env.createSession(bytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }).use { session ->
                    fun paragraph(): Long {
                        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
                        val t = System.nanoTime()
                        (1..8).map { pool.submit { OnnxTensor.createTensor(env, input(), shape).use { x -> session.run(mapOf(session.inputNames.first() to x)).use { } } } }.forEach { it.get() }
                        pool.shutdown()
                        return (System.nanoTime() - t) / 1_000_000
                    }
                    paragraph()
                    val times = (1..5).map { paragraph() }
                    android.util.Log.i("PaddleSpeed", "$kit $label: 8줄 ${median(times)}ms")
                }
            }
        }
    }

    @Test
    fun compareOptions() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        fun model(name: String) = target.assets.open("paddle/$name").use { it.readBytes() }
        val env = OrtEnvironment.getEnvironment()
        val det = model("det.onnx")
        val rec = model("eslav_rec.onnx")
        val variants: List<Pair<String, () -> OrtSession.SessionOptions>> = listOf(
            "cpu 4" to { OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) } },
            "cpu 6" to { OrtSession.SessionOptions().apply { setIntraOpNumThreads(6) } },
            "cpu 8" to { OrtSession.SessionOptions().apply { setIntraOpNumThreads(8) } },
            "cpu 2" to { OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) } },
            "xnnpack 4" to { OrtSession.SessionOptions().apply { setIntraOpNumThreads(1); addXnnpack(mapOf("intra_op_num_threads" to "4")) } },
            "xnnpack 6" to { OrtSession.SessionOptions().apply { setIntraOpNumThreads(1); addXnnpack(mapOf("intra_op_num_threads" to "6")) } },
        )
        for ((label, options) in variants) {
            val d = runCatching { env.createSession(det, options()).use { time(env, it, longArrayOf(1, 3, 960, 544)) } }.getOrElse { -1 }
            val r = runCatching { env.createSession(rec, options()).use { time(env, it, longArrayOf(1, 3, 48, 1100)) } }.getOrElse { -1 }
            android.util.Log.i("PaddleSpeed", "$label: 검출 ${d}ms, 인식(한 줄) ${r}ms")
        }
    }
}
