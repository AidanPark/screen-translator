package com.galaxy.airviewdictionary.data.local.vision.model

import android.graphics.Rect
import com.galaxy.airviewdictionary.data.local.vision.WritingDirection
import android.icu.text.BreakIterator
import com.galaxy.airviewdictionary.extensions._unionWith
import java.util.Locale


/**
 * 어러 개의 [Line] 으로 이루어진 문단
 */
data class Paragraph(
    val lines: MutableList<Line>,
    override val writingDirection: WritingDirection
) : VisionText {

    private var boundingBoxCache: Rect? = null
    private var linesHashCodeCache: Int? = null

    override val boundingBox: Rect
        get() {
            val currentWordsHashCode = lines.hashCode()
            if (boundingBoxCache == null || linesHashCodeCache != currentWordsHashCode) {
                boundingBoxCache = if (lines.isEmpty()) {
                    Rect()
                } else {
                    lines.map { it.boundingBox }.reduce { acc, rect -> acc._unionWith(rect) }
                }
                linesHashCodeCache = currentWordsHashCode
            }
            return boundingBoxCache!!
        }

    override val representation: String
        get() = lines.joinToString(separator = " ") { it.representation }

    override val fontHeight: Double
        get() = lines.map { it.fontHeight }.average()

    /**
     * 복수의 Line 들이 하나의 행을 이루는 것이 있는지의 여부
     */
    var hasParallelLines = false

    /**
     * 이 문단 글의 언어. 문장 경계를 로케일 규칙으로 찾는 데 쓴다.
     * 생성 시점에는 모르므로 조립이 끝난 뒤 [VisionRepository] 가 채운다.
     */
    var languageCode: String? = null

    private var _sentences: List<Sentence>? = null
    val sentences: List<Sentence>
        get() {
            if (_sentences == null) {
                _sentences = toSentences()
            }
            return _sentences!!
        }

    /**
     * lines의 모든 Line 높이의 평균을 반환
     */
    fun averageLineHeight(): Double {
        return if (lines.isEmpty()) {
            0.0
        } else {
            lines.map { it.fontHeight }.average()
        }
    }

    /**
     * 모든 Line이 줄바꿈 방향에서 겹치는지 확인 (모든 Line 이 하나의 행을 이루는지 확인)
     */
    fun areAllInLine(): Boolean {
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                if (!lines[i].isLineReturnDirectionOverlaps(lines[j])) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * 문단의 글을 문장으로 자른다.
     *
     * 경계 판정은 ICU 에 맡긴다. 직접 문장부호 집합을 들고 비교하면 언어마다 빠지는 것이
     * 생기고(우르두어 U+06D4 가 빠져 있었다), 약어의 마침표나 소수점을 문장 끝으로 오해한다.
     * ICU 는 로케일 규칙으로 그것들을 구분하고, 태국어처럼 문장부호가 없는 글도 다룬다.
     *
     * 글자 범위를 단어로 되돌리는 규칙은 [SentenceSplit] 에 있다 — 단어마다 한 문장에만 가고, 경계가 단어 안에 떨어지면
     * 글자 상자로 그 자리에서 잘라 조각마다 제 문장으로 간다.
     */
    private fun toSentences(): List<Sentence> {
        val ordered = lines.flatMapIndexed { lineIndex, line -> line.words.map { lineIndex to it } }
        if (ordered.isEmpty()) return emptyList()

        val texts = ordered.map { (_, word) ->
            SentenceSplit.WordText(word.representation, word.chars.map { it.representation })
        }
        val boundaries = sentenceBoundaries(SentenceSplit.joinedText(texts))
        if (boundaries.size < 3) {
            return listOf(Sentence(lines.toMutableList(), writingDirection, fontHeight))
        }

        // 문장 번호 → (원래 줄 번호 → 그 줄에서 이 문장에 든 단어). 조각은 단어 순서대로 오므로 줄 안의 순서도 그대로다.
        val bySentence = sortedMapOf<Int, LinkedHashMap<Int, MutableList<Word>>>()
        for (piece in SentenceSplit.assign(texts, boundaries)) {
            val (lineIndex, word) = ordered[piece.word]
            val part = if (piece.chars != null && piece.text != null) word.piece(piece.chars, piece.text) else word
            bySentence.getOrPut(piece.sentence) { LinkedHashMap() }.getOrPut(lineIndex) { mutableListOf() }.add(part)
        }
        val sentences = bySentence.values.map { byLine ->
            Sentence(byLine.values.map { Line(it, writingDirection) }.toMutableList(), writingDirection, fontHeight)
        }
        return sentences.ifEmpty {
            listOf(Sentence(lines.toMutableList(), writingDirection, fontHeight))
        }
    }

    /**
     * 단어의 한 조각. 쓰기 방향의 범위는 그 글자들의 상자로, 줄 높이 방향은 단어 상자 그대로 둔다 — 조각이 든 문장의 다각형이
     * 줄 높이를 잃지 않게. 글자 상자가 단어 밖으로 나가 있으면 단어 안으로 자른다.
     */
    private fun Word.piece(charRange: IntRange, textRange: IntRange): Word {
        val part = chars.subList(charRange.first, charRange.last + 1)
        val box = when (writingDirection) {
            WritingDirection.LTR, WritingDirection.RTL -> {
                val from = part.minOf { it.boundingBox.left }
                val to = part.maxOf { it.boundingBox.right }
                val left = from.coerceAtLeast(boundingBox.left)
                val right = to.coerceAtMost(boundingBox.right)
                if (left < right) Rect(left, boundingBox.top, right, boundingBox.bottom)
                else Rect(from, boundingBox.top, to, boundingBox.bottom)
            }
            WritingDirection.TTB_LTR, WritingDirection.TTB_RTL -> {
                val from = part.minOf { it.boundingBox.top }
                val to = part.maxOf { it.boundingBox.bottom }
                val top = from.coerceAtLeast(boundingBox.top)
                val bottom = to.coerceAtMost(boundingBox.bottom)
                if (top < bottom) Rect(boundingBox.left, top, boundingBox.right, bottom)
                else Rect(boundingBox.left, from, boundingBox.right, to)
            }
        }
        return Word(box, representation.substring(textRange.first, textRange.last + 1), writingDirection, part, fontHeight)
    }

    /** ICU 가 찾은 문장 경계(글자 인덱스). 실패하면 문단 전체를 한 문장으로 본다. */
    private fun sentenceBoundaries(text: String): List<Int> {
        val locale = languageCode
            ?.takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) && it != "und" }
            ?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() }
            ?: Locale.getDefault()
        return runCatching {
            val iterator = BreakIterator.getSentenceInstance(locale)
            iterator.setText(text)
            buildList {
                add(iterator.first())
                while (true) {
                    val next = iterator.next()
                    if (next == BreakIterator.DONE) break
                    add(next)
                }
            }
        }.getOrElse { listOf(0, text.length) }
    }

    override fun toString(): String {
        return "Paragraph(boundingBox=$boundingBox, representation='$representation', lines=${lines.joinToString(separator = "\n", prefix = "[", postfix = "]") { it.toString() }})"
    }
}
