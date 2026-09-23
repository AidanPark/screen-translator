package com.galaxy.airviewdictionary.ui.screen.reply

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.galaxy.airviewdictionary.data.local.preference.PreferenceRepository
import com.galaxy.airviewdictionary.data.remote.firebase.AnalyticsRepository
import com.galaxy.airviewdictionary.data.remote.translation.TranslationRepository
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ReplyViewModel @Inject constructor(
    val preferenceRepository: PreferenceRepository,
    private val translationRepository: TranslationRepository,
    val analyticsRepository: AnalyticsRepository
) : ViewModel() {

    private val TAG = javaClass.simpleName

    private var sourceLanguageCode = "en"

    fun setSourceLanguageCode(sourceLanguageCode: String) {
        this.sourceLanguageCode = sourceLanguageCode
    }

    private var targetLanguageCode = "en"

    fun setTargetLanguageCode(targetLanguageCode: String) {
        this.targetLanguageCode = targetLanguageCode
    }

    /**
     * Get translation from TranslationRepository
     */
    private val _request = MutableStateFlow<String>("")

    fun request(sourceText: String) {
        _request.value = sourceText
    }

    fun cancelRequest() {
        _translationFlow.value = Transaction()
    }

    // Translation state
    private val _translationFlow = MutableStateFlow(Transaction())

    val translationFlow: StateFlow<Transaction> = _translationFlow.asStateFlow()

    private fun collectTranslation() {
        viewModelScope.launch {
            combine(
                _request,
                preferenceRepository.translationKitTypeFlow
            ) { sourceText, translationKitType ->
                Pair(sourceText, translationKitType)
            }.collect { (sourceText, translationKitType) ->
                if (sourceText.isNotBlank()) {
                    _translationFlow.value = Transaction(
                        requestedSourceLanguageCode = sourceLanguageCode,
                        targetLanguageCode = targetLanguageCode,
                        sourceText = sourceText,
                        translationKitType = translationKitType,
                    )

                    val translationResponse = translationRepository.request(
                        translationKitType, sourceLanguageCode, targetLanguageCode, sourceText
                    )

                    if (translationResponse is TranslationResponse.Success &&
                        _translationFlow.value.requestedSourceLanguageCode == sourceLanguageCode &&
                        _translationFlow.value.targetLanguageCode == translationResponse.result.targetLanguageCode &&
                        _translationFlow.value.sourceText == translationResponse.result.sourceText
                    ) {
                        Timber.tag(TAG).d("translationResponse ${translationResponse.result}")
                        _translationFlow.update {
                            it.copy(
                                resolvedSourceLanguageCode = translationResponse.result.resolvedSourceLanguageCode,
                                resultText = translationResponse.result.resultText,
                                modelName = translationResponse.result.modelName,
                            )
                        }
                    } else if (translationResponse is TranslationResponse.Error) {
                        Timber.tag(TAG).d("Response Error ${translationResponse.t}")
                    }
                }
            }
        }
    }


    init {
        collectTranslation()
        translationRepository.acquire()
    }

    override fun onCleared() {
        translationRepository.release()
        super.onCleared()
    }
}
