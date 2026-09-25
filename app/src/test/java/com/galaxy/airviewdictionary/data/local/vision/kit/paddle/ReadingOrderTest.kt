package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.text.Bidi

/**
 * [ReadingOrder] — 아랍 문자 인식기의 보이는 순서 → 읽는 순서(`.docs/vision-engine-design.md` §22).
 *
 * 보이는 순서는 [display] 가 만든다: 읽는 순서의 글을 오른쪽→왼쪽 문단으로 양방향 알고리즘(`java.text.Bidi`)에 넣어 화면 순서로 놓고, 홀수 단계
 * (오른쪽→왼쪽)의 짝 괄호는 거울상 글꼴로 바꾼다 — 인식기가 화면에서 보는 글자 모양이다.
 */
class ReadingOrderTest {

    private val mirror = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '«' to '»', '»' to '«')

    private fun display(logical: String): String {
        val bidi = Bidi(logical, Bidi.DIRECTION_RIGHT_TO_LEFT)
        val levels = ByteArray(logical.length) { bidi.getLevelAt(it).toByte() }
        val glyphs = Array<Any>(logical.length) { i -> logical[i].let { c -> if (levels[i] % 2 == 1) mirror[c] ?: c else c } }
        Bidi.reorderVisually(levels, 0, glyphs, 0, glyphs.size)
        return glyphs.joinToString("")
    }

    private fun assertRoundTrip(logical: String) {
        val visual = display(logical)
        assertEquals("보이는 순서: $visual", logical, ReadingOrder.of(visual))
    }

    @Test
    fun persianDigitsKeepTheirOrder() {
        // real_fa702: 정답 ۱۴۰۱ 이 ١٠٤١ 로 나오던 것(확장 아랍-인도 숫자 U+06F0..06F9 는 EN)
        val logical = "در سال ۱۴۰۱ شمسی"
        assertEquals("۱۴۰۱ لاس رد", display(logical).substringAfter("یسمش "))
        assertRoundTrip(logical)
        assertRoundTrip("تاریخ ۱۴۰۱/۰۲/۰۳ است")
    }

    @Test
    fun arabicIndicDigitsKeepTheirOrder() {
        // 아랍-인도 숫자 U+0660..0669 는 AN
        assertRoundTrip("في عام ١٤٠١ هجري")
        assertEquals("عام ١٤٠١", ReadingOrder.of("١٤٠١ ماع"))
    }

    @Test
    fun asciiDigitsKeepTheirOrder() {
        assertRoundTrip("في عام 2019 ميلادي")
        assertRoundTrip("مساحتها 1,010,408 كم و 3.14 و 12:30")
        assertEquals("عام 2019", ReadingOrder.of("2019 ماع"))
    }

    @Test
    fun bracketsAreMirroredBack() {
        // real_ar700: (2019) 가 )2019( 로 나오던 것
        assertRoundTrip("مصر (2019) دولة")
        assertEquals("مصر (2019) دولة", ReadingOrder.of("ةلود (2019) رصم"))
        assertRoundTrip("شعار «استقلال، آزادی» بود")
        assertRoundTrip("نهر النيل [2] في")
    }

    @Test
    fun colonStaysWithTheRightToLeftFlow() {
        // `توجه: متن` 이 `توجه :متن` 으로 붙던 것 — 부호와 공백만으로는 LTR 덩어리가 아니다
        assertEquals("توجه: متن", ReadingOrder.of("نتم :هجوت"))
        assertRoundTrip("توجه: متن")
    }

    @Test
    fun latinWordWithDiacriticsKeepsItsOrder() {
        // `café` 가 `écaf` 로 뒤집히던 것 — é 도 강한 LTR 글자다
        assertEquals("قهوة café", ReadingOrder.of("café ةوهق"))
        assertRoundTrip("قهوة café جيدة")
        assertRoundTrip("Crème brûlée حلوى")
    }

    @Test
    fun mixedText() {
        assertRoundTrip("الذكاء الاصطناعي (بالإنجليزية: Artificial Intelligence) هو فرع")
        assertRoundTrip("صورة: Eltabakh/DW")
        assertRoundTrip("بنسبة 7.8% من السكان")
        assertRoundTrip("list 1 of 2 تذكرون")
        val visual = display("في عام 2019 (أي ١٤٤٠) قال: café")
        assertNotEquals("시험 글이 실제로 뒤섞여 보여야 한다", visual.reversed(), visual)
        assertEquals("في عام 2019 (أي ١٤٤٠) قال: café", ReadingOrder.of(visual))
    }

    @Test
    fun mirroredItemsKeepTheirBoxes() {
        // 읽는 순서 `ب(12)` 는 화면에 `(12)ب` 로 보인다. 괄호는 글만 거울상을 되돌리고 자리(x)는 화면 그대로다
        data class Glyph(val text: String, val x: Int)
        val visual = listOf(Glyph("(", 0), Glyph("1", 1), Glyph("2", 2), Glyph(")", 3), Glyph("ب", 4))
        val read = ReadingOrder.of(visual, { it.text }, { g, mirrored -> g.copy(text = mirrored) })
        assertEquals(listOf(Glyph("ب", 4), Glyph("(", 3), Glyph("1", 1), Glyph("2", 2), Glyph(")", 0)), read)
    }
}
