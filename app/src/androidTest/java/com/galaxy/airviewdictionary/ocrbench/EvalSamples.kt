package com.galaxy.airviewdictionary.ocrbench

import android.content.Context
import android.graphics.Rect
import com.galaxy.airviewdictionary.data.local.vision.WritingDirection
import com.galaxy.airviewdictionary.data.local.vision.model.Line
import com.galaxy.airviewdictionary.data.local.vision.model.Word
import com.galaxy.airviewdictionary.data.remote.translation.Language
import org.json.JSONArray
import org.json.JSONObject

/**
 * 인식기가 기하 단계에 무엇을 넘기는가.
 *
 *  - WORDS  ML Kit 가로 경로 — 단어를 넘기고 앱이 줄을 유도한다
 *  - LINES  검출기(PP-OCRv5) — 줄을 직접 넘기고 그 줄을 신뢰한다. 단어→줄 단계를 지나가지 않는다
 *  - VSPLIT ML Kit 세로 경로(`textToVerticalParagraphs`) — 한 화면의 줄을 종횡비로 둘로 나눈다.
 *           세로로 긴 줄은 `toLine()` 으로 줄째 세로 경로에, 나머지는 단어로 흩어 가로 경로에
 */
enum class InputUnit { WORDS, LINES, VSPLIT }

/**
 * 평가 표본 한 장.
 *
 * 입력은 [OcrDumpTest] 가 떠 둔 박스(단어 또는 줄)와, 브라우저 DOM 또는 접근성 트리에서 받은
 * 정답 블록이다. PNG 도 OCR 도 비트맵도 쓰지 않는다 — 조립기는 비트맵을 보지 않으므로 그럴
 * 이유가 없고, 그렇게 두면 설정 비교가 완전히 결정적이 된다.
 *
 * 갈래·역할·쓰기 방향·입력 단위는 에셋의 `classes.tsv` 에서 읽는다. 코드에 표본 이름을 박으면
 * 표본을 늘릴 때마다 코드를 고치게 되고, 그게 실험을 무겁게 만든다.
 */
class Sample(
    val name: String,
    /** 언어코드. 언어별 기준값을 채우는 데 쓴다. */
    val lang: String,
    /** web | book-justified | book-ragged | layout | native | detector-real | vertical … */
    val group: String,
    /** tune(상수를 이 표본으로 골랐다) | holdout(선택에 쓰지 않았다) */
    val role: String,
    val direction: WritingDirection,
    val input: InputUnit,
    /**
     * 인식기가 준 박스와 글자열. WORDS 는 단어, LINES 는 줄, VSPLIT 은 가로 부분의 단어다.
     *
     * [Word] 와 [Line] 은 **조립할 때마다 새로 만든다.** 같은 객체를 설정 여럿에 돌려쓰면 앞
     * 설정의 상태가 남을 수 있다 — 조립기가 Line 에 단어를 덧붙이기 때문이다.
     */
    val boxes: List<Pair<Rect, String>>,
    /** VSPLIT 일 때만 — 세로로 긴 ML Kit 줄 하나마다 그 줄의 단어 박스들. */
    val verticalLineBoxes: List<List<Pair<Rect, String>>>,
    val blocks: List<Pair<Int, Rect>>,
    /** 블록 id → DOM 태그. 인접 형제 목록 병합을 가려내는 데 쓴다. */
    val tags: Map<Int, String>,
) {
    val isVertical: Boolean
        get() = direction == WritingDirection.TTB_LTR || direction == WritingDirection.TTB_RTL

    val linesFromDetector: Boolean
        get() = input == InputUnit.LINES

    /** 세로 분기에서 가로로 긴 줄이 가는 방향. 프로덕션과 같은 함수로 정한다. */
    val horizontalDirection: WritingDirection
        get() = Language.writingDirection(lang, false)

    private fun fontHeightOf(box: Rect) = if (isVertical) box.width().toDouble() else box.height().toDouble()

    fun buildWords(): List<Word> {
        // 세로 분기의 가로 부분은 가로 방향 단어다.
        val d = if (input == InputUnit.VSPLIT) horizontalDirection else direction
        // chars 는 인식 단계의 단어 채택 필터로만 쓰이고 조립에는 관여하지 않는다.
        return boxes.map { (box, text) -> Word(box, text, d, emptyList()) }
    }

    /**
     * 검출기는 줄 박스만 준다. 줄 하나에 단어 하나를 세워 [Line] 을 만든다 — 조립기가 Line 의
     * 폰트높이를 단어에서 얻으므로 박스 크기를 그대로 넘긴다.
     */
    fun buildLines(): List<Line> = boxes.map { (box, text) ->
        Line(mutableListOf(Word(box, text, direction, emptyList(), fontHeightOf(box))), direction)
    }

    /** `toLine()` 과 같다 — ML Kit 줄의 요소들이 그대로 단어가 된다. */
    fun buildVerticalLines(): List<Line> = verticalLineBoxes.map { words ->
        Line(words.map { (box, text) -> Word(box, text, direction, emptyList()) }.toMutableList(), direction)
    }
}

