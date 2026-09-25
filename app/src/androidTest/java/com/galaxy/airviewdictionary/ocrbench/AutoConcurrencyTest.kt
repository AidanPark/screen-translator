package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleKits
import com.galaxy.airviewdictionary.data.local.vision.kit.paddle.PaddleModelFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * auto 에서 PP-OCRv5 를 ML Kit 과 **동시에** 돌려도 되는가(`.docs/vision-engine-design.md` §13 실행 방식).
 * 같은 화면에서 셋을 잰다 — ML Kit auto 만, PP-OCRv5 표본 읽기(검출 + 넓은 6줄 × 세 모델)만, 둘을 동시에.
 * 표본은 `real_<이름>.png` 로 에셋에 잠깐 놓는다.
 */
class AutoConcurrencyTest {

    @Test
    fun measure() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val repository = VisionRepository() // ML Kit 후보만 — auto 의 지금 모습
            val paddle = PaddleKits(PaddleModelFiles(instrumentation.targetContext))
            val models = listOf("ar", "ru", "th").map { paddle.kitFor(it)!! }
            suspend fun paddleSample(image: Bitmap) {
                val lines = models[0].detect(image).lines.sortedByDescending { it.boundingBox!!.width() }.take(6)
                models.map { m -> async(Dispatchers.Default) { m.recognize(image, lines) } }.awaitAll()
            }
            fun ms(t: Long) = (System.nanoTime() - t) / 1_000_000
            val names = instrumentation.context.assets.list("")!!.filter { it.startsWith("real_") && it.endsWith(".png") }.sorted()
            for (asset in names) {
                val bytes = instrumentation.context.assets.open(asset).use { it.readBytes() }
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                repository.read(image, "auto"); paddleSample(image) // 적재·워밍업
                val alone = mutableListOf<Long>(); val paddleAlone = mutableListOf<Long>(); val together = mutableListOf<Long>(); val paddleTogether = mutableListOf<Long>()
                repeat(3) {
                    var t = System.nanoTime(); repository.read(image, "auto"); alone.add(ms(t))
                    t = System.nanoTime(); paddleSample(image); paddleAlone.add(ms(t))
                    t = System.nanoTime()
                    val p = async(Dispatchers.Default) { val s = System.nanoTime(); paddleSample(image); ms(s) }
                    repository.read(image, "auto"); together.add(ms(t)); paddleTogether.add(p.await())
                }
                fun med(v: List<Long>) = v.sorted()[v.size / 2]
                android.util.Log.i(
                    "AutoConcurrency",
                    "$asset ML Kit auto 혼자 ${med(alone)}ms, 동시 ${med(together)}ms | PP-OCRv5 표본 혼자 ${med(paddleAlone)}ms, 동시 ${med(paddleTogether)}ms"
                )
            }
        }
    }
}
