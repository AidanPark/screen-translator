package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * auto 에 PP-OCRv5 를 넣은 규칙(`.docs/vision-engine-design.md` §13.4)을 그린 화면으로 시험한다. 모델은 앱 쪽(디버그 에셋)에 있다.
 *  - ML Kit 문자(영어·힌디어)는 ML Kit 이 그대로 읽는다 — 다 읽힌 화면
 *  - ML Kit 에 없는 문자(러시아어·아랍어)는 PP-OCRv5 가 이기고, 그 언어를 지정한 것처럼 검출만 된 화면이 되며 가리킨 문단만 읽는다.
 *    auto 가 표본으로 넓은 4줄을 이미 읽으므로, 줄이 4개뿐인 화면은 다 읽힌 화면이 된다 — 그래서 문단을 넉넉히 그린다
 */
class AutoPaddleTest {

    private val repository = VisionRepository(InstrumentationRegistry.getInstrumentation().targetContext)

    private fun screen(paragraphs: List<String>): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 2000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 44f }
        var top = 120
        for (text in paragraphs) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 960).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            canvas.save(); canvas.translate(60f, top.toFloat()); layout.draw(canvas); canvas.restore()
            top += layout.height + 160
        }
        return bitmap
    }

    private fun request(bitmap: Bitmap) = runBlocking {
        (repository.request(bitmap, "auto") as VisionResponse.Success).result
    }

    @Test
    fun englishStaysWithMlKit() {
        val tx = request(screen(listOf(
            "Screen translation reads the words under your finger. It finds the lines first and groups them into paragraphs.",
            "The quick brown fox jumps over the lazy dog while the farmer watches from the porch and wonders about the weather.",
        )))
        assertNull("ML Kit 이 다 읽는다", tx.unread)
        assertEquals("en", tx.detectedLanguageCode)
    }

    @Test
    fun hindiStaysWithMlKit() {
        val tx = request(screen(listOf(
            "भारत दक्षिण एशिया में स्थित एक विशाल देश है। यह जनसंख्या के हिसाब से दुनिया का सबसे बड़ा देश है और यहाँ अनेक भाषाएँ बोली जाती हैं।",
            "हिमालय पर्वत भारत की उत्तरी सीमा पर फैला हुआ है। गंगा नदी यहीं से निकलती है और मैदानों को सींचती हुई बंगाल की खाड़ी में मिलती है।",
        )))
        assertNull("데바나가리는 ML Kit 이 읽는다", tx.unread)
        assertEquals("hi", tx.detectedLanguageCode)
    }

    @Test
    fun russianGoesToPaddle() {
        val tx = request(screen(listOf(
            "Москва — столица России, крупнейший по численности населения город страны и её экономический и культурный центр.",
            "Река Волга является самой длинной рекой Европы. Она берёт начало на Валдайской возвышенности и впадает в Каспийское море.",
        )))
        assertNotNull("PP-OCRv5 가 이기면 검출만 된 화면이다", tx.unread)
        assertEquals("ru", tx.detectedLanguageCode)
        val read = runBlocking { tx.paragraphs.mapNotNull { repository.readParagraph(tx, it)?.representation } }.joinToString(" ")
        assertTrue("읽은 글에 러시아어가 있어야 한다: $read", read.contains("Москва") || read.contains("Волга"))
    }

    @Test
    fun arabicGoesToPaddle() {
        val tx = request(screen(listOf(
            "القاهرة هي عاصمة جمهورية مصر العربية وأكبر مدنها، وتقع على ضفاف نهر النيل في شمال البلاد، وهي من أكبر المدن في أفريقيا والشرق الأوسط.",
            "يعد نهر النيل أطول أنهار العالم، ويمر عبر عدة دول في أفريقيا قبل أن يصب في البحر الأبيض المتوسط، وقد قامت على ضفافه حضارات قديمة عظيمة.",
            "تشتهر مصر بالأهرامات وأبي الهول، ويزورها ملايين السياح كل عام لمشاهدة آثارها التاريخية والاستمتاع بشواطئها على البحر الأحمر.",
        )))
        assertNotNull("PP-OCRv5 가 이기면 검출만 된 화면이다", tx.unread)
        assertTrue("아랍 문자권 언어여야 한다: ${tx.detectedLanguageCode}", tx.detectedLanguageCode in setOf("ar", "fa", "ur"))
        val read = runBlocking { tx.paragraphs.mapNotNull { repository.readParagraph(tx, it)?.representation } }.joinToString(" ")
        assertTrue("읽은 글에 아랍어가 있어야 한다: $read", read.contains("النيل") || read.contains("القاهرة"))
    }
}