object EvalSamples {

    private fun read(context: Context, asset: String) =
        context.assets.open(asset).use { String(it.readBytes(), Charsets.UTF_8) }

    private fun directionOf(name: String) = when (name.lowercase().trim()) {
        "rtl" -> WritingDirection.RTL
        "ttb_rtl" -> WritingDirection.TTB_RTL
        "ttb_ltr" -> WritingDirection.TTB_LTR
        else -> WritingDirection.LTR
    }

    private fun inputOf(name: String) = when (name.trim()) {
        "lines" -> InputUnit.LINES
        "vsplit" -> InputUnit.VSPLIT
        else -> InputUnit.WORDS
    }

    private fun boxesOf(array: JSONArray) = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        Rect(o.getInt("l"), o.getInt("t"), o.getInt("r"), o.getInt("b")) to o.optString("text", "")
    }

    /**
     * 표본을 읽는다. [groups]·[roles]·[inputs] 를 주면 그것만 고른다.
     *
     * 입력 에셋이 없는 줄은 조용히 건너뛴다 — 표본을 새로 모으고 덤프를 아직 안 뜬 중간 상태가
     * 정상이기 때문이다.
     */
    fun load(
        context: Context,
        groups: Set<String>? = null,
        roles: Set<String>? = null,
        inputs: Set<InputUnit>? = null,
    ): List<Sample> {
        val assets = context.assets.list("")!!.toSet()
        val out = mutableListOf<Sample>()
        for (line in read(context, "classes.tsv").lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split("\t")
            if (f.size < 8) continue
            val name = f[0]; val lang = f[1]; val group = f[2]; val role = f[3]
            val direction = directionOf(f[4])
            val input = inputOf(f[5])
            val inputAsset = f[6].trim(); val truthAsset = f[7].trim()

            if (groups != null && group !in groups) continue
            if (roles != null && role !in roles) continue
            if (inputs != null && input !in inputs) continue
            if ("$inputAsset.json" !in assets || "$truthAsset.json" !in assets) continue

            val boxes: List<Pair<Rect, String>>
            val verticalLineBoxes: List<List<Pair<Rect, String>>>
            if (input == InputUnit.VSPLIT) {
                val v = JSONObject(read(context, "$inputAsset.json"))
                boxes = boxesOf(v.getJSONArray("horizontalWords"))
                val lines = v.getJSONArray("verticalLines")
                verticalLineBoxes = (0 until lines.length()).map { i ->
                    boxesOf(lines.getJSONObject(i).getJSONArray("words"))
                }
            } else {
                boxes = boxesOf(JSONArray(read(context, "$inputAsset.json")))
                verticalLineBoxes = emptyList()
            }

            val bj = JSONArray(read(context, "$truthAsset.json"))
            val blocks = (0 until bj.length()).map { i ->
                val o = bj.getJSONObject(i)
                o.getInt("id") to Rect(o.getInt("l"), o.getInt("t"), o.getInt("r"), o.getInt("b"))
            }
            val tags = (0 until bj.length()).associate { i ->
                val o = bj.getJSONObject(i)
                o.getInt("id") to o.optString("tag", "")
            }
            out.add(Sample(name, lang, group, role, direction, input, boxes, verticalLineBoxes, blocks, tags))
        }
        return out.sortedWith(compareBy({ it.group }, { it.lang }, { it.name.length }, { it.name }))
    }
}
