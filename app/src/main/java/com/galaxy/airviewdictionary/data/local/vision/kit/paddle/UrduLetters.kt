package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText

/**
 * 우르두어 글자 보정(`.docs/vision-engine-design.md` §15.1). PP-OCRv5 에는 우르두어 전용 모델이 없어 아랍 문자 모델이 읽는데, 우르두어의 `ہ`(헤 골)·`ھ`(도
 * 차슈미)를 아랍어 `ه` 로 자주 낸다. 우르두어에는 아랍어 `ه` 가 거의 쓰이지 않으므로 되돌린다 — 같은 낱말 안에서 뒤에 글자가 이어지고 앞 글자가 유기음을 만드는
 * 자음이면 `ھ`, 아니면 `ہ`. 글자 하나를 글자 하나로 바꾸므로 단어·글자 상자는 그대로다.
 *
 * 우르두어 표본(34면)에서 문자 정확도 tune 78.8 → 79.9%, holdout 79.3 → 80.4%. 다른 흔한 오류(`ے` 빠짐, `ہ`→`ب`, `ں`→`ن`)는 사전 없이 되돌릴 수 없다.
 */
object UrduLetters {

    private const val ARABIC_HEH = 'ه'
    private const val HEH_GOAL = 'ہ'
    private const val HEH_DOACHASHMEE = 'ھ'

    /** ب پ ت ٹ ج چ د ڈ ک گ ر ڑ ل م ن — 뒤에 ھ 가 붙어 유기음이 되는 자음. */
    private val ASPIRABLE = setOf(
        'ب', 'پ', 'ت', 'ٹ', 'ج', 'چ', 'د', 'ڈ',
        'ک', 'گ', 'ر', 'ڑ', 'ل', 'م', 'ن',
    )

    fun fix(text: String): String {
        if (ARABIC_HEH !in text) return text
        val out = text.toCharArray()
        for (i in out.indices) {
            if (out[i] != ARABIC_HEH) continue
            val prev = if (i > 0) text[i - 1] else ' '
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            out[i] = if (prev in ASPIRABLE && next.isLetter()) HEH_DOACHASHMEE else HEH_GOAL
        }
        return String(out)
    }

    /** 읽힌 줄의 글·단어·글자를 모두 보정한다. 읽지 않은 줄은 그대로. */
    fun fix(line: OcrLine): OcrLine {
        val words = line.words ?: return line
        return line.copy(
            text = fix(line.text),
            words = words.map { word ->
                val fixed = fix(word.text)
                // 글자 상자는 단어 글과 한 글자씩 짝이 맞는다(PaddleRecognizer) — 짝이 맞을 때만 글자도 바꾼다
                val symbols = if (fixed.length == word.symbols.size) {
                    word.symbols.mapIndexed { i, s -> s.copy(text = fixed[i].toString()) }
                } else word.symbols
                word.copy(text = fixed, symbols = symbols)
            },
        )
    }

    fun fix(ocr: OcrText): OcrText =
        OcrText(fix(ocr.text), ocr.blocks.map { block -> block.copy(lines = block.lines.map { fix(it) }) })
}
