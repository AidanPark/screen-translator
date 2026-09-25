package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

/**
 * 아랍 문자 인식기가 내는 **보이는 순서**(왼쪽부터)의 글을 읽는 순서로 되돌린다(`.docs/vision-engine-design.md` §22).
 *
 * 화면은 유니코드 양방향 알고리즘으로 그려진다 — 오른쪽→왼쪽 흐름 안에서 라틴 글자와 숫자 덩어리만 왼쪽→오른쪽으로 놓이고, 그 밖의 짝 괄호는
 * 거울상 글꼴로 그려진다. 그 역을 이렇게 근사한다.
 *  1. 전체를 뒤집는다.
 *  2. 왼쪽→오른쪽 덩어리는 안의 순서를 되살린다. 덩어리는 강한 LTR 글자(`L`)와 숫자(`EN`·`AN` — 아랍·페르시아 숫자 포함)가 잇닿은 것이고,
 *     사이에 끼었을 때만 함께 묶는 것이 있다: 공백(덩어리 안쪽의 공백), 숫자와 숫자 사이의 숫자 구분자 하나(`ES`·`CS`·`ET` — `3.14`, `1,000`,
 *     `12:30`), 라틴 글자와 라틴 글자 사이의 중립 문자(`Eltabakh/DW`). 덩어리 끝의 공백·부호는 넣지 않는다 — `توجه: متن` 의 `: ` 는 오른쪽→왼쪽 흐름이다.
 *  3. 덩어리 밖의 짝 괄호 `()[]{}<>«»‹›` 는 거울상을 되돌린다.
 *
 * 파이썬 포트(`tools/paddle/pipeline.py` 의 `reading_order`)와 같은 알고리즘이다. 안드로이드 타입을 쓰지 않는다 — JVM 시험이 직접 부른다.
 */
internal object ReadingOrder {

    private enum class Kind { LTR, DIGIT, SEPARATOR, SPACE, NEUTRAL, RTL }

    private val MIRROR = mapOf(
        '(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{',
        '<' to '>', '>' to '<', '«' to '»', '»' to '«', '‹' to '›', '›' to '‹',
    )

    /** 글(어휘 한 항목)의 첫 코드 포인트로 가른다 — 사전에는 BMP 밖 글자(`𝑢`)도 있다. */
    private fun kindOf(text: String): Kind {
        if (text.isEmpty()) return Kind.NEUTRAL
        return when (Character.getDirectionality(text.codePointAt(0))) {
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> Kind.LTR
            Character.DIRECTIONALITY_EUROPEAN_NUMBER, Character.DIRECTIONALITY_ARABIC_NUMBER -> Kind.DIGIT
            Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR,
            Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR,
            Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR -> Kind.SEPARATOR
            Character.DIRECTIONALITY_WHITESPACE -> Kind.SPACE
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> Kind.RTL
            else -> Kind.NEUTRAL
        }
    }

    /** 덩어리 글자 [left] 와 [right] 사이(둘 다 제외)를 덩어리에 넣는가. */
    private fun joins(kinds: List<Kind>, left: Int, right: Int): Boolean {
        val gap = kinds.subList(left + 1, right)
        return when {
            gap.all { it == Kind.SPACE } -> true
            gap.size == 1 && gap[0] == Kind.SEPARATOR && kinds[left] == Kind.DIGIT && kinds[right] == Kind.DIGIT -> true
            else -> kinds[left] == Kind.LTR && kinds[right] == Kind.LTR && gap.none { it == Kind.RTL }
        }
    }

    /** 보이는 순서의 [visual] 을 읽는 순서로. 거울상을 되돌릴 항목은 [mirror] 로 글을 바꾼 새 항목이 된다. */
    fun <T> of(visual: List<T>, textOf: (T) -> String, mirror: (T, String) -> T): List<T> {
        val kinds = visual.map { kindOf(textOf(it)) }
        val inRun = BooleanArray(visual.size) { kinds[it] == Kind.LTR || kinds[it] == Kind.DIGIT }
        var last = -1
        for (i in visual.indices) {
            if (!inRun[i]) continue
            if (last >= 0 && i - last > 1 && joins(kinds, last, i)) for (k in last + 1 until i) inRun[k] = true
            last = i
        }
        val segments = mutableListOf<List<T>>()
        var i = 0
        while (i < visual.size) {
            if (inRun[i]) {
                var end = i
                while (end < visual.size && inRun[end]) end++
                segments.add(visual.subList(i, end))
                i = end
            } else {
                val item = visual[i]
                val text = textOf(item)
                val mirrored = if (text.length == 1) MIRROR[text[0]] else null
                segments.add(listOf(if (mirrored != null) mirror(item, mirrored.toString()) else item))
                i++
            }
        }
        return segments.asReversed().flatten()
    }

    /** 코드 포인트 단위로 [of]. */
    fun of(visual: String): String {
        val units = mutableListOf<String>()
        var i = 0
        while (i < visual.length) {
            val n = Character.charCount(visual.codePointAt(i))
            units.add(visual.substring(i, i + n))
            i += n
        }
        return of(units, { it }, { _, mirrored -> mirrored }).joinToString("")
    }
}
