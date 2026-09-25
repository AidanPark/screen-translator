package com.galaxy.airviewdictionary.data.local.vision.ocr

import com.galaxy.airviewdictionary.data.local.vision.ocr.ReadingOrder.Box
import com.galaxy.airviewdictionary.data.local.vision.ocr.ReadingOrder.Item
import org.junit.Assert.assertEquals
import org.junit.Test

/** 검출기 줄을 읽는 순서로 잇기(`.docs/vision-engine-design.md` §23). 입력 순서는 검출기처럼 윗 픽셀 순으로 준다. */
class ReadingOrderTest {

    private fun item(left: Int, top: Int, right: Int, bottom: Int, text: String) = Item(Box(left, top, right, bottom), text)

    /** 아랍어 한 행이 상자 둘로 잡혔다. 왼쪽 상자가 1px 위라 먼저 온다 — 오른쪽 상자부터 읽어야 한다. */
    @Test
    fun rightToLeftRowReadsFromTheRight() {
        val detected = listOf(
            item(100, 10, 300, 40, "الثاني"),
            item(350, 11, 600, 41, "الجزء الأول"),
            item(80, 60, 600, 90, "السطر التالي"),
        )
        assertEquals("الجزء الأول الثاني\nالسطر التالي", ReadingOrder.join(detected))
    }

    /** 라틴 행은 왼쪽부터. 오른쪽 상자가 더 위에서 시작해도 같은 행이다. */
    @Test
    fun leftToRightRowReadsFromTheLeft() {
        val detected = listOf(
            item(500, 98, 900, 130, "right"),
            item(0, 100, 400, 130, "left"),
            item(0, 150, 400, 180, "next"),
        )
        assertEquals("left right\nnext", ReadingOrder.join(detected))
    }

    /** 두 단 배치 — 검출 순서가 행마다 좌우를 뒤바꿔도 행 안의 순서는 늘 같다. */
    @Test
    fun twoColumnRowsKeepAConsistentOrder() {
        val detected = listOf(
            item(0, 100, 400, 130, "L1"),
            item(500, 104, 900, 134, "R1"),
            item(500, 146, 900, 176, "R2"),
            item(0, 150, 400, 180, "L2"),
        )
        assertEquals("L1 R1\nL2 R2", ReadingOrder.join(detected))
    }

    /** 행은 위에서 아래로 — 받은 순서와 무관하다. */
    @Test
    fun rowsGoTopToBottom() {
        val detected = listOf(
            item(0, 300, 400, 330, "third"),
            item(0, 100, 400, 130, "first"),
            item(0, 200, 400, 230, "second"),
        )
        assertEquals("first\nsecond\nthird", ReadingOrder.join(detected))
    }

    /** 세로 중심이 줄 높이의 절반 넘게 벌어지면 다른 행이다. */
    @Test
    fun linesMoreThanHalfALineApartAreDifferentRows() {
        val detected = listOf(
            item(500, 100, 900, 130, "upper"),
            item(0, 120, 400, 150, "lower"),
        )
        assertEquals("upper\nlower", ReadingOrder.join(detected))
    }

    /** 빈 글은 건너뛰고, 상자가 없는 줄은 맨 뒤에 받은 순서대로. */
    @Test
    fun blankLinesAreSkippedAndUnboxedLinesGoLast() {
        val detected = listOf(
            Item(null, "loose"),
            item(0, 100, 400, 130, "  "),
            item(0, 150, 400, 180, "body"),
        )
        assertEquals("body\nloose", ReadingOrder.join(detected))
    }

    /** 행의 방향은 그 행 글자의 과반으로 — 숫자·기호는 세지 않는다. */
    @Test
    fun rowDirectionFollowsTheMajorityOfLetters() {
        val arabicWithNumber = listOf(item(0, 10, 100, 40, "2026"), item(150, 10, 400, 40, "سنة"))
        assertEquals("سنة 2026", ReadingOrder.join(arabicWithNumber))
        val latinWithArabicWord = listOf(item(0, 10, 300, 40, "Welcome to"), item(350, 10, 450, 40, "دبي"))
        assertEquals("Welcome to دبي", ReadingOrder.join(latinWithArabicWord))
    }
}
