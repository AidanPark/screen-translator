package com.galaxy.airviewdictionary.data.remote.translation.deepl

import android.content.Context
import com.deepl.api.AuthorizationException
import com.deepl.api.TextResult
import com.deepl.api.Translator
import com.galaxy.airviewdictionary.data.local.secure.SecureStore
import com.galaxy.airviewdictionary.data.local.secure.SecureStoreKey
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKit
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeepL 번역 엔진. 사용자가 직접 발급받은 개인 API 키로 동작한다.
 * 키는 설정 > API Key > DeepL 에서 입력받아 [SecureStore] 에 암호화 저장된다.
 * 저장된 키가 없으면 엔진은 비활성 상태이며 엔진 전환기에 노출되지 않는다.
 */
@Singleton
class DeepLKit @Inject constructor(
    @ApplicationContext private val context: Context,
) : TranslationKit() {

    private var translator: Translator? = null

    // translator 가 어떤 키로 생성됐는지 기억해서, 키가 바뀌면 재생성한다
    private var translatorApiKey: String? = null

    private fun resolveApiKey(): String? {
        return getStoredApiKey(context)
    }

    override fun available(): Boolean {
        return resolveApiKey() != null
    }

    init {
        refreshAvailability(context)
    }

    override val supportedLanguagesAsSource: List<Language> by lazy {
        supportedSourceLanguageCodes.map { Language(it.lowercase()).apply { supportKitTypes.add(TranslationKitType.DEEPL) } }
    }

    override val supportedLanguagesAsTarget: List<Language> by lazy {
        supportedTargetLanguageCodes.map { Language(it.lowercase()).apply { supportKitTypes.add(TranslationKitType.DEEPL) } }
    }

    override fun isSupportedAsSource(code: String, targetLanguageCode: String): Boolean {
        return supportedLanguagesAsSource.any { it.code.equals(code, ignoreCase = true) } && supportedLanguagesAsTarget.any { it.code.equals(targetLanguageCode, ignoreCase = true) }
    }

    override fun isSupportedAsTarget(code: String, sourceLanguageCode: String): Boolean {
        return supportedLanguagesAsTarget.any { it.code.equals(code, ignoreCase = true) } && supportedLanguagesAsSource.any { it.code.equals(sourceLanguageCode, ignoreCase = true) }
    }

    override fun isLanguageSwappable(sourceLanguageCode: String, targetLanguageCode: String): Boolean {
        return isSupportedAsSource(targetLanguageCode, sourceLanguageCode) && isSupportedAsTarget(sourceLanguageCode, targetLanguageCode)
    }

    /**
     * 공용 언어 코드를 DeepL 고유 타겟 코드로 변환한다.
     * (DeepL 은 EN/PT/ZH 의 변형 지정을 요구한다)
     */
    private fun getOwnTargetLanguageCode(languageCode: String): String {
        return when (languageCode) {
            "en" -> "EN-US"
            "pt" -> "PT-PT"
            "zh-CN" -> "ZH-HANS"
            "zh-TW" -> "ZH-HANT"
            else -> languageCode
        }
    }

    override suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String
    ): TranslationResponse {
        return try {
            val apiKey = resolveApiKey() ?: throw IllegalStateException("DeepL API key is not set.")
            val translator = translator.let { current ->
                if (current == null || translatorApiKey != apiKey) {
                    Translator(apiKey).also {
                        translator = it
                        translatorApiKey = apiKey
                    }
                } else {
                    current
                }
            }
            val textResult: TextResult = withContext(Dispatchers.IO) {
                translator.translateText(
                    sourceText,
                    if (sourceLanguageCode == "auto") null else sourceLanguageCode,
                    getOwnTargetLanguageCode(targetLanguageCode),
                )
            }
            TranslationResponse.Success(
                Transaction(
                    targetLanguageCode = targetLanguageCode,
                    sourceText = sourceText,
                    translationKitType = TranslationKitType.DEEPL,
                    // 응답의 원문 언어(detected_source_language). deepl-java 가 소문자 코드("en")로 표준화해 돌려준다
                    // (LanguageCode.standardize).
                    resolvedSourceLanguageCode = textResult.detectedSourceLanguage,
                    resultText = textResult.text
                )
            )
        } catch (e: CancellationException) {
            // 취소는 오류가 아니다. 여기서 삼키면 핸들이 떠나 취소된 요청이
            // 실패 안내로 둔갑하고, 상위 코루틴은 취소된 줄 모른 채 계속 진행한다.
            throw e
        } catch (e: Exception) {
            TranslationResponse.Error(e)
        }
    }

    /**
     * API 키 검증 결과.
     * 네트워크 오류는 키 자체의 문제가 아니므로 무효와 구분한다.
     */
    enum class KeyValidationResult {
        VALID,
        INVALID,
        NETWORK_ERROR,
    }

    companion object {
        // DeepL 계정 안내 링크
        const val URL_SUBSCRIPTION = "https://www.deepl.com/ko/your-account/subscription"
        const val URL_API_KEYS = "https://www.deepl.com/ko/your-account/keys"

        /**
         * 사용량 조회([Translator.getUsage])로 키 유효성을 검증한다. 글자 쿼터를 소모하지 않는다.
         */
        suspend fun validateApiKey(apiKey: String): KeyValidationResult = withContext(Dispatchers.IO) {
            try {
                Translator(apiKey).usage
                KeyValidationResult.VALID
            } catch (e: AuthorizationException) {
                Timber.tag("DeepLKit").w("validateApiKey invalid: $e")
                KeyValidationResult.INVALID
            } catch (e: Exception) {
                Timber.tag("DeepLKit").w("validateApiKey network error: $e")
                KeyValidationResult.NETWORK_ERROR
            }
        }

        /**
         * 저장된 API 키 존재 여부. 엔진 전환기 노출과 설정의 활성 표시가 이 값을 따른다.
         * (SecureStore 는 flow 를 제공하지 않으므로, 키 저장/조회 시점에 갱신한다)
         */
        private val _keyActivatedStateFlow = MutableStateFlow(false)
        val keyActivatedStateFlow: StateFlow<Boolean> = _keyActivatedStateFlow.asStateFlow()

        fun refreshAvailability(context: Context) {
            _keyActivatedStateFlow.value = getStoredApiKey(context) != null
        }

        /**
         * 설정에서 저장한 API 키. 없거나 공백이면 null.
         */
        fun getStoredApiKey(context: Context): String? {
            return SecureStore.get(context, SecureStoreKey.DEEPL_API_KEY)?.get()?.takeIf { it.isNotBlank() }
        }

        /**
         * 설정에서 입력한 API 키를 암호화 저장한다. 빈 문자열 저장은 키 삭제로 동작한다.
         */
        fun storeApiKey(context: Context, apiKey: String) {
            SecureStore.set(context, SecureStoreKey.DEEPL_API_KEY, apiKey.trim())
            refreshAvailability(context)
            Timber.tag("DeepLKit").i("storeApiKey saved (${apiKey.trim().length} chars)")
        }

        val supportedSourceLanguageCodes = arrayOf(
            "auto", // Auto
            "AR", // Arabic
            "BG", // Bulgarian
            "CS", // Czech
            "DA", // Danish
            "DE", // German
            "EL", // Greek
            "EN", // English
            "ES", // Spanish
            "ET", // Estonian
            "FI", // Finnish
            "FR", // French
            "HU", // Hungarian
            "ID", // Indonesian
            "IT", // Italian
            "JA", // Japanese
            "KO", // Korean
            "LT", // Lithuanian
            "LV", // Latvian
            "NB", // Norwegian Bokmål
            "NL", // Dutch
            "PL", // Polish
            "PT", // Portuguese
            "RO", // Romanian
            "RU", // Russian
            "SK", // Slovak
            "SL", // Slovenian
            "SV", // Swedish
            "TR", // Turkish
            "UK", // Ukrainian
            "ZH", // Chinese
        )

        val supportedTargetLanguageCodes = arrayOf(
            "AR", // Arabic
            "BG", // Bulgarian
            "CS", // Czech
            "DA", // Danish
            "DE", // German
            "EL", // Greek
            "EN-GB", // en-gb --------------- English (British)
            "en", // en-us --------------- English (American)
            "ES", // Spanish
            "ET", // Estonian
            "FI", // Finnish
            "FR", // French
            "HU", // Hungarian
            "ID", // Indonesian
            "IT", // Italian
            "JA", // Japanese
            "KO", // Korean
            "LT", // Lithuanian
            "LV", // Latvian
            "NB", // Norwegian Bokmål
            "NL", // Dutch
            "PL", // Polish
            "PT-BR", // pt-br --------------- Portuguese (Brazilian)
            "pt", // pt-pt --------------- Portuguese (excluding Brazilian)
            "RO", // Romanian
            "RU", // Russian
            "SK", // Slovak
            "SL", // Slovenian
            "SV", // Swedish
            "TR", // Turkish
            "UK", // Ukrainian
            "zh-CN", // zh-hans --------------- Chinese (simplified)
            "zh-TW", // zh-hant --------------- Chinese (traditional)
        )
    }
}
