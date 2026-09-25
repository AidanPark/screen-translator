package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import android.graphics.Bitmap
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrBlock
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrText
import com.galaxy.airviewdictionary.data.local.vision.ocr.ReadingOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import timber.log.Timber
import java.util.IdentityHashMap

/**
 * 문자권별 PP-OCRv5 엔진. 언어 → 엔진 표는 사전이 그 언어의 글자를 다 갖는지로 정했다(`.docs/vision-engine-design.md` §12).
 * 세르비아어·카자흐어 등 나머지 키릴 문자는 동슬라브 사전에 없어 별도 `cyrillic` 모델이 필요하다 — 아직 없다.
 */
class PaddleKits(files: PaddleModelFiles) {

    private val sessions = PaddleSessions(files)
    private val detector = PaddleDetector(sessions)

    private val arabic = PaddleOcrVisionKit("PADDLE_ARABIC", detector, PaddleRecognizer(sessions, "arabic_rec.onnx", "arabic_dict.txt", visualOrder = true))
    private val eastSlavic = PaddleOcrVisionKit("PADDLE_ESLAV", detector, PaddleRecognizer(sessions, "eslav_rec.onnx", "eslav_dict.txt", visualOrder = false))
    private val thai = PaddleOcrVisionKit("PADDLE_THAI", detector, PaddleRecognizer(sessions, "th_rec.onnx", "th_dict.txt", visualOrder = false))

    /** 엔진마다 맡는 언어(첫째가 대표)와 제 문자권 글자. */
    private class Script(val languages: List<String>, val owns: (Char) -> Boolean)

    private val scripts: Map<PaddleOcrVisionKit, Script> = mapOf(
        arabic to Script(ARABIC_LANGUAGES) { c ->
            c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' || c in '\u08A0'..'\u08FF' || c in '\uFB50'..'\uFDFF' || c in '\uFE70'..'\uFEFF'
        },
        eastSlavic to Script(EAST_SLAVIC_LANGUAGES) { c -> c in '\u0400'..'\u052F' },
        thai to Script(THAI_LANGUAGES) { c -> c in '\u0E00'..'\u0E7F' },
    )

    /** [languageCode] 를 맡을 엔진. 그 문자권이 아니거나, 모델이 아직 없거나, 스위치로 꺼 두었으면 null(§19). */
    fun kitFor(languageCode: String): PaddleOcrVisionKit? {
        if (!PaddleSwitch.enabled) return null
        val base = languageCode.substringBefore('-')
        return scripts.entries.firstOrNull { base in it.value.languages }?.key?.takeIf { it.isReady() }
    }

    /**
     * auto 의 PP-OCRv5 후보 — 확인 라운드로 채택한 규칙(§13.4). 한 번 검출해 가장 넓은 [SAMPLE_LINES] 줄을 세 모델이 각각 읽고,
     * 읽은 글이 **그 모델의 문자권**이면(글자 중 그 문자 비율 ≥ 0.5, 그 문자 10자 이상, 평균 신뢰도 ≥ 0.4) 인정한다. 인정된 후보만,
     * 평균 신뢰도가 높은 순으로 돌려준다. 모델이 아직 없거나 스위치로 꺼 두었으면 빈 목록.
     *
     * 후보의 [AutoCandidate.ocr] 는 검출한 화면 전체다 — 표본으로 읽은 줄만 읽힌 채이고 나머지는 읽지 않았다. 이긴 후보는 이것으로
     * 검출만 된 화면을 만들고, 표본 줄은 다시 읽지 않는다.
     */
    suspend fun autoCandidates(screen: Bitmap): List<AutoCandidate> = coroutineScope {
        if (!PaddleSwitch.autoEnabled) return@coroutineScope emptyList()
        val ready = scripts.keys.filter { it.isReady() }
        if (ready.isEmpty()) return@coroutineScope emptyList()
        val detected = ready.first().detect(screen)
        val sample = detected.lines.sortedByDescending { it.boundingBox?.width() ?: 0 }.take(SAMPLE_LINES)
        if (sample.isEmpty()) return@coroutineScope emptyList()
        ready.map { kit ->
            async {
                // 이 모델의 세션을 처음 만들다 실패하면 이 엔진만 빠진다 — 이미 꺼진 것으로 기록됐다(§22)
                val read = try {
                    kit.recognize(screen, sample)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag("PaddleKits").e("${kit.name} 표본 읽기 실패: ${e.message}")
                    return@async null
                }
                val script = scripts.getValue(kit)
                val letters = read.flatMap { line -> line.text.filter { it.isLetter() }.toList() }
                val own = letters.count(script.owns)
                val mean = read.map { (it.confidence ?: 0f).toDouble() }.average()
                val accepted = letters.isNotEmpty() && own.toDouble() / letters.size >= MIN_OWN_FRACTION &&
                    own >= MIN_OWN_LETTERS && mean >= MIN_MEAN_CONFIDENCE
                if (!accepted) return@async null
                val readOf = IdentityHashMap<OcrLine, OcrLine>().apply { sample.forEachIndexed { i, line -> put(line, read[i]) } }
                val blocks = detected.blocks.map { b -> OcrBlock(b.boundingBox, b.lines.map { readOf[it] ?: it }) }
                // 표본이 모든 줄을 읽었으면(줄이 SAMPLE_LINES 이하) 다 읽힌 화면이라 남은 줄 읽기를 건너뛴다 — 글 전체도 여기서
                // 채워야 한다. 비워 두면 글 전체를 쓰는 영역 선택·고정 영역이 빈 글을 번역한다(§17.1). 잇는 순서는 남은 줄을
                // 읽을 때(`recognizeAll`)와 같은 읽는 순서다(§23).
                val lines = blocks.flatMap { it.lines }
                val text = if (lines.all { it.words != null }) ReadingOrder.text(lines) else detected.text
                val ocr = OcrText(text, blocks)
                AutoCandidate(kit, ocr, read.joinToString("\n") { it.text }, mean)
            }
        }.awaitAll().filterNotNull().sortedByDescending { it.meanConfidence }
    }

    /** 이긴 후보의 번역 소스 언어. 표본 글의 감지 언어가 그 엔진의 언어면 그것, 아니면 엔진의 대표 언어. */
    fun languageOf(candidate: AutoCandidate, identified: String): String {
        val languages = scripts.getValue(candidate.kit).languages
        return identified.substringBefore('-').takeIf { it in languages } ?: languages.first()
    }

    class AutoCandidate(val kit: PaddleOcrVisionKit, val ocr: OcrText, val sampleText: String, val meanConfidence: Double)

    companion object {
        private val ARABIC_LANGUAGES = listOf("ar", "fa", "ur", "ps", "ckb", "ug", "sd")
        private val EAST_SLAVIC_LANGUAGES = listOf("ru", "uk", "be", "bg")
        private val THAI_LANGUAGES = listOf("th")

        /** PP-OCRv5 엔진이 맡는 언어 전부. 모델 팩 준비·스위치와 무관하다. */
        val LANGUAGES: Set<String> = (ARABIC_LANGUAGES + EAST_SLAVIC_LANGUAGES + THAI_LANGUAGES).toSet()

        private const val SAMPLE_LINES = 4
        private const val MIN_OWN_FRACTION = 0.5
        private const val MIN_OWN_LETTERS = 10
        private const val MIN_MEAN_CONFIDENCE = 0.4
    }
}
