package com.galaxy.airviewdictionary.data.local.vision.model

import androidx.core.graphics.get
import android.graphics.Rect
import com.galaxy.airviewdictionary.data.local.vision.WritingDirection
import com.galaxy.airviewdictionary.extensions._unionWith


/**
 * 한 줄 짜리 텍스트
 */
data class Line(
    val words: MutableList<Word>,
    override val writingDirection: WritingDirection
) : VisionSingleLineText {

    private var boundingBoxCache: Rect? = null
    private var wordsHashCodeCache: Int? = null

    override val boundingBox: Rect
        get() {
            val currentWordsHashCode = words.hashCode()
            if (boundingBoxCache == null || wordsHashCodeCache != currentWordsHashCode) {
                boundingBoxCache = if (words.isEmpty()) {
                    Rect()
                } else {
                    words.map { it.boundingBox }.reduce { acc, rect -> acc._unionWith(rect) }
                }
                wordsHashCodeCache = currentWordsHashCode
            }
            return boundingBoxCache!!
        }

    override val representation: String
        get() = words.joinToString(separator = " ") { it.representation }

    /**
     * Line 에 [Word] 를 삽입
     */
    fun addWord(newWord: Word) {
        var left = 0
        var right = words.size

        when (writingDirection) {
            WritingDirection.LTR -> { // LTR and default case
                while (left < right) {
                    val mid = (left + right) / 2
                    if (words[mid].boundingBox.left < newWord.boundingBox.left) {
                        left = mid + 1
                    } else {
                        right = mid
                    }
                }
            }

            WritingDirection.RTL -> {
                while (left < right) {
                    val mid = (left + right) / 2
                    if (words[mid].boundingBox.left > newWord.boundingBox.left) {
                        left = mid + 1
                    } else {
                        right = mid
                    }
                }
            }

            else -> { // TTB_LTR, TTB_RTL
                while (left < right) {
                    val mid = (left + right) / 2
                    if (words[mid].boundingBox.top < newWord.boundingBox.top) {
                        left = mid + 1
                    } else {
                        right = mid
                    }
                }
            }

        }
        // 삽입 위치에 새로운 Word를 추가
        words.add(left, newWord)
    }
}
