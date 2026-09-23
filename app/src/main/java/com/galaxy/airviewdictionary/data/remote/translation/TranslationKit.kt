package com.galaxy.airviewdictionary.data.remote.translation

import android.graphics.Bitmap
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.remote.translation.Language


abstract class TranslationKit {

    protected val TAG: String = javaClass.simpleName

    abstract fun available(): Boolean

    abstract val supportedLanguagesAsSource: List<Language>

    abstract val supportedLanguagesAsTarget: List<Language>

    /**
     * Language codes for source languages supported by Translator.
     */
    abstract fun isSupportedAsSource(code: String, targetLanguageCode: String): Boolean

    /**
     * Language codes for target languages supported by Translator.
     */
    abstract fun isSupportedAsTarget(code: String, sourceLanguageCode: String): Boolean

    abstract fun isLanguageSwappable(sourceLanguageCode: String, targetLanguageCode: String): Boolean

    /**
     * Request translation.
     * This function would block current thread and coroutine cannot be properly suspended.
     * Therefore, it must be used within 'viewModelScope.launch' syntax.
     *
     * [TranslationResponse] Translation response object
     */
    abstract suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String
    ): TranslationResponse

    /**
     * 주변 텍스트를 문맥으로 함께 받는 번역 요청.
     *
     * 프롬프트 개념이 있는 AI 엔진만 [contextText] 를 활용한다.
     * 나머지 엔진은 기본 구현대로 문맥을 무시하고 대상 문장만 번역한다.
     */
    open suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String,
        contextText: String?,
    ): TranslationResponse = request(sourceLanguageCode, targetLanguageCode, sourceText)

    /** 이 엔진이 이미지 경로를 실제로 지원하는지. */
    open fun supportsImageRequest(): Boolean = false

    /**
     * 화면 이미지를 직접 받는 번역 요청.
     *
     * OCR 이 텍스트 경계를 잘못 그으면(아랍어 등 연결 문자, 태국어 등 무공백 문자)
     * [sourceText] 자체가 망가져 있어 어떤 모델을 써도 번역 품질에 상한이 생긴다.
     * 이미지를 읽는 엔진은 [targetImage] 의 마커 위치와 [detectMode] 로 대상을 직접 찾아
     * OCR 의 잘못된 분할을 교정할 수 있다. [sourceText] 는 참고용 힌트로만 넘긴다.
     *
     * 지원하지 않는 엔진은 기본 구현대로 기존 텍스트 경로를 탄다.
     */
    open suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String,
        contextText: String?,
        targetImage: Bitmap,
        detectMode: TextDetectMode,
    ): TranslationResponse = request(sourceLanguageCode, targetLanguageCode, sourceText, contextText)

}


