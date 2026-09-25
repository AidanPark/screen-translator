package com.galaxy.airviewdictionary.data.local.vision.ocr

import kotlin.math.abs
import kotlin.math.min

/**
 * 검출기가 찾은 줄을 읽는 순서로 이어 화면 글([OcrText.text])을 만든다(`.docs/vision-engine-design.md` §23).
 *
 * PP-OCRv5 검출기는 줄을 찾은 순서(윗 픽셀부터 훑는 순서)로 준다. 그 순서로 이으면 한 행이 여러 상자로 잡힌 아랍어 행이나 두 단
 * 배치에서 같은 행의 상자 순서가 뒤섞인다. 글 전체를 조립 없이 번역하는 영역 선택·고정 영역이 그 글을 그대로 보낸다.
 *
 * 규칙: 세로 중심이 가까운(두 줄 높이 중 작은 쪽의 절반 안) 줄을 한 행으로 묶고, 행은 위에서 아래로, 행 안은 그 행의 글이 주로
 * 오른쪽→왼쪽 문자면 오른쪽부터, 아니면 왼쪽부터 잇는다. 같은 행의 상자는 빈칸으로, 행은 줄바꿈으로 잇는다. 빈 글은 건너뛴다.
 *
 * ML Kit 은 제 덩어리 순서로 글을 주므로 쓰지 않는다 — 검출만 된 줄을 읽은 경우에만 쓴다.
 */
object ReadingOrder {

    /** 줄 하나의 상자와 글. 상자가 없는 줄은 [box] = null — 맨 뒤에 받은 순서대로 붙인다. */
    class Item(val box: Box?, val text: String)

    /** 화면 좌표 상자. `android.graphics.Rect` 에 기대지 않아 JVM 시험으로 잴 수 있다. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerY: Double get() = (top + bottom) / 2.0
        val height: Int get() = bottom - top
    }

    /** [lines] 의 글을 읽는 순서로 잇는다. */
    fun text(lines: List<OcrLine>): String = join(
        lines.map { line -> Item(line.boundingBox?.let { Box(it.left, it.top, it.right, it.bottom) }, line.text) }
    )

    /** [items] 를 행으로 묶어 읽는 순서로 잇는다. */
    fun join(items: List<Item>): String = rows(items).joinToString("\n") { row ->
        row.joinToString(" ") { it.text.trim() }
    }

    /** 읽는 순서의 행들. 행마다 읽는 순서의 줄, 빈 글은 뺀다. */
    fun rows(items: List<Item>): List<List<Item>> {
        val present = items.filter { it.text.isNotBlank() }
        val boxed = present.filter { it.box != null }.sortedWith(compareBy({ it.box!!.centerY }, { it.box!!.left }))

        val rows = mutableListOf<MutableList<Item>>()
        for (item in boxed) {
            val box = item.box!!
            val row = rows.lastOrNull()
            if (row != null && sameRow(row, box)) row.add(item) else rows.add(mutableListOf(item))
        }

        val ordered: List<List<Item>> = rows.map { row ->
            if (isRightToLeft(row)) row.sortedByDescending { it.box!!.right } else row.sortedBy { it.box!!.left }
        }
        val unboxed = present.filter { it.box == null }
        return if (unboxed.isEmpty()) ordered else ordered + unboxed.map { listOf(it) }
    }

    /** [box] 의 세로 중심이 [row] 의 평균 세로 중심에서 (줄 높이·행 평균 높이 중 작은 쪽의) 절반 안인가. */
    private fun sameRow(row: List<Item>, box: Box): Boolean {
        val rowCenter = row.sumOf { it.box!!.centerY } / row.size
        val rowHeight = row.sumOf { it.box!!.height }.toDouble() / row.size
        val tolerance = min(box.height.toDouble(), rowHeight) / 2
        return abs(box.centerY - rowCenter) <= tolerance
    }

    /** 행의 글자 중 오른쪽→왼쪽 문자(아랍·히브리 등)가 절반을 넘는가. */
    private fun isRightToLeft(row: List<Item>): Boolean {
        var rtl = 0
        var letters = 0
        for (item in row) for (c in item.text) {
            if (!c.isLetter()) continue
            letters++
            val direction = Character.getDirectionality(c)
            if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) rtl++
        }
        return letters > 0 && rtl * 2 > letters
    }
}
