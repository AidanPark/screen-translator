package com.galaxy.airviewdictionary.data.local.secure

import android.content.Context
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec


object SecureStoreKey {
    const val TRANSLATE_TRIAL_COUNT = "translate_trial_count"

    const val DEEPL_API_KEY = "deepl_api_key"

    const val OPENAI_API_KEY = "openai_api_key"

    const val GEMINI_API_KEY = "gemini_api_key"

    const val CLAUDE_API_KEY = "claude_api_key"

    // 최초 사용 시각(참여도 분석 기준점). 저장 문자열은 기존 사용자 데이터 연속성을 위해 유지한다.
    const val FIRST_USE_TIME = "trial_start_time"
}

/**
 * 앱 secret 저장소
 */
object SecureStore {
    private const val TAG = "SecureStore"

    private const val KEYSTORE_ALIAS = "SecureKeyAlias"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_MODE = "AES/GCM/NoPadding"
    private const val PREFS_NAME = "SecurePrefs"
    private const val IV_SUFFIX = "_iv"

    /**
     * 키스토어 지원 여부 확인
     */
    fun isSupported(): Boolean {
        return try {
            // Attempt to initialize Keystore
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            true
        } catch (e: Exception) {
            false
        }
    }

    // Generate or load the secret key
    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        } else {
            (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
    }

    // store plainText
    fun set(context: Context, key: String, plainText: String) {
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(AES_MODE).apply { init(Cipher.ENCRYPT_MODE, secretKey) }
        val encryptedData = cipher.doFinal(plainText.toByteArray())
        val iv = cipher.iv

        val targetContext = context.createDeviceProtectedStorageContext()
        val prefs = targetContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            putString(key, Base64.encodeToString(encryptedData, Base64.DEFAULT))
            putString(key + IV_SUFFIX, Base64.encodeToString(iv, Base64.DEFAULT))
        }
    }

    fun set(context: Context, key: String, plainText: SecureString) {
        set(context, key, plainText.get())
    }

    // retrieve plainText
    fun get(context: Context, key: String): SecureString? {
        val targetContext = context.createDeviceProtectedStorageContext()
        val prefs = targetContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedData = prefs.getString(key, null)?.let { Base64.decode(it, Base64.DEFAULT) }
        val iv = prefs.getString(key + IV_SUFFIX, null)?.let { Base64.decode(it, Base64.DEFAULT) }

        if (encryptedData == null || iv == null) return null

        return try {
            val secretKey = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(AES_MODE).apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            }
            Timber.tag(TAG).d("get $context, $key")
            SecureString(String(cipher.doFinal(encryptedData)))
        } catch (e: AEADBadTagException) {
            null
        }
    }

    // Check if a key exists in the direct boot storage
    fun containsKey(context: Context, key: String): Boolean {
        val targetContext = context.createDeviceProtectedStorageContext()
        val prefs = targetContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(key) && prefs.contains(key + IV_SUFFIX)
    }

    /**
     * 키 삭제
     */
    fun deleteKey(alias: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(alias)
    }


}

