package com.galaxy.airviewdictionary.data.local.vision.model

/**
 * 문단의 단어를 문장에 나눠 주는 규칙. 기하·ICU 와 떨어진 순수한 부분이라 JVM 시험으로 잰다([Paragraph.sentences] 가 쓴다).
 *
 * 단어 하나는 언제나 **한 문장에만** 간다. 경계가 단어 안에 떨어지면(ML Kit 은 중국어·일본어 한 줄을 단어 하나로 주고 그 안에
 * `。` 가 있다 — 저장소 덤프에서 일본어 45%, 중국어 67%. PP-OCRv5 는 `.` 뒤 띄어쓰기를 놓친다) 글자 상자로 그 자리에서 자른다.
 * 글자로 자를 수 없는 단어는 마지막 글자가 든 문장으로 간다(2.7.3 규칙). 예전에는 경계에 걸친 단어를 걸친 문장마다 넣어,
 * 한 줄이 두 문장에 겹쳐 들어갔다(`.docs/vision-engine-design.md` §23).
 */
internal object SentenceSplit {

    /**
     * 단어 하나의 글. [chars] 는 글자마다의 글이다 — 글자로 자를 수 있을 때만 준다. 글자를 이으면 [text] 가 되거나,
     * 글자 수가 [text] 길이와 같아야 자른다. 아니면 단어째로 둔다.
     */
    class WordText(val text: String, val chars: List<String>? = null)

    /**
     * 단어(또는 그 조각)가 가는 문장. [chars]·[text] 는 잘린 조각의 글자 목록 구간과 글 구간이고, 단어째면 둘 다 null 이다.
     */
    data class Piece(val word: Int, val sentence: Int, val chars: IntRange? = null, val text: IntRange? = null)

    /** 문장 경계를 찾을 글. 단어를 빈칸 하나로 잇는다 — [assign] 의 경계는 이 글의 글자 인덱스다. */
    fun joinedText(words: List<WordText>): String = words.joinToString(" ") { it.text }

    /**
     * 단어를 문장에 나눠 준다.
     *
     * @param boundaries [joinedText] 에서 찾은 문장 경계. 오름차순이고 0 과 글 길이를 포함한다(ICU `BreakIterator` 의 결과 그대로).
     * @return 조각 목록, 단어 순서대로. 단어마다 조각 하나(단어째) 또는 경계에서 잘린 여러 조각이다.
     */
    fun assign(words: List<WordText>, boundaries: List<Int>): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var from = 0
        words.forEachIndexed { index, word ->
            if (index > 0) from++ // 잇는 빈칸
            val to = from + word.text.length
            val first = sentenceAt(boundaries, from)
            val last = sentenceAt(boundaries, maxOf(from, to - 1))
            val offsets = if (first != last) charOffsets(word) else null
            if (offsets == null) {
                // 한 문장 안이거나, 글자로 자를 수 없다 — 마지막 글자가 든 문장으로
                pieces.add(Piece(index, last))
            } else {
                // 글자가 시작하는 자리의 문장으로 글자를 나눈다. 경계는 오름차순이라 같은 문장의 글자는 이어져 있다.
                val runs = mutableListOf<Piece>()
                var runStart = 0
                var runSentence = sentenceAt(boundaries, from + offsets[0])
                for (c in 1..offsets.size) {
                    val sentence = if (c < offsets.size) sentenceAt(boundaries, from + offsets[c]) else -1
                    if (sentence != runSentence) {
                        val textEnd = if (c < offsets.size) offsets[c] else word.text.length
                        runs.add(Piece(index, runSentence, runStart until c, offsets[runStart] until textEnd))
                        runStart = c
                        runSentence = sentence
                    }
                }
                if (runs.size == 1) pieces.add(Piece(index, runs[0].sentence)) else pieces.addAll(runs)
            }
            from = to
        }
        return pieces
    }

    /** 글자마다 단어 글 안에서 시작하는 자리. 글자로 자를 수 없으면 null. */
    private fun charOffsets(word: WordText): IntArray? {
        val chars = word.chars?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            chars.joinToString("") == word.text -> {
                var at = 0
                IntArray(chars.size) { i -> at.also { at += chars[i].length } }
            }
            chars.size == word.text.length -> IntArray(chars.size) { it }
            else -> null
        }
    }

    /** [position] 이 든 문장의 번호. 경계 밖이면 가까운 끝 문장. */
    private fun sentenceAt(boundaries: List<Int>, position: Int): Int {
        val found = boundaries.binarySearch(position)
        val index = if (found >= 0) found else -found - 2
        return index.coerceIn(0, (boundaries.size - 2).coerceAtLeast(0))
    }
}
