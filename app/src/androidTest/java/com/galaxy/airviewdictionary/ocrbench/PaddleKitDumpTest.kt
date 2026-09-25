package com.galaxy.airviewdictionary.ocrbench

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKitSelector
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * PP-OCRv5 엔진(`PaddleOcrVisionKit`)으로 표본 캡처를 끝까지 읽어 줄마다 상자·글·신뢰도와 걸린 시간을 떨어뜨린다.
 * 정확도 채점은 오프라인에서 한다(`tools/paddle/score.py`). 표본은 `paddle_<언어>_<이름>.png` 로 에셋에 잠깐 놓는다.
 * 출력은 앱 외부 미디어의 `paddle_eval/`.
 */
class PaddleKitDumpTest {

    @Test
    fun dumpPaddleReads() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val selector = VisionKitSelector(instrumentation.targetContext) // 모델은 앱 쪽(팩 또는 디버그 에셋)에 있다
            val outDir = File(instrumentation.targetContext.externalMediaDirs.first(), "paddle_eval").apply { mkdirs() }
            val names = instrumentation.context.assets.list("")!!.filter { it.startsWith("paddle_") && it.endsWith(".png") }.sorted()
            for (asset in names) {
                val lang = asset.removePrefix("paddle_").substringBefore('_')
                val kit = selector.candidatesFor(lang).single()
                val bytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
                val screen = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                kit.detect(screen) // 첫 회는 세션 적재가 섞이므로 한 번 버린다
                val t0 = System.nanoTime()
                val detected = kit.detect(screen)
                val t1 = System.nanoTime()
                val read = kit.recognize(screen, detected.lines)
                val t2 = System.nanoTime()
                val lines = JSONArray()
                for (line in read) {
                    val b = line.boundingBox!!
                    lines.put(JSONObject().put("b", JSONArray(listOf(b.left, b.top, b.right, b.bottom))).put("t", line.text)
                        .put("c", line.confidence?.toDouble() ?: JSONObject.NULL).put("words", line.words?.size ?: 0))
                }
                File(outDir, asset.removeSuffix(".png") + ".json").writeText(
                    JSONObject().put("kit", kit.name).put("lang", lang).put("w", screen.width).put("h", screen.height)
                        .put("detectMs", (t1 - t0) / 1_000_000).put("recognizeMs", (t2 - t1) / 1_000_000).put("lines", lines).toString()
                )
                android.util.Log.i("PaddleKitDump", "$asset ${kit.name} 줄 ${read.size} 검출 ${(t1 - t0) / 1_000_000}ms 인식 ${(t2 - t1) / 1_000_000}ms")
            }
        }
    }
}
