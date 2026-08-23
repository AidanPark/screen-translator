package com.galaxy.airviewdictionary.data.local.preference

import android.content.Context
import android.speech.tts.Voice
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.local.tts.TTSReadTarget
import com.galaxy.airviewdictionary.data.local.vision.TextDetectMode
import com.galaxy.airviewdictionary.ui.screen.overlay.menubar.MenuConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton


val Context.preferenceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "user_preferences"
)

@Singleton
class PreferenceRepository @Inject constructor(@ApplicationContext val context: Context) {

    private val TAG = javaClass.simpleName

    companion object PreferencesKeys {
        val WAS_TRAILER_SHOWN = booleanPreferencesKey("was_trailer_shown")
        val IS_REVIEW_DONE = booleanPreferencesKey("is_review_done")

        val IS_SAY_HERE_R_SHOWN = booleanPreferencesKey("is_say_here_r_shown")
        val IS_SAY_HERE_L_SHOWN = booleanPreferencesKey("is_say_here_l_shown")

        // 설정 화면이 처음 닫힌 뒤, 핸들 더블탭으로 설정을 다시 열 수 있음을 한 번만 안내했는지
        val IS_SETTINGS_REOPEN_HINT_SHOWN = booleanPreferencesKey("is_settings_reopen_hint_shown")

        val TEXT_DETECT_MODE: Preferences.Key<String> = stringPreferencesKey("text_detect_mode")
        val SOURCE_LANGUAGE_CODE = stringPreferencesKey("source_language_code")
        val TARGET_LANGUAGE_CODE = stringPreferencesKey("target_language_code")
        val TRANSLATION_KIT_TYPE = stringPreferencesKey("translation_kit_type")
        // OpenAI 번역에 사용할 모델. 미설정이면 OpenAiKit 이 Remote Config 후보의 첫 번째로 폴백한다.
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        // Gemini 번역에 사용할 모델. 미설정이면 GeminiKit 이 Remote Config 후보의 첫 번째로 폴백한다.
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        // Claude 번역에 사용할 모델. 미설정이면 ClaudeKit 이 Remote Config 후보의 첫 번째로 폴백한다.
        val CLAUDE_MODEL = stringPreferencesKey("claude_model")

        val DRAG_HANDLE_DOCKING = booleanPreferencesKey("drag_handle_docking")
        val DOCKING_DELAY = longPreferencesKey("docking_delay")
        val DRAG_HANDLE_HAPTIC = booleanPreferencesKey("drag_handle_haptic")
        val MENU_BAR_VISIBILITY = booleanPreferencesKey("menu_bar_visibility")
        val MENU_BAR_TRANSPARENCY = floatPreferencesKey("menu_bar_transparency")
        val MENU_BAR_COMPOSITION = stringPreferencesKey("menu_bar_composition")
        val TRANSLATION_TRANSPARENCY = floatPreferencesKey("translation_transparency")
        val TRANSLATION_CLOSE_DELAY = longPreferencesKey("translation_close_delay")
        val REPLY_TRANSPARENCY = floatPreferencesKey("reply_transparency")
        val AUTOMATIC_TRANSLATION_PLAYBACK = booleanPreferencesKey("automatic_translation_playback")
        val TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        val TTS_ORDERED_VOICE_NAMES = stringPreferencesKey("tts_ordered_voice_names")
        val TTS_READ_TARGET = stringPreferencesKey("tts_read_target")
        // 마지막 번역에 실제 사용된 소스 언어(auto 감지 결과 포함). 소스가 auto 일 때
        // TTS 목소리 목록 정렬 등에서 기준 언어로 쓴다.
        val LAST_USED_SOURCE_LANGUAGE_CODE = stringPreferencesKey("last_used_source_language_code")

        val SOURCE_LANGUAGE_CODE_HISTORY = stringPreferencesKey("source_language_code_history")
        val TARGET_LANGUAGE_CODE_HISTORY = stringPreferencesKey("target_language_code_history")
    }

    private val preferenceDataStore: DataStore<Preferences> = context.preferenceDataStore

    private val gson = Gson()

    private val preferenceFlow: Flow<Preferences> = preferenceDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    fun <T> update(key: Preferences.Key<T>, value: T) {
        CoroutineScope(Dispatchers.IO).launch {
            preferenceDataStore.edit { preferences ->
                preferences[key] = value
            }
        }
    }

