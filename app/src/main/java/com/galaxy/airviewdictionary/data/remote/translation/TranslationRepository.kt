package com.galaxy.airviewdictionary.data.remote.translation

import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKitSelector
import com.galaxy.airviewdictionary.data.AVDRepository
import com.galaxy.airviewdictionary.data.remote.translation.claude.ClaudeKit
import com.galaxy.airviewdictionary.data.remote.translation.deepl.DeepLKit
import com.galaxy.airviewdictionary.data.remote.translation.gemini.GeminiKit
import com.galaxy.airviewdictionary.data.remote.translation.goolge.GoogleWebKit
import com.galaxy.airviewdictionary.data.remote.translation.openai.OpenAiKit
import com.galaxy.airviewdictionary.data.remote.translation.Language
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton


/**
 * 번역 저장소.
 * - GOOGLE: 무료 Google 웹 번역
 * - DEEPL: 사용자의 개인 API 키로 동작하는 DeepL
 */
@Singleton
class TranslationRepository @Inject constructor(
    private val googleWebKit: GoogleWebKit,
    private val deepLKit: DeepLKit,
    private val openAiKit: OpenAiKit,
    private val geminiKit: GeminiKit,
    private val claudeKit: ClaudeKit,
) : AVDRepository() {

    // 라틴 문자를 사용하는 언어 코드 리스트. 이 로케일 사용자에게는 표시명 하단 정렬을 건너뛴다.
    private val latinLanguages = setOf("en", "es", "fr", "de", "pt", "it", "ro", "nl", "sv", "no", "da", "fi", "pl", "cs", "hu", "sk", "sl")

    val supportedLanguagesAsSource: List<Language> by lazy {
        val (autoLanguages, otherLanguages) = mergeLanguages(
            googleWebKit.supportedLanguagesAsSource,
            deepLKit.supportedLanguagesAsSource,
            openAiKit.supportedLanguagesAsSource,
            geminiKit.supportedLanguagesAsSource,
            claudeKit.supportedLanguagesAsSource,
        ).partition { it.code.equals("auto", ignoreCase = true) }

        // 비-라틴 로케일 사용자에게는 표시명이 부실한 언어([Language.noDisplayNameList])를 목록 맨 아래로 정렬한다.
        // (현재 그 리스트는 비어 있어 결과적으로 전체 정렬과 동일하지만, 향후 확장을 위해 기제를 유지한다.)
        val userLanguageCode = Locale.getDefault().language
        if (userLanguageCode in latinLanguages) {
            autoLanguages + otherLanguages.sorted()
        } else {
            val (noDisplayNames, regularLanguages) = otherLanguages.partition { language ->
                language.code.uppercase() in Language.noDisplayNameList
            }
            autoLanguages + regularLanguages.sorted() + noDisplayNames.sorted()
        }
    }

    val supportedLanguagesAsTarget: List<Language> by lazy {
        val mergedLanguages = mergeLanguages(
            googleWebKit.supportedLanguagesAsTarget,
            deepLKit.supportedLanguagesAsTarget,
            openAiKit.supportedLanguagesAsTarget,
            geminiKit.supportedLanguagesAsTarget,
            claudeKit.supportedLanguagesAsTarget,
        )

        // 소스와 동일한 이유로, 비-라틴 로케일에서는 표시명이 부실한 언어를 하단으로 정렬한다.
        val userLanguageCode = Locale.getDefault().language
        if (userLanguageCode in latinLanguages) {
            mergedLanguages.sorted()
        } else {
            val (noDisplayNames, regularLanguages) = mergedLanguages.partition { language ->
                language.code.uppercase() in Language.noDisplayNameList
            }
            regularLanguages.sorted() + noDisplayNames.sorted()
        }
    }

    /**
     * 여러 엔진의 지원 언어 리스트를 코드 기준으로 병합한다.
     * 같은 언어가 여러 엔진에서 지원되면 supportKitTypes 를 합친다.
     */
    private fun mergeLanguages(vararg lists: List<Language>): List<Language> {
        val languageMap = mutableMapOf<String, Language>()

        for (language in lists.flatMap { it }) {
            val key = language.code.uppercase()
            val existingLanguage = languageMap[key]
            if (existingLanguage != null) {
                val mergedSupportKitTypes = (existingLanguage.supportKitTypes + language.supportKitTypes).distinct()
                languageMap[key] = Language(existingLanguage.code).apply { supportKitTypes.addAll(mergedSupportKitTypes) }
            } else {
                languageMap[key] = language
            }
        }

        return languageMap.values.toList()
    }

    fun getSupportedLanguages(kitType: TranslationKitType): List<Language> {
        val languages = when (kitType) {
            TranslationKitType.GOOGLE -> googleWebKit.supportedLanguagesAsSource + googleWebKit.supportedLanguagesAsTarget
            TranslationKitType.DEEPL -> deepLKit.supportedLanguagesAsSource + deepLKit.supportedLanguagesAsTarget
            TranslationKitType.OPENAI -> openAiKit.supportedLanguagesAsSource + openAiKit.supportedLanguagesAsTarget
            TranslationKitType.GEMINI -> geminiKit.supportedLanguagesAsSource + geminiKit.supportedLanguagesAsTarget
            TranslationKitType.CLAUDE -> claudeKit.supportedLanguagesAsSource + claudeKit.supportedLanguagesAsTarget
        }
        return languages
            .distinctBy { it.code.uppercase() }
            .sortedBy { it.displayName }
    }

    fun getSupportedSourceLanguage(code: String): Language {
        return supportedLanguagesAsSource.find { it.code.equals(code, ignoreCase = true) } ?: Language(code)
    }

    fun getSupportedTargetLanguage(code: String): Language {
        return supportedLanguagesAsTarget.find { it.code.equals(code, ignoreCase = true) } ?: Language(code)
    }

    private fun getTranslationKit(kitType: TranslationKitType): TranslationKit {
        return when (kitType) {
            TranslationKitType.GOOGLE -> googleWebKit
            TranslationKitType.DEEPL -> deepLKit
            TranslationKitType.OPENAI -> openAiKit
            TranslationKitType.GEMINI -> geminiKit
            TranslationKitType.CLAUDE -> claudeKit
        }
    }

    fun isSupportedAsSource(kitType: TranslationKitType, code: String, targetLanguageCode: String): Boolean {
        return getTranslationKit(kitType).isSupportedAsSource(code, targetLanguageCode)
    }

    fun isSupportedAsTarget(kitType: TranslationKitType, code: String, sourceLanguageCode: String): Boolean {
        return getTranslationKit(kitType).isSupportedAsTarget(code, sourceLanguageCode)
    }

    /** 바꾸면 대상 언어가 원문이 되므로, 그 문자를 읽을 엔진이 없으면 바꿀 수 없다(§21). */
    fun isLanguageSwappable(sourceLanguageCode: String, targetLanguageCode: String, kitType: TranslationKitType): Boolean {
        return VisionKitSelector.hasReaderFor(targetLanguageCode) &&
                getTranslationKit(kitType).isLanguageSwappable(sourceLanguageCode, targetLanguageCode)
    }

    suspend fun request(
        translationKitType: TranslationKitType,
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String,
        contextText: String? = null,
    ): TranslationResponse {
        return getTranslationKit(translationKitType).request(
            sourceLanguageCode,
            targetLanguageCode,
            sourceText,
            contextText,
        )
    }

    override fun onZeroReferences() {
    }
}
