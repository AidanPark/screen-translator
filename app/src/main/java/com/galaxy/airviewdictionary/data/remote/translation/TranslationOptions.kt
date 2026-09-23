package com.galaxy.airviewdictionary.data.remote.translation

import com.galaxy.airviewdictionary.data.local.capture.TargetCrop
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.R

/**
 * AI 번역 엔진의 문맥/스타일 옵션.
 *
 * 사용자 API 키로 동작하는 엔진에만 적용된다(Google/DeepL 은 프롬프트 개념이 없다).
 * 현재는 OpenAI 만 지원하며, 다른 AI 엔진으로 넓힐 때 그대로 재사용한다.
 */

/** 번역할 문장 주변 텍스트를 얼마나 함께 보낼지. */
enum class TranslationContextMode(val labelResourceId: Int) {
    /** 대상 문장만 보낸다. */
    OFF(R.string.translation_context_off),

    /** 대상이 속한 문단(주변 문장)까지 참고용으로 보낸다. 기본값. */
    NEARBY(R.string.translation_context_nearby),

    /** 화면에서 인식된 텍스트 전부를 참고용으로 보낸다. */
    SCREEN(R.string.translation_context_screen);

    companion object {
        val DEFAULT = NEARBY
        fun from(name: String?): TranslationContextMode =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** 원문에 얼마나 충실하게 옮길지. */
enum class TranslationStrength(val labelResourceId: Int, val promptClause: String) {
    LITERAL(
        labelResourceId = R.string.translation_strength_literal,
        promptClause = "Stay close to the source wording and structure.",
    ),
    NATURAL(
        labelResourceId = R.string.translation_strength_natural,
        promptClause = "Prefer wording that reads naturally to a native speaker, " +
                "while keeping the original meaning intact.",
    ),
    FREE(
        labelResourceId = R.string.translation_strength_free,
        promptClause = "Convey the intent idiomatically; rephrase freely when a literal " +
                "rendering would read awkwardly.",
    );

    companion object {
        val DEFAULT = LITERAL
        fun from(name: String?): TranslationStrength =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** 번역 대상의 분야. 용어 선택과 말투의 기준이 된다. */
enum class TranslationDomain(val labelResourceId: Int, val promptClause: String?) {
    GENERAL(R.string.translation_domain_general, null),
    GAME(
        R.string.translation_domain_game,
        "The text is from a video game UI or dialogue; keep game terminology and character voice.",
    ),
    COMIC(
        R.string.translation_domain_comic,
        "The text is from a comic or webtoon; keep the speech style of the speaker and casual dialogue.",
    ),
    TECH(
        R.string.translation_domain_tech,
        "The text is technical documentation; keep technical terms accurate and leave code, " +
                "identifiers and product names unchanged.",
    ),
    BUSINESS(
        R.string.translation_domain_business,
        "The text is business correspondence; keep a professional and courteous register.",
    );

    companion object {
        val DEFAULT = GENERAL
        fun from(name: String?): TranslationDomain =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * AI 번역 엔진들이 공유하는 시스템 프롬프트.
 *
 * 출력 형식 제약을 스타일 지시보다 **먼저** 둔다. 순서가 뒤바뀌면 분야/강도 문구가
 * "번역문만 출력" 규칙을 흔들어 설명문이 섞여 나온다.
 */
fun buildTranslationSystemPrompt(
    sourceLanguageName: String?,
    targetLanguageName: String,
    strength: TranslationStrength,
    domain: TranslationDomain,
    hasContext: Boolean,
): String {
    val fromClause = if (sourceLanguageName == null) {
        "Detect the source language and translate the user's text into $targetLanguageName."
    } else {
        "Translate the user's text from $sourceLanguageName into $targetLanguageName."
    }
    return buildString {
        append("You are a professional translation engine. ")
        append(fromClause)
        append(" Output ONLY the translated text — no quotes, no explanations, no notes, and no source text.")
        append(" Preserve the original meaning, tone, and line breaks.")
        append(" If the text is already in $targetLanguageName, return it unchanged.")
        append(" ")
        append(strength.promptClause)
        domain.promptClause?.let {
            append(" ")
            append(it)
        }
        if (hasContext) {
            append(
                " The user message contains a <context> block and a <text> block." +
                        " The <context> block is surrounding text from the same screen, provided only to" +
                        " resolve pronouns, omitted subjects and ambiguous words." +
                        " Translate ONLY the contents of the <text> block." +
                        " Never translate, quote or mention the <context> block, and do not output the tags."
            )
        }
    }
}

/** 문맥이 있으면 태그로 구분해 한 메시지에 담는다. */
fun buildTranslationUserMessage(sourceText: String, contextText: String?): String {
    if (contextText.isNullOrBlank()) return sourceText
    return "<context>\n$contextText\n</context>\n<text>\n$sourceText\n</text>"
}

/**
 * 이미지에서 번역 대상의 범위를 모델에게 맡기는 지시.
 *
 * 앱이 크롭으로 범위를 정하지 않는 이유: 크롭은 OCR 박스를 기준으로 잡는데,
 * 아랍어처럼 ML Kit 에 인식기가 없는 문자에서는 그 박스가 통째로 틀린다.
 *
 * "문장"만으로는 부족하다 — 제목·버튼·목록·표 셀·말풍선은 문장부호가 없어서,
 * 마침표를 찾아 이웃 항목까지 삼켜버린다(7줄 크롭에서 엉뚱한 줄 번역 — 2026-09-21 실측).
 * 그래서 마커 아래 "줄"을 기점으로 잡고, 이어붙일 조건을 명시한다.
 * 마커는 반경 18px 원이라 768px 이미지에서 두 줄에 걸치므로 중심 기준도 못박는다.
 */
private fun TextDetectMode.imageTargetClause(): String = when (this) {
    TextDetectMode.WORD ->
        "Your target is the single word under the centre of the circle." +
                " If the circle touches two words, use the one closer to its centre."

    TextDetectMode.SENTENCE ->
        "Find the line of text under the centre of the circle." +
                " Your target is the sentence that line belongs to: extend it to adjacent lines only" +
                " while they clearly continue the same sentence in the same block — same size and style," +
                " no sentence-ending punctuation in between, no blank line, no new bullet or number." +
                " A heading, button label, menu item, list row, table cell, caption or chat message is a" +
                " complete unit by itself even without a full stop — never join it with neighbouring items." +
                " If the circle touches two lines, use the one closer to its centre."

    TextDetectMode.PARAGRAPH ->
        "Find the line of text under the centre of the circle." +
                " Your target is the whole paragraph or block that line belongs to, and nothing outside it." +
                " A heading, list, table cell or chat message is a block by itself —" +
                " never join it with neighbouring blocks."

    // 사용자가 직접 그린 영역. 가리킬 한 점이 없으니 마커도 없고, 영역 전체가 대상이다.
    TextDetectMode.SELECT ->
        "Your target is all of the text in the image, in its natural reading order." +
                " The region was drawn by hand, so a line can be clipped at an edge:" +
                " use a line only if you can read the whole of it, and leave out a" +
                " partial line at the top or bottom instead of guessing what it said." +
                " Treat the selection as one passage and keep its wording and order."

    else -> "Your target is the text under the centre of the circle."
}

/**
 * 이 모드가 표적을 마커로 가리키는가. SELECT 는 영역 자체가 대상이라 마커를 그리지 않는다.
 */
private val TextDetectMode.usesMarker: Boolean
    get() = this != TextDetectMode.SELECT

/**
 * 이미지를 직접 읽는 번역의 시스템 프롬프트.
 *
 * 형식은 줄 접두사(LANG/SRC/DST)로 받고 [parseImageTranslation] 이 푼다.
 * responseSchema(구조화 출력)는 이미지와 함께 쓰면 20초 타임아웃을 넘겼다(2026-09-21 실측).
 *
 * 언어 이름은 반드시 영어로 넘겨야 한다 — [Language.displayName] 은 기기 로케일을 따라
 * 한국 기기에서 "한국어", 일본 기기에서 "韓国語" 를 돌려주므로 프롬프트가 기기마다 달라진다.
 */
fun buildImageTranslationSystemPrompt(
    sourceLanguageName: String?,
    targetLanguageName: String,
    strength: TranslationStrength,
    domain: TranslationDomain,
    detectMode: TextDetectMode,
): String {
    val isAutoDetect = sourceLanguageName == null
    return buildString {
        append("You are a translation engine reading a cropped phone screenshot.")
        if (detectMode.usesMarker) {
            append(" The ${TargetCrop.MARKER_DESCRIPTION} is an overlay drawn by the app,")
            append(" not part of the content.")
        }
        append(" ")
        append(detectMode.imageTargetClause())
        if (detectMode.usesMarker) {
            append(" Ignore all other text in the image.")
        }
        if (!isAutoDetect) {
            // 소스 언어는 사용자의 힌트일 뿐 사실이 아니다. 단언으로 쓰면 화면이 다른 언어일 때
            // 모델이 번역을 거부한다("이건 아랍어라 영어로 번역 못 합니다" — 2026-09-21 실측).
            append(" The text is expected to be in $sourceLanguageName;")
            append(" if it is actually in another language, translate it anyway.")
        }
        append(" Reply with exactly three lines, no markdown, no bold, no code fences,")
        append(" no quotes, nothing before or after them.")
        // 이름이 아니라 코드로 받는다. 이 값은 화면 라벨뿐 아니라 TTS 목소리 선택,
    // 답장(ReplyActivity), 애널리틱스가 쓰는 detectedLanguageCode 로 들어가기 때문이다.
    // 이름으로 받으면 그 세 기능에 넘길 코드가 없어진다.
        append(" Line 1 is \"LANG: \" followed by the ISO 639-1 two-letter code of the language")
        append(" of the text on line 2 — for example ar, th, ko — lower case and nothing else,")
        append(" no language name, no parentheses, no explanation.")
        append(" If unsure, give the code of the most likely language for that script.")
        append(" Line 2 is \"SRC: \" followed by the target text exactly as it appears,")
        append(" in its original script, with no corrections and nothing added.")
        // SRC 는 한 줄로만 파싱된다. 여러 줄·여러 문단이 대상일 수 있는 SELECT 에서는
        // 줄바꿈이 남으면 원문이 첫 줄에서 잘린다.
        append(" It must be a single line: join every line break in the target with one space,")
        append(" but use no space for scripts written without spaces such as Chinese,")
        append(" Japanese and Thai, and rejoin words that were hyphenated at a line break.")
        append(" Line 3 is \"DST: \" followed by that text translated into $targetLanguageName;")
        append(" if it is already in $targetLanguageName, repeat it unchanged.")
        append(" ")
        append(strength.promptClause)
        domain.promptClause?.let {
            append(" ")
            append(it)
        }
        // 형식 규칙을 마지막에 둔다. 앞에 두면 뒤따르는 문체 지시가 마지막 인상이 되어
        // 불확실할 때 형식을 버린다(설명문이 번역 결과로 표시됨 — 2026-09-21 실측).
        append(" Use this format for every reply without exception — even if the text is a single word,")
        append(" cut off, small, blurry, offensive, or not the language you expected.")
        append(" Read it as well as you can and give your best reading and translation;")
        append(" never apologise, refuse, explain, or add a note before or after the three lines.")
        append(
            if (detectMode.usesMarker) " There is almost always text under the circle."
            else " There is almost always text in the image."
        )
        append(" Only if there is truly none,")
        append(" reply with exactly these three lines: \"LANG: $NO_TEXT_SENTINEL\",")
        append(" \"SRC: $NO_TEXT_SENTINEL\", \"DST: $NO_TEXT_SENTINEL\".")
    }
}

/** 마커 아래에 글자가 없을 때 모델이 답하는 값. 앱은 이를 오류가 아니라 "글자 없음"으로 다룬다. */
const val NO_TEXT_SENTINEL = "-"

/**
 * 마커 아래에 읽을 글자가 없다고 모델이 답했다. 번역 실패가 아니다.
 *
 * 호출부는 이 경우 안내창을 띄우지 않고 조용히 끝내야 한다 —
 * OCR 경로도 가리킨 곳에 글자가 없으면 아무 창도 띄우지 않기 때문이다.
 */
class NoTextAtPointerException : Exception("no text under the marker")

/** 이미지 경로에서 모델이 돌려준 세 항목. */
data class ImageTranslation(
    val language: String = "",
    val source: String = "",
    val translation: String = "",
) {
    /** 마커 아래에 읽을 글자가 없다고 모델이 답한 경우. 오류가 아니다. */
    val isNoText: Boolean
        get() = translation.trim() == NO_TEXT_SENTINEL
}

/**
 * 모델이 답한 언어 코드를 앱이 쓰는 코드로 정규화한다. 알아볼 수 없으면 null.
 *
 * 모델은 지시를 어기고 "und", "unknown", 언어 이름, 설명문을 내놓을 수 있다.
 * 이 값은 쓰기 방향·TTS 목소리·답장으로 흘러가므로 통과시키기 전에 반드시 거른다.
 * (걸러서 null 이 되면 언어 미확정으로 다뤄져 기능이 조용히 깨지지 않는다)
 */
fun ImageTranslation.detectedLanguageCodeOrNull(): String? {
    val code = language.trim().lowercase()
        .substringBefore('-')
        .substringBefore('_')
    if (code.length != 2) return null // "und", "unknown", 언어 이름, 문장이 여기서 걸린다
    return code.takeIf { it in ISO_LANGUAGE_CODES }
}

private val ISO_LANGUAGE_CODES: Set<String> = java.util.Locale.getISOLanguages().toSet()

/**
 * 줄 접두사를 찾는다. 모델이 "**SRC:**" 처럼 마크다운을 붙이거나
 * 목록 기호를 앞에 다는 경우가 있어 장식은 흘려보낸다.
 * (그대로 startsWith 로 보면 접두사를 못 찾아 원문·언어가 조용히 빈칸이 된다)
 */
private val IMAGE_FIELD_REGEX =
    Regex("""^[\s>*#\-`]*(?:\*\*|__)?\s*(LANG|SRC|DST)\s*(?:\*\*|__)?\s*:\s*(?:\*\*|__)?\s*""")

/** 값 끝에 남은 마크다운 강조를 떼어낸다. */
private fun String.trimDecoration(): String =
    trim().removeSuffix("**").removeSuffix("__").removeSuffix("`").trim()

/**
 * 프롬프트가 지시한 줄 접두사 형식을 푼다.
 *
 *   LANG: 아랍어
 *   SRC: ...
 *   DST: ...
 *
 * DST 는 마지막 항목이라 여러 줄이어도 끝까지 가져간다.
 * 형식이 어긋나면 null 을 돌려준다. 호출부는 이를 번역 실패로 처리해야 한다.
 *
 * responseSchema(구조화 출력)를 쓰지 않는 이유: 이미지와 함께 쓰면 20초 타임아웃을
 * 넘겼다(2026-09-21 Gemini 실측).
 */
fun parseImageTranslation(text: String): ImageTranslation? {
    var language = ""
    var source = ""
    val translation = StringBuilder()
    var inTranslation = false

    for (line in text.lines()) {
        val match = IMAGE_FIELD_REGEX.find(line)
        val field = match?.groupValues?.get(1)
        val value = if (match != null) line.substring(match.value.length) else line
        when (field) {
            "LANG" -> { language = value.trimDecoration(); inTranslation = false }
            "SRC" -> { source = value.trimDecoration(); inTranslation = false }
            "DST" -> { translation.append(value); inTranslation = true }
            // DST 뒤의 줄은 번역문이 여러 줄인 경우다. 그 앞의 군더더기는 버린다.
            else -> if (inTranslation) translation.append('\n').append(line)
        }
    }

    // 형식이 없으면 실패로 본다. 예전에는 전체를 번역문으로 썼는데,
    // 모델이 "이건 아랍어라 영어로 번역 못 합니다" 같은 설명을 내놓으면
    // 그 설명이 그대로 번역 결과로 화면에 떴다(2026-09-21 실측).
    if (!inTranslation) return null
    return ImageTranslation(
        language = language,
        source = source,
        translation = translation.toString().trimDecoration(),
    )
}
