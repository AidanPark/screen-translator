package com.galaxy.airviewdictionary.data.local.vision.model

import com.galaxy.airviewdictionary.data.local.vision.WritingDirection
import com.galaxy.airviewdictionary.extensions._divideByLarger
import kotlin.math.abs

/**
 * 한 줄짜리 VisionText
 * [Char], [Word], [Line] 의 부모 클래스
 */
interface VisionSingleLineText : VisionText {

    /**
     * 시작위치
     */
    val startPosition: Int
        get() = when (writingDirection) {
            WritingDirection.LTR -> boundingBox.left
            WritingDirection.RTL -> boundingBox.right
            WritingDirection.TTB_LTR, WritingDirection.TTB_RTL -> boundingBox.top
        }

    /**
     * 폰트 높이
     */
    override val fontHeight: Double
        get() = when (writingDirection) {
            WritingDirection.LTR, WritingDirection.RTL -> boundingBox.height().toDouble()
            WritingDirection.TTB_LTR, WritingDirection.TTB_RTL -> boundingBox.width().toDouble()
        }

    /**
     * 다른 VisionSingleLineText 와의 평균 폰트 높이
     */
    fun getAverageFontHeight(other: VisionSingleLineText): Double {
        return when (writingDirection) {
            WritingDirection.LTR, WritingDirection.RTL -> (height + other.height).toDouble() / 2
            WritingDirection.TTB_LTR, WritingDirection.TTB_RTL -> (width + other.width).toDouble() / 2
        }
    }

    /**
     * 다른 VisionSingleLineText 와의 평균 폰트 높이 유사율
     */
    fun getFontHeightSimilarityRatio(other: VisionSingleLineText): Double {
        return when (writingDirection) {
            WritingDirection.LTR, WritingDirection.RTL -> height.toDouble()._divideByLarger(other.height.toDouble())
            WritingDirection.TTB_LTR, WritingDirection.TTB_RTL -> width.toDouble()._divideByLarger(other.width.toDouble())
        }
    }

    /**
     * 다른 VisionSingleLineText 와의 행 중심축 거리
     */
    fun getAxisDistance(other: VisionSingleLineText): Int {
        return when (writingDirection) {
            WritingDirection.LTR, WritingDirection.RTL -> abs(boundingBox.centerY() - other.boundingBox.centerY())
            WritingDirection.TTB_LTR, WritingDirection.TTB_RTL -> abs(boundingBox.centerX() - other.boundingBox.centerX())
        }
    }

    /**
     * 다른 VisionSingleLineText 와의 행방향 중심축 유사율
     */
    fun getAxisSimilarityRatio(other: VisionSingleLineText): Double {
        val averageFontHeight: Double = getAverageFontHeight(other)
        val axisDistance = getAxisDistance(other)
        return averageFontHeight / (averageFontHeight + axisDistance)
    }

    companion object {
        /**
         * 읽는 순서로 정렬한다. 조립기의 정렬은 전부 이것을 거친다.
         *
         * 가로쓰기는 [getComparator] 그대로다. 세로쓰기는 비교 함수 하나로 정할 수 없다 — ML Kit 이
         * 한 열을 여러 조각으로 끊으면 조각마다 오른쪽 끝이 1~3px 씩 달라, 오른쪽 끝으로 정렬하면
         * 아래 조각이 위 조각보다 앞에 온다(실측: zh106 한 열의 세 조각이 위 좌표 11 → 867 → 691
         * 순으로 이어져 문단 안 글이 뒤섞였다). 세로 분기에는 가로 경로처럼 다시 정렬하는 후처리가
         * 없어 그대로 출력된다. 허용 오차를 비교 함수에 넣으면 전이성이 깨져 정렬이 예외를 낼 수
         * 있으므로, 가로로 겹치는 것끼리 먼저 열로 묶고, 열은 읽는 방향으로, 열 안은 위에서 아래로 놓는다.
         */
        @JvmStatic
        fun <T : VisionSingleLineText> sortedForReading(
            items: Collection<T>,
            writingDirection: WritingDirection,
        ): List<T> {
            val rightToLeft = writingDirection == WritingDirection.TTB_RTL
            if (!rightToLeft && writingDirection != WritingDirection.TTB_LTR) {
                return items.sortedWith(getComparator(writingDirection))
            }
            val sweep = if (rightToLeft) items.sortedByDescending { it.boundingBox.right }
            else items.sortedBy { it.boundingBox.left }
            val columns = mutableListOf<MutableList<T>>()
            var left = 0
            var right = 0
            for (item in sweep) {
                val box = item.boundingBox
                val overlap = minOf(box.right, right) - maxOf(box.left, left)
                val narrower = minOf(box.width(), right - left)
                if (columns.isNotEmpty() && narrower > 0 && overlap > narrower * 0.5) {
                    columns.last().add(item)
                    left = minOf(left, box.left)
                    right = maxOf(right, box.right)
                } else {
                    columns.add(mutableListOf(item))
                    left = box.left
                    right = box.right
                }
            }
            return columns.flatMap { column -> column.sortedBy { it.boundingBox.top } }
        }

        @JvmStatic
        fun getComparator(writingDirection: WritingDirection): Comparator<VisionSingleLineText> {
            return when (writingDirection) {
                WritingDirection.LTR -> {
                    Comparator { singleLineText1, singleLineText2 ->
                        val topComparison = singleLineText1.boundingBox.top.compareTo(singleLineText2.boundingBox.top)
                        if (topComparison != 0) topComparison else singleLineText1.boundingBox.left.compareTo(singleLineText2.boundingBox.left)
                    }
                }

                WritingDirection.RTL -> {
                    Comparator { singleLineText1, singleLineText2 ->
                        val topComparison = singleLineText1.boundingBox.top.compareTo(singleLineText2.boundingBox.top)
                        if (topComparison != 0) topComparison else singleLineText2.boundingBox.right.compareTo(singleLineText1.boundingBox.right)
                    }
                }

                WritingDirection.TTB_LTR -> {
                    Comparator { singleLineText1, singleLineText2 ->
                        val leftComparison = singleLineText1.boundingBox.left.compareTo(singleLineText2.boundingBox.left)
                        if (leftComparison != 0) leftComparison else singleLineText1.boundingBox.top.compareTo(singleLineText2.boundingBox.top)
                    }
                }

                WritingDirection.TTB_RTL -> {
                    Comparator { singleLineText1, singleLineText2 ->
                        val rightComparison = singleLineText2.boundingBox.right.compareTo(singleLineText1.boundingBox.right)
                        if (rightComparison != 0) rightComparison else singleLineText1.boundingBox.top.compareTo(singleLineText2.boundingBox.top)
                    }
                }
            }
        }
    }
}