    val wasTrailerShownFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[WAS_TRAILER_SHOWN] ?: false
    }

    val isReviewDoneFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[IS_REVIEW_DONE] ?: false
    }

    val isSayHereRShownFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[IS_SAY_HERE_R_SHOWN] ?: false
    }

    val isSayHereLShownFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[IS_SAY_HERE_L_SHOWN] ?: false
    }

    val isSettingsReopenHintShownFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[IS_SETTINGS_REOPEN_HINT_SHOWN] ?: false
    }

    /**
     * 저장된 문자열을 enum 으로 변환한다.
     * 앱 업데이트로 값이 제거/변경되어 더 이상 존재하지 않는 경우(예: 과거 선택한
     * 번역 엔진이 삭제된 경우) 크래시 대신 기본값으로 안전하게 폴백한다.
     */
    private inline fun <reified T : Enum<T>> safeEnumValueOf(value: String?, default: T): T {
        if (value == null) return default
        return try {
            enumValueOf<T>(value)
        } catch (e: IllegalArgumentException) {
            default
        }
    }

    val textDetectModeFlow: Flow<TextDetectMode> = preferenceFlow.map { preferences ->
        safeEnumValueOf(preferences[TEXT_DETECT_MODE], TextDetectMode.SENTENCE)
    }

    val sourceLanguageCodeFlow: Flow<String> = preferenceFlow.map { preferences ->
        Timber.tag(TAG).d(" preferences[SOURCE_LANGUAGE_CODE] ${preferences[SOURCE_LANGUAGE_CODE]} getCurrentLocale().language ${getCurrentLocale().language}")
        // 기본값은 auto(자동 감지). 소스 언어가 원문과 어긋나 OCR 인식기가 잘못 선택되는 문제를 방지한다.
        preferences[SOURCE_LANGUAGE_CODE] ?: "auto"
    }

    val targetLanguageCodeFlow: Flow<String> = preferenceFlow.map { preferences ->
        Timber.tag(TAG).d(" preferences[TARGET_LANGUAGE_CODE] ${preferences[TARGET_LANGUAGE_CODE]}")
        // 기본 번역 대상은 사용자 시스템 언어. (소스는 auto → "아무 외국어 → 내 언어"가 기본이 된다)
        preferences[TARGET_LANGUAGE_CODE] ?: getCurrentLocale().language
    }

    // 마지막 번역에 실제 사용된 소스 언어. 번역 이력이 없으면 null.
    val lastUsedSourceLanguageCodeFlow: Flow<String?> = preferenceFlow.map { preferences ->
        preferences[LAST_USED_SOURCE_LANGUAGE_CODE]
    }

    val translationKitTypeFlow: Flow<TranslationKitType> = preferenceFlow.map { preferences ->
        // 과거에 저장된, 지금은 없는 엔진 값이라도 안전하게 GOOGLE 로 폴백
        safeEnumValueOf(preferences[TRANSLATION_KIT_TYPE], TranslationKitType.GOOGLE)
    }

    // OpenAI 번역 모델 선택값. 미설정이면 null (OpenAiKit 이 Remote Config 후보로 폴백).
    val openAiModelFlow: Flow<String?> = preferenceFlow.map { preferences ->
        preferences[OPENAI_MODEL]
    }

    // Gemini 번역 모델 선택값. 미설정이면 null (GeminiKit 이 Remote Config 후보로 폴백).
    val geminiModelFlow: Flow<String?> = preferenceFlow.map { preferences ->
        preferences[GEMINI_MODEL]
    }

    // Claude 번역 모델 선택값. 미설정이면 null (ClaudeKit 이 Remote Config 후보로 폴백).
    val claudeModelFlow: Flow<String?> = preferenceFlow.map { preferences ->
        preferences[CLAUDE_MODEL]
    }

    val dragHandleDockingFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[DRAG_HANDLE_DOCKING] ?: false
    }

    val dockingDelayFlow: Flow<Long> = preferenceFlow.map { preferences ->
        preferences[DOCKING_DELAY] ?: 15000L
    }

    val dragHandleHapticFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[DRAG_HANDLE_HAPTIC] ?: false
    }

    val menuBarVisibilityFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[MENU_BAR_VISIBILITY] ?: true
    }

    val menuBarTransparencyFlow: Flow<Float> = preferenceFlow.map { preferences ->
        preferences[MENU_BAR_TRANSPARENCY] ?: 0.905f
    }

    val menuBarConfigFlow: Flow<MenuConfig> = preferenceFlow.map { preferences ->
        safeEnumValueOf(preferences[MENU_BAR_COMPOSITION], MenuConfig.WHOLE)
    }

    val translationTransparencyFlow: Flow<Float> = preferenceFlow.map { preferences ->
        preferences[TRANSLATION_TRANSPARENCY] ?: 0.905f
    }

    val translationCloseDelayFlow: Flow<Long> = preferenceFlow.map { preferences ->
        preferences[TRANSLATION_CLOSE_DELAY] ?: 1600L
    }

    val replyTransparencyFlow: Flow<Float> = preferenceFlow.map { preferences ->
        preferences[REPLY_TRANSPARENCY] ?: 0.905f
    }

    val automaticTranslationPlaybackFlow: Flow<Boolean> = preferenceFlow.map { preferences ->
        preferences[AUTOMATIC_TRANSLATION_PLAYBACK] ?: false
    }

    val ttsSpeechRateFlow: Flow<Float> = preferenceFlow.map { preferences ->
        preferences[TTS_SPEECH_RATE] ?: 1.0f
    }

    val ttsReadTargetFlow: Flow<TTSReadTarget> = preferenceFlow.map { preferences ->
        safeEnumValueOf(preferences[TTS_READ_TARGET], TTSReadTarget.SOURCE)
    }

    val ttsOrderedVoiceNamesFlow: Flow<List<String>> = preferenceFlow.map { preferences ->
        preferences[TTS_ORDERED_VOICE_NAMES]?.let { jsonString ->
            gson.fromJson(jsonString, object : TypeToken<List<String>>() {}.type)
        } ?: listOf()
    }

    val sourceLanguageCodeHistoryFlow: Flow<List<String>> = preferenceFlow.map { preferences ->
        preferences[SOURCE_LANGUAGE_CODE_HISTORY]?.let { jsonString ->
            gson.fromJson(jsonString, object : TypeToken<List<String>>() {}.type)
        } ?: listOf()
    }

    val targetLanguageCodeHistoryFlow: Flow<List<String>> = preferenceFlow.map { preferences ->
        preferences[TARGET_LANGUAGE_CODE_HISTORY]?.let { jsonString ->
            gson.fromJson(jsonString, object : TypeToken<List<String>>() {}.type)
        } ?: listOf()
    }

    fun addOrUpdateLanguageHistory(newLanguageCode: String, isSourceLanguage: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            // 언어 저장
            preferenceDataStore.edit { preferences ->
                preferences[if (isSourceLanguage) SOURCE_LANGUAGE_CODE else TARGET_LANGUAGE_CODE] = newLanguageCode
            }

            // 히스토리 저장
            val currentHistory = if (isSourceLanguage) sourceLanguageCodeHistoryFlow.first() else targetLanguageCodeHistoryFlow.first() // 현재 저장된 언어 코드 리스트
            // 새 언어 코드를 리스트의 시작 부분에 추가
            val updatedList = mutableListOf(newLanguageCode)
            // 새 언어 코드가 이미 리스트에 있으면 제거하고, 그렇지 않으면 유지 (대소문자 구분 없음)
            currentHistory.filter { !it.equals(newLanguageCode, ignoreCase = true) }.forEach { updatedList.add(it) }
            // 리스트의 크기가 5를 초과하지 않도록 조정
            val finalList = if (updatedList.size > 5) updatedList.take(5) else updatedList
            // 변경된 리스트를 JSON 문자열로 변환 후 저장
            val jsonString = gson.toJson(finalList)
            update(if (isSourceLanguage) SOURCE_LANGUAGE_CODE_HISTORY else TARGET_LANGUAGE_CODE_HISTORY, jsonString)
        }
    }

    fun addOrUpdateOrderedVoiceNames(orderedVoices: List<Voice>) {
        CoroutineScope(Dispatchers.IO).launch {
            val orderedVoiceNames: List<String> = orderedVoices.map { voice -> voice.name }
            val jsonString = gson.toJson(orderedVoiceNames)
            update(TTS_ORDERED_VOICE_NAMES, jsonString)
        }
    }

    private fun getCurrentLocale(): Locale {
        return context.resources.configuration.locales.get(0)
    }

}









