package com.galaxy.airviewdictionary.data.remote.translation.claude

import android.graphics.Bitmap
import android.util.Base64
import com.galaxy.airviewdictionary.data.local.capture.TargetCrop
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.data.remote.translation.buildImageTranslationSystemPrompt
import com.galaxy.airviewdictionary.data.remote.translation.NoTextAtPointerException
import com.galaxy.airviewdictionary.data.remote.translation.detectedLanguageCodeOrNull
import com.galaxy.airviewdictionary.data.remote.translation.parseImageTranslation
import java.io.ByteArrayOutputStream
import android.content.Context
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.secure.SecureStore
import com.galaxy.airviewdictionary.data.local.secure.SecureStoreKey
import com.galaxy.airviewdictionary.data.remote.firebase.RemoteConfigRepository
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKit
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.TranslationDomain
import com.galaxy.airviewdictionary.data.remote.translation.TranslationStrength
import com.galaxy.airviewdictionary.data.remote.translation.buildTranslationSystemPrompt
import com.galaxy.airviewdictionary.data.remote.translation.buildTranslationUserMessage
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import com.galaxy.airviewdictionary.data.remote.translation.goolge.GoogleWebKit
import com.galaxy.airviewdictionary.di.ClaudeRetrofit
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anthropic Claude 번역 엔진. 전용 번역 API 대신 Messages API 에 번역 프롬프트를 보내 사용한다.
 * 사용자가 발급받은 개인 API 키로 동작하며, 키는 설정 > API Key > Claude 에서
 * [SecureStore] 에 암호화 저장된다. 사용할 모델은 설정에서 고르고, 후보 목록은 Firebase Remote Config
 * ([RemoteConfigRepository.TRANSLATE_MODELS])로 관리한다.
 * 저장된 키가 없으면 엔진은 비활성 상태이며 엔진 전환기에 노출되지 않는다.
 */
