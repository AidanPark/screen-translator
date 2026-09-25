package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKitSelector
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleKits
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleModelFiles
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.ui.screen.overlay.selection.createOverlaidBitmap
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * auto 에 PP-OCRv5 를 넣는 규칙을 비교하려고(`.docs/vision-engine-design.md` §13) 한 면에서 두 엔진 쪽을 모두 덤프한다.
 *
 * 면마다 셋 — 화면 전체와 영역 선택 잘라내기 둘([AutoPickDumpTest] 와 같은 난수라 같은 영역이다).
 *  - ML Kit: 후보 다섯의 줄(신뢰도·글·상자)과 글 전체의 감지 언어
 *  - PP-OCRv5: 검출 줄 수, 가장 넓은 8줄을 세 모델(아랍·동슬라브·태국)이 각각 읽은 줄 글·줄 신뢰도, 넓은 4·6·8줄 글의 감지 언어
 * 규칙 비교는 오프라인에서 한다. 출력은 앱 외부 미디어의 `autopick2/`.
 */
class AutoPickPaddleDumpTest {

    @Test
    fun dumpBothEngines() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.context
            val mlkit = VisionKitSelector().candidatesFor("auto")
            val paddle = PaddleKits(PaddleModelFiles(instrumentation.targetContext))
            val models = listOf("ar", "ru", "th").map { paddle.kitFor(it) ?: error("PP-OCRv5 모델이 없다") }
            val repository = VisionRepository()
            val outDir = File(instrumentation.targetContext.externalMediaDirs.first(), "autopick2").apply { mkdirs() }
            val names = context.assets.list("")!!.filter { it.startsWith("real_") && it.endsWith(".png") }
                .map { it.removePrefix("real_").removeSuffix(".png") }.sorted()

            for (name in names) {
                val bytes = context.assets.open("real_$name.png").use { it.readBytes() }
                val screen = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val random = Random(name.hashCode())
                val variants = JSONArray()
                for (kind in listOf("full", "crop1", "crop2")) {
                    val rect = if (kind == "full") null else cropRect(screen, random)
                    val image = rect?.let { createOverlaidBitmap(screen, it) } ?: screen

                    val mlkitOut = JSONArray()
                    for (kit in mlkit) {
                        val lines = JSONArray()
                        val text = runCatching { kit.detect(image) }.getOrNull()?.let { ocr ->
                            for (line in ocr.lines) {
                                val b = line.boundingBox
                                lines.put(JSONObject().put("c", line.confidence?.toDouble() ?: JSONObject.NULL).put("t", line.text)
                                    .put("b", if (b == null) JSONObject.NULL else JSONArray(listOf(b.left, b.top, b.right, b.bottom))))
                            }
                            ocr.text
                        }
                        mlkitOut.put(JSONObject().put("kit", kit.name).put("text", text ?: JSONObject.NULL).put("lines", lines)
                            .put("lang", if (text.isNullOrBlank()) "und" else repository.identifyLanguage(text)))
                    }

                    val t0 = System.nanoTime()
                    val detected = models[0].detect(image).lines
                    val detectMs = (System.nanoTime() - t0) / 1_000_000
                    // 가장 넓은 줄이 글을 가장 많이 담는다 — 표본으로 읽을 줄
                    val sample: List<OcrLine> = detected.sortedByDescending { it.boundingBox!!.width() }.take(8)
                    val paddleOut = JSONArray()
                    for (kit in models) {
                        val t1 = System.nanoTime()
                        val read = kit.recognize(image, sample)
                        val ms = (System.nanoTime() - t1) / 1_000_000
                        val lines = JSONArray()
                        for (line in read) lines.put(JSONObject().put("t", line.text).put("c", line.confidence?.toDouble() ?: 0.0))
                        val langs = JSONObject()
                        for (k in listOf(4, 6, 8)) {
                            val text = read.take(k).joinToString("\n") { it.text }
                            langs.put("$k", if (text.isBlank()) "und" else repository.identifyLanguage(text))
                        }
                        paddleOut.put(JSONObject().put("kit", kit.name).put("lines", lines).put("lang", langs).put("ms", ms))
                    }
                    variants.put(JSONObject().put("kind", kind)
                        .put("rect", rect?.let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
                        .put("mlkit", mlkitOut)
                        .put("paddle", JSONObject().put("detected", detected.size).put("detectMs", detectMs).put("models", paddleOut)))
                    if (image !== screen) image.recycle()
                }
                File(outDir, "$name.json").writeText(JSONObject().put("name", name).put("variants", variants).toString())
                screen.recycle()
                android.util.Log.i("AutoPick2", "$name 끝")
            }
        }
    }

    /**
     * 프로덕션 auto(`VisionRepository.detect`)가 실제로 고른 엔진을 같은 세 이미지에서 떨어뜨린다 — 오프라인 채점기(`confirm13.py`)의
     * 계산과 구현이 같은지 대조한다. 출력은 앱 외부 미디어의 `autopick_prod2/`.
     */
    @Test
    fun dumpProductionAutoChoice() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val repository = VisionRepository(instrumentation.targetContext)
            val outDir = File(instrumentation.targetContext.externalMediaDirs.first(), "autopick_prod2").apply { mkdirs() }
            val names = instrumentation.context.assets.list("")!!.filter { it.startsWith("real_") && it.endsWith(".png") }
                .map { it.removePrefix("real_").removeSuffix(".png") }.sorted()
            for (name in names) {
                val bytes = instrumentation.context.assets.open("real_$name.png").use { it.readBytes() }
                val screen = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val random = Random(name.hashCode())
                val variants = JSONArray()
                for (kind in listOf("full", "crop1", "crop2")) {
                    val rect = if (kind == "full") null else cropRect(screen, random)
                    val image = rect?.let { createOverlaidBitmap(screen, it) } ?: screen
                    val chosen = runCatching { repository.detect(image, "auto") }.getOrNull()
                    variants.put(JSONObject().put("kind", kind).put("kit", chosen?.kit?.name ?: JSONObject.NULL)
                        .put("language", chosen?.identifiedLanguageCode ?: JSONObject.NULL))
                    if (image !== screen) image.recycle()
                }
                File(outDir, "$name.json").writeText(JSONObject().put("name", name).put("variants", variants).toString())
                screen.recycle()
            }
        }
    }

    /** [AutoPickDumpTest] 와 같은 영역 — 같은 표본 이름이면 같은 난수열이다. */
    private fun cropRect(screen: Bitmap, random: Random): Rect {
        val w = (screen.width * random.nextDouble(0.40, 0.95)).toInt()
        val h = (screen.height * random.nextDouble(0.12, 0.35)).toInt()
        val left = random.nextInt(0, screen.width - w + 1)
        val top = random.nextInt(200, maxOf(201, screen.height - h - 150))
        return Rect(left, top, left + w, top + h)
    }
}
