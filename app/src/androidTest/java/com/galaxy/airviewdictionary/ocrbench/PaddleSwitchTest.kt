package com.galaxy.airviewdictionary.ocrbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.R
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import com.galaxy.airviewdictionary.data.local.vision.model.VisionResponse
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.galaxy.airviewdictionary.ui.screen.overlay.selection.createOverlaidBitmap
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PP-OCRv5 끄기 스위치(`.docs/vision-engine-design.md` §19)와 영역 선택의 검정 칠(§17). 스위치 값은 Remote Config 기본값으로 바꿔 본다 —
 * 콘솔에 올리지 않고 같은 판정 경로(`getValue`)를 탄다.
 */
class PaddleSwitchTest {

    private val repository = VisionRepository(InstrumentationRegistry.getInstrumentation().targetContext)

    private val arabic = listOf(
        "القاهرة هي عاصمة جمهورية مصر العربية وأكبر مدنها، وتقع على ضفاف نهر النيل في شمال البلاد، وهي من أكبر المدن في أفريقيا والشرق الأوسط.",
        "يعد نهر النيل أطول أنهار العالم، ويمر عبر عدة دول في أفريقيا قبل أن يصب في البحر الأبيض المتوسط، وقد قامت على ضفافه حضارات قديمة عظيمة.",
        "تشتهر مصر بالأهرامات وأبي الهول، ويزورها ملايين السياح كل عام لمشاهدة آثارها التاريخية والاستمتاع بشواطئها على البحر الأحمر.",
    )

    /** 그린 화면과 문단마다의 상자. */
    private fun screen(): Pair<Bitmap, List<Rect>> {
        val bitmap = Bitmap.createBitmap(1080, 2000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 44f }
        var top = 120
        val boxes = arabic.map { text ->
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 960).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            canvas.save(); canvas.translate(60f, top.toFloat()); layout.draw(canvas); canvas.restore()
            Rect(40, top - 20, 1040, top + layout.height + 20).also { top += layout.height + 160 }
        }
        return bitmap to boxes
    }

    private fun setSwitch(enabled: Boolean, auto: Boolean) {
        Tasks.await(Firebase.remoteConfig.setDefaultsAsync(mapOf(
            RemoteConfigRepository.PADDLE_OCR_ENABLED to enabled,
            RemoteConfigRepository.PADDLE_OCR_AUTO_ENABLED to auto,
        )))
    }

    @After
    fun restore() {
        Tasks.await(Firebase.remoteConfig.setDefaultsAsync(R.xml.remote_config_defaults))
    }

    @Test
    fun offSendsArabicBackToMlKit() {
        setSwitch(enabled = false, auto = true)
        val off = runBlocking { (repository.request(screen().first, "ar") as VisionResponse.Success).result }
        assertNull("꺼지면 ML Kit 이 다 읽는다", off.unread)

        setSwitch(enabled = true, auto = true)
        val on = runBlocking { (repository.request(screen().first, "ar") as VisionResponse.Success).result }
        assertNotNull("켜면 PP-OCRv5 가 검출만 하고 가리킨 문단을 읽는다", on.unread)
    }

    @Test
    fun autoOffKeepsAutoOnMlKit() {
        setSwitch(enabled = true, auto = false)
        val off = runBlocking { (repository.request(screen().first, "auto") as VisionResponse.Success).result }
        assertNull("auto 표본을 돌리지 않으면 ML Kit 이 다 읽는다", off.unread)
        val named = runBlocking { (repository.request(screen().first, "ar") as VisionResponse.Success).result }
        assertNotNull("지정 언어는 계속 PP-OCRv5", named.unread)

        setSwitch(enabled = true, auto = true)
        val on = runBlocking { (repository.request(screen().first, "auto") as VisionResponse.Success).result }
        assertNotNull("켜면 PP-OCRv5 가 이긴다", on.unread)
    }

    /** 고정 영역처럼 좁은 영역을 auto 로 — 줄이 영역 가장자리에서 잘린다. PP-OCRv5 가 이기고 글이 나와야 한다. */
    @Test
    fun narrowAreaInAutoReadsArabic() {
        val (bitmap, boxes) = screen()
        val first = boxes[0]
        val area = createOverlaidBitmap(bitmap, Rect(first.left + 300, first.top, first.right - 100, first.bottom))
        val response = runBlocking { repository.request(area, "auto", readAll = true) }
        assertTrue("요청이 실패했다: $response", response is VisionResponse.Success)
        val tx = (response as VisionResponse.Success).result
        assertTrue("아랍 문자권으로 판정해야 한다: ${tx.detectedLanguageCode}", tx.detectedLanguageCode in setOf("ar", "fa", "ur"))
        assertTrue("읽은 글에 아랍어가 있어야 한다: '${tx.ocr.text}'", tx.ocr.text.any { it in '\u0600'..'\u06FF' })
    }

    @Test
    fun selectedAreaIsNotReadInverted() {
        val (bitmap, boxes) = screen()
        val area = createOverlaidBitmap(bitmap, boxes[0])
        val tx = runBlocking { (repository.request(area, "ar", readAll = true) as VisionResponse.Success).result }
        val lines = tx.ocr.blocks.flatMap { it.lines }
        assertTrue("영역 안 문단은 3줄 안팎이어야 한다(뒤집히면 단어 조각으로 부서진다): ${lines.size} ${lines.map { it.text }}", lines.size <= 5)
        assertTrue("읽은 글에 아랍어가 있어야 한다: ${tx.ocr.text}", tx.ocr.text.contains("القاهرة") || tx.ocr.text.contains("النيل"))
    }
}
