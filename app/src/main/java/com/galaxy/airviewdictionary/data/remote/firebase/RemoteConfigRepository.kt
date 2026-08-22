package com.galaxy.airviewdictionary.data.remote.firebase

import android.content.Context
import com.galaxy.airviewdictionary.R
import com.google.firebase.Firebase
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue
import com.google.firebase.remoteconfig.get
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class RemoteConfigRepository @Inject constructor(@ApplicationContext val context: Context) {

    private val TAG = javaClass.simpleName

    companion object PreferencesKeys {
        const val SERVICE_AVAILABLE_KEY = "service_available"
        const val LATEST_VERSION_CODE_KEY = "latest_version_code"
        const val FORCE_UPDATE_VERSION_CODE_KEY = "force_update_version_code"
        const val AD_UNIT_ID = "ad_unit_id"

        // OpenAI 번역에서 고를 수 있는 모델 후보 (쉼표 구분). 설정 UI 가 이 목록을 노출한다.
        const val OPENAI_TRANSLATE_MODELS = "openai_translate_models"

        // Gemini 번역에서 고를 수 있는 모델 후보 (쉼표 구분).
        const val GEMINI_TRANSLATE_MODELS = "gemini_translate_models"

        // Claude 번역에서 고를 수 있는 모델 후보 (쉼표 구분).
        const val CLAUDE_TRANSLATE_MODELS = "claude_translate_models"
    }

    /**
     * OpenAI 번역 모델 후보 목록. Remote Config 의 쉼표 구분 문자열을 파싱한다.
     * (기본값은 res/xml/remote_config_defaults.xml 참조)
     */
    fun getOpenAiTranslateModels(): List<String> {
        return remoteConfig[OPENAI_TRANSLATE_MODELS].asString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Gemini 번역 모델 후보 목록. Remote Config 의 쉼표 구분 문자열을 파싱한다.
     */
    fun getGeminiTranslateModels(): List<String> {
        return remoteConfig[GEMINI_TRANSLATE_MODELS].asString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Claude 번역 모델 후보 목록. Remote Config 의 쉼표 구분 문자열을 파싱한다.
     */
    fun getClaudeTranslateModels(): List<String> {
        return remoteConfig[CLAUDE_TRANSLATE_MODELS].asString()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private val remoteConfig: FirebaseRemoteConfig = Firebase.remoteConfig

    private val _remoteConfigFlow = MutableStateFlow<Map<String, FirebaseRemoteConfigValue>>(emptyMap())

    val remoteConfigFlow: StateFlow<Map<String, FirebaseRemoteConfigValue>> get() = _remoteConfigFlow

    private fun retrieveConfig() {
        Timber.tag(TAG).d("SERVICE_AVAILABLE_KEY ${remoteConfig[SERVICE_AVAILABLE_KEY].asString()}")
        Timber.tag(TAG).d("LATEST_VERSION_CODE_KEY ${remoteConfig[LATEST_VERSION_CODE_KEY].asString()}")
        Timber.tag(TAG).d("FORCE_UPDATE_VERSION_CODE_KEY ${remoteConfig[FORCE_UPDATE_VERSION_CODE_KEY].asString()}")
        Timber.tag(TAG).d("AD_UNIT_ID ${remoteConfig[AD_UNIT_ID].asString()}")
        _remoteConfigFlow.value = remoteConfig.all
    }

    init {
        remoteConfig.setConfigSettingsAsync(remoteConfigSettings {
            minimumFetchIntervalInSeconds = 60 * 60 * 24
        })

        remoteConfig.setDefaultsAsync(R.xml.remote_config_defaults)

        // [START fetch_config_with_callback]
        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val updated = task.result
                    Timber.tag(TAG).d("Config params updated: $updated")
                    retrieveConfig()
                } else {
                    Timber.tag(TAG).d("Fetch failed")
                }
            }
        // [END fetch_config_with_callback]

        // [START add_config_update_listener]
        remoteConfig.addOnConfigUpdateListener(object : ConfigUpdateListener {
            override fun onUpdate(configUpdate: ConfigUpdate) {
                Timber.tag(TAG).i(TAG, "Updated keys: %s", configUpdate.updatedKeys)

                remoteConfig.activate().addOnCompleteListener {
                    Timber.tag(TAG).i("------------------- onUpdate ------------------")
                    retrieveConfig()
                }
            }

            override fun onError(error: FirebaseRemoteConfigException) {
                Timber.tag(TAG).w("Config update error with code: %s", error.code)
            }
        })
    }
}










