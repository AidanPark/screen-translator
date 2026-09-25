package com.galaxy.airviewdictionary.data.local.vision.ocr

import com.google.mlkit.vision.text.Text

/**
 * ML Kit 의 인식 결과를 엔진 중립 타입으로 옮긴다. 앱에서 ML Kit 결과 타입을 아는 유일한 곳이다.
 *
 * 필드를 그대로 옮긴다 — 걸러 내거나 고치지 않는다. 그래야 옮기기 전후로 조립 결과가 바이트 단위로 같다.
 * ML Kit 은 덩어리·단어·글자 상자와 줄 신뢰도를 다 주므로 [OcrText] 계약(3~5)은 저절로 지켜진다.
 */
fun Text.toOcrText(): OcrText = OcrText(
    text = text,
    blocks = textBlocks.map { block ->
        OcrBlock(
            boundingBox = block.boundingBox,
            lines = block.lines.map { line ->
                OcrLine(
                    boundingBox = line.boundingBox,
                    text = line.text,
                    confidence = line.confidence,
                    words = line.elements.map { element ->
                        OcrWord(
                            boundingBox = element.boundingBox,
                            text = element.text,
                            symbols = element.symbols.map { symbol -> OcrSymbol(symbol.boundingBox, symbol.text, symbol.confidence) },
                        )
                    },
                )
            },
        )
    },
)