@Singleton
class ClaudeKit @Inject constructor(
    @ApplicationContext private val context: Context,
    @ClaudeRetrofit private val service: ClaudeService,
    private val googleWebKit: GoogleWebKit,
    private val preferenceRepository: PreferenceRepository,
    private val remoteConfigRepository: RemoteConfigRepository,
) : TranslationKit() {

    override fun available(): Boolean {
        return getStoredApiKey(context) != null
    }

    init {
        refreshAvailability(context)
    }

    // Claude 는 사실상 전 언어를 번역하므로 Google 과 동일한 언어 커버리지를 사용한다.
    override val supportedLanguagesAsSource: List<Language> by lazy {
        googleWebKit.supportedLanguagesAsSource.map {
            Language(it.code).apply { supportKitTypes.add(TranslationKitType.CLAUDE) }
        }
    }

    override val supportedLanguagesAsTarget: List<Language> by lazy {
        googleWebKit.supportedLanguagesAsTarget.map {
            Language(it.code).apply { supportKitTypes.add(TranslationKitType.CLAUDE) }
        }
    }

    override fun isSupportedAsSource(code: String, targetLanguageCode: String): Boolean {
        return supportedLanguagesAsSource.any { it.code.equals(code, ignoreCase = true) } &&
                supportedLanguagesAsTarget.any { it.code.equals(targetLanguageCode, ignoreCase = true) }
    }

    override fun isSupportedAsTarget(code: String, sourceLanguageCode: String): Boolean {
        return supportedLanguagesAsTarget.any { it.code.equals(code, ignoreCase = true) } &&
                supportedLanguagesAsSource.any { it.code.equals(sourceLanguageCode, ignoreCase = true) }
    }

    override fun isLanguageSwappable(sourceLanguageCode: String, targetLanguageCode: String): Boolean {
        return isSupportedAsSource(targetLanguageCode, sourceLanguageCode) &&
                isSupportedAsTarget(sourceLanguageCode, targetLanguageCode)
    }

    private fun buildSystemPrompt(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        strength: TranslationStrength,
        domain: TranslationDomain,
        hasContext: Boolean,
    ): String = buildTranslationSystemPrompt(
        sourceLanguageName = if (sourceLanguageCode == "auto") null else Language(sourceLanguageCode).displayName,
        targetLanguageName = Language(targetLanguageCode).displayName,
        strength = strength,
        domain = domain,
        hasContext = hasContext,
    )

    /**
     * 사용할 모델. 설정에서 고른 값이 있고 현재 후보에 있으면 그것을, 아니면 후보의 첫 번째를, 그마저 없으면 기본값.
     */
    private suspend fun resolveModel(): String {
        val chosen = preferenceRepository.claudeModelFlow.first()?.takeIf { it.isNotBlank() }
        val candidates = remoteConfigRepository.getClaudeTranslateModels()
        return when {
            chosen != null && chosen in candidates -> chosen
            candidates.isNotEmpty() -> candidates.first()
            else -> chosen ?: DEFAULT_MODEL
        }
    }

    override suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String
    ): TranslationResponse = request(sourceLanguageCode, targetLanguageCode, sourceText, null)

    override fun supportsImageRequest(): Boolean = true

    /**
     * 화면 크롭을 그대로 보내는 번역. 배경은 [TranslationKit.request] 이미지 오버로드와 [TargetCrop] 참조.
     * Gemini 경로와 형식·프롬프트를 공유한다(LANG/SRC/DST 줄 접두사).
     */
    override suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String,
        contextText: String?,
        targetImage: Bitmap,
        detectMode: TextDetectMode,
    ): TranslationResponse {
        return try {
            val apiKey = getStoredApiKey(context) ?: throw IllegalStateException("Claude API key is not set.")
            val model = resolveModel()
            val strength = preferenceRepository.claudeTranslationStrengthFlow.first()
            val domain = preferenceRepository.claudeTranslationDomainFlow.first()

            val encodeStart = System.nanoTime()
            val imageBase64 = targetImage.toJpegBase64()
            val encodeMs = (System.nanoTime() - encodeStart) / 1_000_000

            val requestBody = mapOf(
                "model" to model,
                "max_tokens" to 4096,
                "system" to buildImageTranslationSystemPrompt(
                    sourceLanguageName = if (sourceLanguageCode == "auto") null else Language(sourceLanguageCode).englishName,
                    targetLanguageName = Language(targetLanguageCode).englishName,
                    strength = strength,
                    domain = domain,
                    detectMode = detectMode,
                ),
                // 이미지만 보낸다. OCR 힌트도 문맥 텍스트도 넣지 않는다 —
                // 필요한 정보가 이미 이미지에 있고, 둘 다 토큰만 늘린다.
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf(
                                "type" to "image",
                                "source" to mapOf(
                                    "type" to "base64",
                                    "media_type" to "image/jpeg",
                                    "data" to imageBase64,
                                ),
                            )
                        ),
                    ),
                    // assistant 프리필: 응답 첫머리를 고정해 형식을 강제한다.
                    // 프롬프트로 부탁하면 모델이 설명문을 내놓을 수 있고, 그게 번역 결과로 떴다.
                    // (끝에 공백을 두면 API 가 거부하므로 붙이지 않는다)
                    mapOf("role" to "assistant", "content" to PREFILL),
                ),
            )
            val json = Gson().toJson(requestBody).toRequestBody("application/json".toMediaType())
            val callStart = System.nanoTime()
            val response = withContext(Dispatchers.IO) {
                service.messages(apiKey, json)
            }
            val callMs = (System.nanoTime() - callStart) / 1_000_000

            // 프리필한 만큼은 응답에 포함되지 않으므로 앞에 도로 붙여 파싱한다.
            val raw = PREFILL + (response.content?.firstOrNull { it.type == "text" } ?: response.content?.firstOrNull())
                ?.text.orEmpty()
            val parsed = parseImageTranslation(cleanOutput(raw))
                ?: throw IllegalStateException("형식을 벗어난 응답: ${cleanOutput(raw).take(120)}")
            // 마커 아래에 글자가 없다는 답. 오류 안내 대신 조용히 끝내라고 호출부에 알린다.
            if (parsed.isNoText) throw NoTextAtPointerException()

            // 이 경로의 성패는 지연과 토큰 비용에서 갈린다. 릴리스에서는 R8 이 걷어낸다.
            val usage = response.usage
            Timber.tag(TAG).i(
                "image request: ${targetImage.width}x${targetImage.height}" +
                        " jpeg=${imageBase64.length / 1024}KB encode=${encodeMs}ms call=${callMs}ms" +
                        " in=${usage?.input_tokens} out=${usage?.output_tokens}" +
                        " mode=$detectMode model=$model"
            )

            TranslationResponse.Success(
                Transaction(
                    targetLanguageCode = targetLanguageCode,
                    // 모델이 이미지에서 직접 읽은 원문. 못 읽었으면 null 로 두고
                    // 무엇으로 대신할지는 파이프라인이 정한다.
                    sourceText = parsed.source.takeIf { it.isNotBlank() },
                    translationKitType = TranslationKitType.CLAUDE,
                    // 이미지 경로는 auto 라도 모델이 언어를 판정한다.
                    resolvedSourceLanguageCode = if (sourceLanguageCode == "auto") {
                        parsed.detectedLanguageCodeOrNull()
                    } else {
                        sourceLanguageCode
                    },
                    resultText = parsed.translation,
                    modelName = model,
                )
            )
        } catch (e: CancellationException) {
            // 취소는 오류가 아니다. 여기서 삼키면 핸들이 떠나 취소된 요청이
            // 실패 안내로 둔갑하고, 상위 코루틴은 취소된 줄 모른 채 계속 진행한다.
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("image request error: ${e.message}")
            TranslationResponse.Error(e)
        }
    }

    /** Claude 의 image 블록은 base64 를 받는다. PNG 보다 JPEG 이 화면 캡처에서 훨씬 작다. */
    private fun Bitmap.toJpegBase64(): String {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    override suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String,
        contextText: String?,
    ): TranslationResponse {
        return try {
            val apiKey = getStoredApiKey(context) ?: throw IllegalStateException("Claude API key is not set.")
            val model = resolveModel()
            val strength = preferenceRepository.claudeTranslationStrengthFlow.first()
            val domain = preferenceRepository.claudeTranslationDomainFlow.first()
            val effectiveContext = contextText?.takeIf { it.isNotBlank() }
            val requestBody = mapOf(
                "model" to model,
                "max_tokens" to 4096,
                // temperature 를 보내지 않는다.
                // Claude Sonnet 5 / Opus 5 / Opus 4.8 은 `temperature` is deprecated for this model
                // 으로 400 을 낸다(2026-09-20 실측). Haiku 4.5 는 있으나 없으나 동일하게 동작하므로
                // 모델별 분기 대신 아예 뺀다. 결정성은 system 프롬프트로 확보한다.
                //
                // effort 도 넣지 않는다: Sonnet 5 는 output_config.effort=low 로 약 0.7초 빨라지지만
                // Haiku 4.5 는 effort 를 지원하지 않아 400 이 난다. 모델별 분기가 필요한데
                // RC 로 모델이 바뀌는 구조라 목록 하드코딩이 쉽게 어긋난다.
                "system" to buildSystemPrompt(
                    sourceLanguageCode = sourceLanguageCode,
                    targetLanguageCode = targetLanguageCode,
                    strength = strength,
                    domain = domain,
                    hasContext = effectiveContext != null,
                ),
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to buildTranslationUserMessage(sourceText, effectiveContext),
                    )
                ),
            )
            val json = Gson().toJson(requestBody).toRequestBody("application/json".toMediaType())
            val response = withContext(Dispatchers.IO) {
                service.messages(apiKey, json)
            }
            val raw = (response.content?.firstOrNull { it.type == "text" } ?: response.content?.firstOrNull())
                ?.text.orEmpty()
            TranslationResponse.Success(
                Transaction(
                    targetLanguageCode = targetLanguageCode,
                    sourceText = sourceText,
                    translationKitType = TranslationKitType.CLAUDE,
                    // 텍스트 경로는 언어를 판정하지 않는다. 지정 번역이면 그 언어가 곧 원문 언어다.
                    resolvedSourceLanguageCode = sourceLanguageCode.takeIf { it != "auto" },
                    resultText = cleanOutput(raw),
                    modelName = model,
                )
            )
        } catch (e: CancellationException) {
            // 취소는 오류가 아니다. 여기서 삼키면 핸들이 떠나 취소된 요청이
            // 실패 안내로 둔갑하고, 상위 코루틴은 취소된 줄 모른 채 계속 진행한다.
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("request error: ${e.message}")
            TranslationResponse.Error(e)
        }
    }

    /** LLM 이 종종 붙이는 코드펜스/따옴표/여백을 정리한다. */
    private fun cleanOutput(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```").substringAfter('\n', "").trim()
            text = text.removeSuffix("```").trim()
        }
        if (text.length >= 2 &&
            ((text.first() == '"' && text.last() == '"') || (text.first() == '\'' && text.last() == '\''))
        ) {
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    /**
     * API 키 검증 결과. 네트워크 오류는 키 자체의 문제가 아니므로 무효와 구분한다.
     */
    enum class KeyValidationResult {
        VALID,
        INVALID,
        NETWORK_ERROR,
    }

    companion object {
        const val BASE_URL = "https://api.anthropic.com/"

        /** 화면 캡처는 사진이 아니라 UI 라, 품질을 조금 낮춰도 글자 가독성은 유지된다. */
        private const val JPEG_QUALITY = 85

        /** 이미지 경로 응답의 첫머리. 형식을 강제하는 assistant 프리필로도 쓴다. */
        private const val PREFILL = "LANG:"
        const val DEFAULT_MODEL = "claude-haiku-4-5-20251001"

        // Claude API 키 발급/사용량 안내 링크
        const val URL_API_KEYS = "https://console.anthropic.com/settings/keys"
        const val URL_BILLING = "https://console.anthropic.com/settings/billing"

        /**
         * 모델 목록([GET /v1/models])을 조회해 키 유효성을 검증한다. 토큰을 소모하지 않는다.
         */
        suspend fun validateApiKey(apiKey: String): KeyValidationResult = withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url("${BASE_URL}v1/models")
                    .header("x-api-key", apiKey.trim())
                    .header("anthropic-version", "2023-06-01")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> KeyValidationResult.VALID
                        response.code == 401 || response.code == 403 -> KeyValidationResult.INVALID
                        else -> KeyValidationResult.NETWORK_ERROR
                    }
                }
            } catch (e: Exception) {
                Timber.tag("ClaudeKit").w("validateApiKey error: $e")
                KeyValidationResult.NETWORK_ERROR
            }
        }

        /**
         * 저장된 API 키 존재 여부. 엔진 전환기 노출과 설정의 활성 표시가 이 값을 따른다.
         */
        private val _keyActivatedStateFlow = MutableStateFlow(false)
        val keyActivatedStateFlow: StateFlow<Boolean> = _keyActivatedStateFlow.asStateFlow()

        fun refreshAvailability(context: Context) {
            _keyActivatedStateFlow.value = getStoredApiKey(context) != null
        }

        /** 설정에서 저장한 API 키. 없거나 공백이면 null. */
        fun getStoredApiKey(context: Context): String? {
            return SecureStore.get(context, SecureStoreKey.CLAUDE_API_KEY)?.get()?.takeIf { it.isNotBlank() }
        }

        /** 설정에서 입력한 API 키를 암호화 저장한다. 빈 문자열 저장은 키 삭제로 동작한다. */
        fun storeApiKey(context: Context, apiKey: String) {
            SecureStore.set(context, SecureStoreKey.CLAUDE_API_KEY, apiKey.trim())
            refreshAvailability(context)
            Timber.tag("ClaudeKit").i("storeApiKey saved (${apiKey.trim().length} chars)")
        }
    }
}
