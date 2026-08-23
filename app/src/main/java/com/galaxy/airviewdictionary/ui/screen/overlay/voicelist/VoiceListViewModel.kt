package com.galaxy.airviewdictionary.ui.screen.overlay.voicelist

import android.content.Context
import android.speech.tts.Voice
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.local.tts.TTSReadTarget
import com.galaxy.airviewdictionary.data.local.tts.TTSRepository
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.TranslationRepository
import com.galaxy.airviewdictionary.extensions.language
import com.galaxy.airviewdictionary.extensions.languageCode
import com.galaxy.airviewdictionary.extensions.voiceNameMatchesLanguage
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber


/** 목소리 목록의 한 행. [isActive] 는 해당 언어에서 실제 사용될 목소리인지를 나타낸다. */
data class VoiceListItem(
    val id: Int,
    val voice: Voice,
    val language: Language,
    val isActive: Boolean,
)

@Suppress("UNCHECKED_CAST")
class VoiceListViewModelFactory(
    private val applicationContext: Context,
    private val preferenceRepository: PreferenceRepository,
    private val translationRepository: TranslationRepository,
    private val ttsRepository: TTSRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VoiceListViewModel::class.java)) {
            return VoiceListViewModel(
                applicationContext = applicationContext,
                preferenceRepository = preferenceRepository,
                translationRepository = translationRepository,
                ttsRepository = ttsRepository,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel Class")
    }
}

class VoiceListViewModel(
    private val applicationContext: Context,
    val preferenceRepository: PreferenceRepository,
    private val translationRepository: TranslationRepository,
    private val ttsRepository: TTSRepository,
) : ViewModel() {

    private val TAG = javaClass.simpleName

    fun playSampleVoice(voice: Voice, text_: String = "It's a voice like this.") {
        viewModelScope.launch {
            val ttsSpeechRate = preferenceRepository.ttsSpeechRateFlow.first()
            var text = text_

            if (voice.language.code != "en") {
                val response: TranslationResponse = translationRepository.request(
                    TranslationKitType.GOOGLE,
                    "en",
                    voice.language.code,
                    text
                )
                if (response is TranslationResponse.Success) {
                    text = response.result.resultText ?: text
                }
            }

            ttsRepository.playTestTTS(
                text,
                ttsSpeechRate,
                voice
            )
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    //                                                                                            //
    //                                       preference                                           //
    //                                                                                            //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    private val ttsOrderedVoiceNamesFlow: Flow<List<String>> = preferenceRepository.ttsOrderedVoiceNamesFlow

    val voicesFlow = combine(
        ttsOrderedVoiceNamesFlow,
        preferenceRepository.ttsReadTargetFlow,
        preferenceRepository.sourceLanguageCodeFlow,
        preferenceRepository.targetLanguageCodeFlow,
        preferenceRepository.lastUsedSourceLanguageCodeFlow,
    ) { orderedVoiceNames, ttsReadTarget, sourceLanguageCode, targetLanguageCode, lastUsedSourceLanguageCode ->
        val availableVoices = ttsRepository.availableVoicesFlow.filterNotNull().first()

        // 표시 순서는 저장(선택) 상태와 무관하게 항상 고정한다: 언어 이름순 → 그룹 내 목소리 이름순.
        // 선택은 라디오 표시로만 나타나고, 항목 위치는 움직이지 않는다.
        val sorted = availableVoices
            .map { voice -> voice to voice.language }
            .sortedWith(compareBy({ it.second.displayName }, { it.first.name }))

        // 읽기 대상 언어 그룹만 최상단으로 올린다 (그룹 단위 이동이라 선택과 무관하게 안정적).
        // - 읽기 대상이 타겟: 타겟 언어
        // - 읽기 대상이 소스: 소스 언어 (auto 면 마지막 번역에서 확인된 소스 언어, 이력이 없으면 시스템 언어)
        val readLanguageCode = when (ttsReadTarget) {
            TTSReadTarget.SOURCE ->
                if (sourceLanguageCode == "auto") {
                    lastUsedSourceLanguageCode ?: applicationContext.resources.configuration.locales.get(0).language
                } else sourceLanguageCode
            TTSReadTarget.TARGET -> targetLanguageCode
        }
        val (readLanguageVoices, otherVoices) = sorted.partition {
            voiceNameMatchesLanguage(it.first.name, readLanguageCode)
        }
        Timber.tag(TAG).d("readTarget $ttsReadTarget readLanguageCode $readLanguageCode top ${readLanguageVoices.size}")

        // 각 언어의 활성 목소리 = 저장 목록에서 첫 매칭(설치된 것만), 없으면 기기 목록에서 첫 매칭.
        // (TTSRepository 의 목소리 선택 규칙과 동일)
        fun activeVoiceNameFor(languageCode: String): String? =
            orderedVoiceNames.firstOrNull { name ->
                voiceNameMatchesLanguage(name, languageCode) && availableVoices.any { it.name == name }
            } ?: availableVoices.firstOrNull { voiceNameMatchesLanguage(it.name, languageCode) }?.name

        (readLanguageVoices + otherVoices).mapIndexed { index, (voice, language) ->
            VoiceListItem(
                id = index,
                voice = voice,
                language = language,
                isActive = voice.name == activeVoiceNameFor(voice.languageCode),
            )
        }
    }

    /**
     * 해당 목소리를 그 언어의 활성 목소리로 선택한다.
     * 저장 형식은 기존 orderedVoiceNames 를 유지한다 — "각 언어의 첫 매칭 = 활성" 이므로
     * 선택된 목소리 이름을 저장 목록 맨 앞으로 옮기면 된다. 표시 순서에는 영향이 없다.
     * (활성 목소리 재적용은 TTSRepository.collectReadTargetLanguageVoice 가 flow 로 처리한다)
     */
    fun selectVoice(selected: VoiceListItem) {
        viewModelScope.launch {
            val availableVoices = ttsRepository.availableVoicesFlow.filterNotNull().first()
            val orderedNames = preferenceRepository.ttsOrderedVoiceNamesFlow.first().toMutableList()
            // 저장 목록에 없는 기기 목소리를 뒤에 보충해 전체 목록을 만든 뒤, 선택을 맨 앞으로.
            availableVoices.forEach { voice -> if (voice.name !in orderedNames) orderedNames.add(voice.name) }
            orderedNames.remove(selected.voice.name)
            orderedNames.add(0, selected.voice.name)
            val orderedVoices = orderedNames.mapNotNull { name -> availableVoices.find { it.name == name } }
            preferenceRepository.addOrUpdateOrderedVoiceNames(orderedVoices)
        }
    }

    init {
        Timber.tag(TAG).i("#### init ####")
        translationRepository.acquire()
        ttsRepository.acquire()
    }

    override fun onCleared() {
        translationRepository.release()
        ttsRepository.release()
        super.onCleared()
    }
}








