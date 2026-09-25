package com.galaxy.airviewdictionary.data.local.vision.ocr

import android.graphics.Rect

/**
 * 인식 엔진이 준 결과 한 화면. 조립 전의 날것이다 — 조립 결과는 `model` 의 [com.galaxy.airviewdictionary.data.local.vision.model.Word]·
 * `Line`·`Paragraph` 다.
 *
 * 앱 안쪽은 이 타입만 안다. 엔진(ML Kit, PaddleOCR, …)은 자기 결과를 이 모양으로 옮기고, 아래 계약을 지킨다
 * (`.docs/vision-engine-design.md` §8).
 *
 * 1. 좌표는 화면 좌표다. 잘라 읽기·축소에서 생긴 좌표 복원은 엔진 안에서 끝낸다
 * 2. 글은 논리 순서(읽는 순서)다. 단어·줄 목록의 순서는 상관없다 — 조립기가 기하로 다시 정렬한다
 * 3. 덩어리가 없는 엔진은 줄마다 덩어리 하나로 준다
 * 4. 줄 신뢰도는 없어도 된다(null)
 * 5. 글자(기호) 상자는 엔진이 채운다. 주지 않는 엔진은 단어 상자를 글자 수로 나눠 채운다 — 글자 상자가 하나도 없는
 *    단어는 조립 단계에서 버려진다
 *
 * 상자가 null 일 수 있는 것은 ML Kit 결과를 손실 없이 옮기기 위해서다.
 */
data class OcrText(
    /** 화면 전체 글. 엔진이 준 그대로다. */
    val text: String,
    val blocks: List<OcrBlock>,
) {
    val lines: List<OcrLine> get() = blocks.flatMap { it.lines }

    /** 모든 줄이 읽혔는지. 검출만 된 줄이 하나라도 있으면 false 다. */
    val isFullyRead: Boolean get() = blocks.all { block -> block.lines.all { it.words != null } }

    /** 덩어리 상자의 합집합. 상자가 하나도 없으면 null. */
    fun blockBoundingBoxUnion(): Rect? =
        blocks.mapNotNull { it.boundingBox }
            .fold(null as Rect?) { union, box -> union?.apply { union(box) } ?: Rect(box) }

    /** 덩어리 상자 높이의 평균. 상자가 없는 덩어리는 0 으로 센다. 덩어리가 없으면 0. */
    fun averageBlockHeight(): Double {
        if (blocks.isEmpty()) return 0.0
        return blocks.sumOf { it.boundingBox?.height()?.toDouble() ?: 0.0 } / blocks.size
    }
}

/** 덩어리. ML Kit 의 TextBlock 이다. */
data class OcrBlock(
    val boundingBox: Rect?,
    val lines: List<OcrLine>,
)

data class OcrLine(
    val boundingBox: Rect?,
    /** 줄 글. 검출만 된 줄은 "" 다. */
    val text: String,
    /** 인식 신뢰도. 주지 않는 엔진은 null — auto 에서 인식기 결과를 고를 때 0 으로 본다. */
    val confidence: Float?,
    /** null 이면 검출만 되고 아직 읽지 않은 줄이다([com.galaxy.airviewdictionary.data.local.vision.kit.VisionKit.recognize]). */
    val words: List<OcrWord>?,
)

data class OcrWord(
    val boundingBox: Rect?,
    val text: String,
    val symbols: List<OcrSymbol>,
)

/** 글자 하나. */
data class OcrSymbol(
    val boundingBox: Rect?,
    val text: String,
    /** 글자 인식 신뢰도(엔진의 날것). 엔진끼리 잴 수 있는 값이 아니다 — 비교하려면 보정이 필요하다(§11). 주지 않으면 null. */
    val confidence: Float? = null,
)
