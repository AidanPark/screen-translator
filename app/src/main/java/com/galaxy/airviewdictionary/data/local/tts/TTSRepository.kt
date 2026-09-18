package com.galaxy.airviewdictionary.data.local.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.galaxy.airviewdictionary.data.AVDRepository
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.extensions.voiceNameMatchesLanguage
import com.galaxy.airviewdictionary.ui.screen.overlay.translation.TTSStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TTSRepository @Inject constructor(
    @ApplicationContext val context: Context,
    private val preferenceRepository: PreferenceRepository,
) : AVDRepository() {

    private var tts: TextToSpeech? = null
    private var ttsForText: TextToSpeech? = null

    val ttsStatusFlow = MutableStateFlow(TTSStatus.Uninitialized)

    val currentVoiceFlow = MutableStateFlow<Voice?>(null)

    val availableVoicesFlow = MutableStateFlow<List<Voice>>(emptyList())

    private fun initTTS(locale: Locale, attempts: Int = 0) {
        Timber.tag(TAG).i("=========================== initTTS ========================== $locale $attempts")
        // 이미 shutdown 된 인스턴스를 또 shutdown 하면 내부 unbindService 가
        // IllegalArgumentException("Service not registered") 를 던진다.
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null

        tts = TextToSpeech(context) { status ->
            launchInAVDCoroutineScope {
                delay(500)
                val success = status == TextToSpeech.SUCCESS
                        && tts?.voices?.isNotEmpty() ?: false
                        && tts?.voice != null

                Timber.tag(TAG).i("TextToSpeech result status $status success $success")

                if (success) {
                    tts?.setLanguage(locale)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            Timber.tag(TAG).i("tts onStart $utteranceId")
                            ttsStatusFlow.value = TTSStatus.Playing
                        }

                        override fun onDone(utteranceId: String?) {
                            Timber.tag(TAG).i("tts onDone $utteranceId")
                            ttsStatusFlow.value = TTSStatus.Idle
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            Timber.tag(TAG).i("tts onError $utteranceId")
                            ttsStatusFlow.value = TTSStatus.Idle
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            Timber.tag(TAG).i("tts onStop $utteranceId $interrupted")
                            ttsStatusFlow.value = TTSStatus.Idle
                        }
                    })
                    ttsStatusFlow.value = TTSStatus.Idle
                    currentVoiceFlow.value = tts?.voice
                    availableVoicesFlow.value = tts?.voices?.toList() ?: emptyList()
                } else if (attempts < 10) {
                    val delayTime = (2000 + 1000 * attempts).toLong()
                    Timber.tag(TAG).w("initTTS failed, retrying in ${delayTime}ms... Attempt: ${attempts + 1}")
                    delay(delayTime)
                    initTTS(locale, attempts + 1)
                } else {
                    Timber.tag(TAG).e("initTTS completely failed after $attempts attempts.")
                    ttsStatusFlow.value = TTSStatus.Uninitialized
                }
            }
        }
    }

    private fun initTTSForText(attempts: Int = 0) {
        Timber.tag(TAG).i("=========================== initTTSForText ==========================")
        try {
            ttsForText?.shutdown()
        } catch (_: Exception) {
        }
        ttsForText = null

        ttsForText = TextToSpeech(context) { status ->
            launchInAVDCoroutineScope {
                delay(1000)
                val success = status == TextToSpeech.SUCCESS
                        && ttsForText?.voices != null
                        && ttsForText?.voice != null

                Timber.tag(TAG).i("initTTSForText result status $status success $success")

                if (!success && attempts < 10) {
                    val delayTime = (2000 + 1000 * attempts).toLong()
                    Timber.tag(TAG).w("initTTSForText failed, retrying in ${delayTime}ms... Attempt: ${attempts + 1}")
                    delay(delayTime)
                    initTTSForText(attempts + 1)
                }
            }
        }
    }

    /**
     * TTS 활성 목소리를 읽기 대상(소스/타겟) 언어 설정에 연동한다.
     *
     * - 읽기 대상이 소스: 소스 언어를 따른다.
     *   소스가 auto 면 시스템 로케일 언어를 기준으로 한다 (번역 시 감지 언어의 목소리로 교체됨)
     * - 읽기 대상이 타겟: 타겟 언어를 따른다
     * - 해당 언어의 목소리가 기기에 없으면: 직전 목소리를 유지한다
     */
    private fun collectReadTargetLanguageVoice() {
        launchInAVDCoroutineScope {
            combine(
                preferenceRepository.ttsReadTargetFlow,
                preferenceRepository.sourceLanguageCodeFlow,
                preferenceRepository.targetLanguageCodeFlow,
                preferenceRepository.ttsOrderedVoiceNamesFlow,
                availableVoicesFlow,
            ) { readTarget, sourceLanguageCode, targetLanguageCode, orderedVoiceNames, availableVoices ->
                if (availableVoices.isEmpty()) return@combine null // TTS 초기화 전

                val languageCode = when (readTarget) {
                    TTSReadTarget.SOURCE -> if (sourceLanguageCode == "auto") getCurrentLocale().language else sourceLanguageCode
                    TTSReadTarget.TARGET -> targetLanguageCode
                }
                val preferredVoiceName = orderedVoiceNames.firstOrNull { voiceName ->
                    voiceNameMatchesLanguage(voiceName, languageCode)
                } ?: availableVoices.map { it.name }.firstOrNull { voiceName ->
                    voiceNameMatchesLanguage(voiceName, languageCode)
                }
                Timber.tag(TAG).i("collectReadTargetLanguageVoice $readTarget -> $languageCode preferred $preferredVoiceName")
                preferredVoiceName
            }.collect { preferredVoiceName ->
                preferredVoiceName?.let { setVoice(it) }
            }
        }
    }

    fun stopTTS() {
        Timber.tag(TAG).i("------------ stopTTS --------------")
        tts?.stop()
    }

    /** clearTTS 로 엔진이 해제된 상태인지. 재사용 시작 시 재초기화 판단 기준. */
    @Volatile
    private var ttsCleared = false

    private fun clearTTS() {
        // onZeroReferences 는 비동기로 실행되므로, 그 사이 서비스가 재시작해
        // 참조가 되살아났다면 살아있는 엔진을 죽이지 않는다.
        if (hasActiveReferences()) {
            Timber.tag(TAG).i("clearTTS skipped — references re-acquired")
            return
        }
        Timber.tag(TAG).i("------------ clearTTS --------------")
        ttsStatusFlow.value = TTSStatus.Uninitialized
        ttsCleared = true
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        try {
            ttsForText?.stop()
        } catch (_: Exception) {
        }
        try {
            ttsForText?.shutdown()
        } catch (_: Exception) {
        }
        // shutdown 된 인스턴스는 다시 바인딩되지 않는다. 참조를 남겨두면
        // 재사용 시 initTTS 가 죽은 인스턴스를 또 shutdown 하려다 크래시한다.
        tts = null
        ttsForText = null
    }

    /**
     * 서비스 재시작 등으로 저장소가 재사용될 때 TTS 를 재초기화한다.
     * (shutdown 된 TextToSpeech 인스턴스는 엔진에 다시 바인딩되지 않아
     * 이후 모든 speak/setVoice 가 "not bound to TTS engine" 으로 무음 실패한다)
     */
    override fun onFirstReference() {
        if (ttsCleared) {
            ttsCleared = false
            Timber.tag(TAG).i("onFirstReference — reinitializing TTS after clear")
            initTTS(getCurrentLocale())
            initTTSForText()
        }
    }

    private fun getCurrentLocale(): Locale {
        return context.resources.configuration.locales.get(0)
    }

    fun setVoice(voiceName: String) {
        launchInAVDCoroutineScope {
            Timber.tag(TAG).i("setVoice ${tts?.voice} to $voiceName")
            val matchingVoice = availableVoicesFlow.first()?.firstOrNull { it.name == voiceName }
            Timber.tag(TAG).i("matchingVoice  $matchingVoice")
            matchingVoice?.let {
                tts?.setVoice(it)
                currentVoiceFlow.value = it
            }
        }
    }

    fun playTTS(text: String, speechRate: Float? = 1.0f) {
        Timber.tag(TAG).i("playTTS Starting TTS playback for text: $text")
        val utteranceId = UUID.randomUUID().toString()
        speechRate?.let { tts?.setSpeechRate(it) }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /**
     * 지정한 언어에 맞는 목소리를 확정한 뒤 재생한다.
     * (읽기 대상이 소스이고 소스 언어가 auto 인 경우, 번역 시 감지된 언어의 목소리로 읽기 위함.
     * 목소리 전환 flow 와 재생 사이의 경쟁을 피하기 위해 같은 코루틴에서 순서를 보장한다)
     *
     * - 현재 목소리가 이미 해당 언어면: 유지 (사용자가 고른 목소리 존중)
     * - 아니면: 우선순위 목록에서 찾고, 없으면 기기 목소리 전체에서 찾는다
     * - 해당 언어의 목소리가 없으면: 현재 목소리로 재생한다
     */
    fun playTTSForLanguage(text: String, languageCode: String?, speechRate: Float? = 1.0f) {
        launchInAVDCoroutineScope {
            if (languageCode != null) {
                val currentVoice = tts?.voice
                if (currentVoice == null || !voiceNameMatchesLanguage(currentVoice.name, languageCode)) {
                    val availableVoices = availableVoicesFlow.first()
                    val orderedVoiceNames = preferenceRepository.ttsOrderedVoiceNamesFlow.first()
                    val matchingVoiceName = orderedVoiceNames.firstOrNull { voiceNameMatchesLanguage(it, languageCode) }
                        ?: availableVoices.map { it.name }.firstOrNull { voiceNameMatchesLanguage(it, languageCode) }
                    val matchingVoice = matchingVoiceName?.let { name -> availableVoices.firstOrNull { it.name == name } }
                    Timber.tag(TAG).i("playTTSForLanguage $languageCode -> $matchingVoiceName")
                    matchingVoice?.let {
                        tts?.setVoice(it)
                        currentVoiceFlow.value = it
                    }
                }
            }
            playTTS(text, speechRate)
        }
    }

    fun playTestTTS(text: String, speechRate: Float? = 1.0f, voice: Voice? = null) {
        Timber.tag(TAG).i("playTestTTS Starting TTS playback for text: $text")
        val utteranceId = UUID.randomUUID().toString()
        speechRate?.let { ttsForText?.setSpeechRate(it) }
        voice?.let { ttsForText?.setVoice(it) }
        val result = ttsForText?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Timber.tag(TAG).i("playTestTTS speak result: $result")
    }

    override fun onZeroReferences() {
        Timber.tag(TAG).i("onZeroReferences called, cancelling all coroutines and clearing TTS resources")
        clearTTS()
    }

    init {
        initTTS(getCurrentLocale())
        initTTSForText()
        collectReadTargetLanguageVoice()
    }
}
