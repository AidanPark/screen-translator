package com.galaxy.airviewdictionary.ocrbench

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 프로덕션 길 그대로(`.docs/vision-engine-design.md` §10·§12): 소스 언어를 지정하면 `request()` 는 PP-OCRv5 로 **검출만** 하고
 * 줄 상자로 문단을 묶는다. 문단마다 `readParagraph()` 로 읽어 글과 걸린 시간을 떨어뜨린다. 채점은 `tools/paddle/score.py --paragraphs`.
 * 표본은 `paddle_<언어>_<이름>.png` 로 에셋에 잠깐 놓는다. 출력은 앱 외부 미디어의 `paddle_path/`.
 */
class PaddlePathDumpTest {

    @Test
    fun dumpLazyPath() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val repository = VisionRepository(instrumentation.targetContext)
            val outDir = File(instrumentation.targetContext.externalMediaDirs.first(), "paddle_path").apply { mkdirs() }
            val names = instrumentation.context.assets.list("")!!.filter { it.startsWith("paddle_") && it.endsWith(".png") }.sorted()
            for (asset in names) {
                val lang = asset.removePrefix("paddle_").substringBefore('_')
                val bytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
                val screen = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                repository.request(screen, lang) // 세션 적재를 뺀다
                val t0 = System.nanoTime()
                val tx = (repository.request(screen, lang) as VisionResponse.Success).result
                val requestMs = (System.nanoTime() - t0) / 1_000_000
                assertNotNull("$asset: 검출만 된 화면이어야 한다", tx.unread)
                val paragraphs = JSONArray()
                for (p in tx.paragraphs) {
                    val t = System.nanoTime()
                    val read = repository.readParagraph(tx, p)
                    val ms = (System.nanoTime() - t) / 1_000_000
                    val b = p.boundingBox
                    paragraphs.put(JSONObject().put("b", JSONArray(listOf(b.left, b.top, b.right, b.bottom))).put("lines", p.lines.size)
                        .put("t", read?.representation ?: "").put("words", read?.lines?.sumOf { it.words.size } ?: 0)
                        .put("sentences", read?.sentences?.size ?: 0).put("ms", ms))
                }
                File(outDir, asset.removeSuffix(".png") + ".json").writeText(
                    JSONObject().put("lang", lang).put("requestMs", requestMs).put("paragraphs", paragraphs).toString()
                )
                android.util.Log.i("PaddlePath", "$asset 문단 ${tx.paragraphs.size} request ${requestMs}ms")
            }
        }
    }
}
