package com.galaxy.airviewdictionary.data.local.vision.model

import com.galaxy.airviewdictionary.data.local.vision.model.SentenceSplit.Piece
import com.galaxy.airviewdictionary.data.local.vision.model.SentenceSplit.WordText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 단어를 문장에 나눠 주는 규칙(`.docs/vision-engine-design.md` §23). 경계는 ICU `BreakIterator` 가 그 글에 줄 값을 손으로 적었다 —
 * JVM 시험에는 android.icu 가 없다.
 */
class SentenceSplitTest {

    /** 글자마다 한 글자씩(ML Kit 의 중국어·일본어 기호, PP-OCRv5 의 CTC 글자). */
    private fun word(text: String) = WordText(text, text.map { it.toString() })

    /** 문장마다 그 문장에 든 조각의 글. 조각은 빈칸으로 잇는다(Sentence.representation 과 같다). */
    private fun sentences(words: List<WordText>, pieces: List<Piece>): List<String> =
        pieces.groupBy { it.sentence }.toSortedMap().values.map { inSentence ->
            inSentence.joinToString(" ") { piece ->
                val text = words[piece.word].text
                piece.text?.let { text.substring(it.first, it.last + 1) } ?: text
            }
        }

    /** 단어마다 한 문장에만 간다 — 단어째 조각 하나이거나, 잘린 조각이 글자·글을 빈틈없이 한 번씩 덮는다. */
    private fun assertEachWordOnce(words: List<WordText>, pieces: List<Piece>) {
        assertEquals(words.indices.toList(), pieces.map { it.word }.distinct())
        pieces.groupBy { it.word }.forEach { (index, own) ->
            if (own.size == 1) {
                assertEquals(null, own[0].chars)
                assertEquals(null, own[0].text)
            } else {
                val word = words[index]
                assertEquals(word.chars!!.indices.toList(), own.flatMap { it.chars!!.toList() })
                assertEquals(word.text, own.joinToString("") { word.text.substring(it.text!!.first, it.text!!.last + 1) })
                assertEquals(own.map { it.sentence }.distinct(), own.map { it.sentence })
            }
        }
    }

    /** 일본어 한 줄이 단어 하나로 오고 그 안에 `。` 가 있다 — 그 자리에서 잘라 앞은 앞 문장, 뒤는 다음 문장으로. */
    @Test
    fun japaneseLineIsCutAtTheFullStopInsideIt() {
        val words = listOf(word("圧配置の影響を受けて動きます。また、台風は地球の自転の影響で"), word("北上します。"))
        val text = SentenceSplit.joinedText(words)
        val boundaries = listOf(0, text.indexOf('。') + 1, text.length)

        val pieces = SentenceSplit.assign(words, boundaries)

        assertEachWordOnce(words, pieces)
        assertEquals(listOf("圧配置の影響を受けて動きます。", "また、台風は地球の自転の影響で 北上します。"), sentences(words, pieces))
        assertEquals(3, pieces.size)
    }

    /** 글자로 자를 수 없으면(글자가 빠졌거나 없다) 단어째 마지막 글자가 든 문장으로 — 2.7.3 규칙. 두 문장에 겹쳐 넣지 않는다. */
    @Test
    fun wordThatCannotBeCutGoesToTheSentenceOfItsLastCharacter() {
        val line = "圧配置の影響を受けて動きます。また、台風は地球の自転の影響で"
        for (unsplittable in listOf(WordText(line, null), WordText(line, line.drop(1).map { it.toString() }))) {
            val words = listOf(word("気圧の"), unsplittable, word("北上します。"))
            val text = SentenceSplit.joinedText(words)
            val boundaries = listOf(0, text.indexOf('。') + 1, text.length)

            val pieces = SentenceSplit.assign(words, boundaries)

            assertEachWordOnce(words, pieces)
            assertEquals(listOf(Piece(0, 0), Piece(1, 1), Piece(2, 1)), pieces)
        }
    }

    /** 라틴 글 — 경계가 빈칸 뒤에 떨어져 자를 단어가 없다. */
    @Test
    fun latinTextSplitsBetweenWords() {
        val words = listOf("Hello", "world.", "This", "is", "fine.").map { word(it) }
        val text = SentenceSplit.joinedText(words)
        assertEquals("Hello world. This is fine.", text)

        val pieces = SentenceSplit.assign(words, listOf(0, 13, text.length))

        assertEachWordOnce(words, pieces)
        assertEquals(listOf("Hello world.", "This is fine."), sentences(words, pieces))
        assertEquals(words.size, pieces.size)
    }

    /** PP-OCRv5 아랍어가 `.` 뒤 띄어쓰기를 놓쳤다 — ICU 는 `.` 바로 뒤를 경계로 준다. 단어를 그 자리에서 자른다. */
    @Test
    fun arabicWordWithMissingSpaceAfterFullStopIsCut() {
        val words = listOf(word("هذا"), word("نص.وهذا"), word("آخر."))
        val text = SentenceSplit.joinedText(words)
        val boundaries = listOf(0, text.indexOf('.') + 1, text.length)

        val pieces = SentenceSplit.assign(words, boundaries)

        assertEachWordOnce(words, pieces)
        assertEquals(listOf("هذا نص.", "وهذا آخر."), sentences(words, pieces))
        assertEquals(listOf(Piece(1, 0, 0..2, 0..2), Piece(1, 1, 3..6, 3..6)), pieces.filter { it.word == 1 })
    }

    /** 문장이 하나면 모든 단어가 단어째 그 문장으로. */
    @Test
    fun singleSentenceParagraphKeepsEveryWordWhole() {
        val words = listOf("One", "sentence", "only").map { word(it) }
        val text = SentenceSplit.joinedText(words)

        val pieces = SentenceSplit.assign(words, listOf(0, text.length))

        assertEquals(words.indices.map { Piece(it, 0) }, pieces)
    }

    /** 글자 하나가 여러 코드 단위여도(결합 문자, 서로게이트) 글자를 이으면 단어 글이 되면 자를 수 있다. */
    @Test
    fun multiUnitCharactersAreCutAtCharacterStarts() {
        val words = listOf(WordText("abc.d", listOf("ab", "c.", "d")))

        val pieces = SentenceSplit.assign(words, listOf(0, 4, 5))

        assertEachWordOnce(words, pieces)
        assertEquals(listOf(Piece(0, 0, 0..1, 0..3), Piece(0, 1, 2..2, 4..4)), pieces)
    }

    /** 경계가 여럿 든 단어는 여러 조각으로 — 조각마다 제 문장. */
    @Test
    fun wordSpanningThreeSentencesIsCutTwice() {
        val words = listOf(word("一。二。三"))
        val pieces = SentenceSplit.assign(words, listOf(0, 2, 4, 5))

        assertEachWordOnce(words, pieces)
        assertEquals(listOf("一。", "二。", "三"), sentences(words, pieces))
    }
}
