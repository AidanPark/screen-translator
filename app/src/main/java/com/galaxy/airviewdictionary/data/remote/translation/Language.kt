package com.galaxy.airviewdictionary.data.remote.translation

import com.galaxy.airviewdictionary.data.local.vision.WritingDirection
import java.util.Locale

/**
 * Holds the language code (i.e. "en") and the corresponding localized full language name
 * (i.e. "English")
 */
class Language(val code: String) : Comparable<Language> {

    val displayName: String = displayNameMap[code.uppercase()] ?: Locale(code).displayName

    val displayShortName: String = code.uppercase().substring(0, 2)

    val localDisplayName: String = Locale(code).getDisplayLanguage(Locale(code))

    /**
     * Non-Spacing Language 에서는 TextDetectMode.WORD 를 지원하지 않는다.
     */
    val isNonSpacingLanguage: Boolean by lazy {
        isNonSpacingLanguage(code)
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }

        if (other !is Language) {
            return false
        }

        return other.code.equals(code, ignoreCase = true)
    }


    val supportKitTypes: MutableList<TranslationKitType> = mutableListOf()

    override fun toString(): String {
        return "$code $displayName $supportKitTypes"
    }

    override fun compareTo(other: Language): Int {
        return this.displayName.compareTo(other.displayName)
    }

    override fun hashCode(): Int {
        return code.hashCode()
    }


    companion object {

        /**
         * 세로쓰기 가능한 언어인지의 여부
         *
         * LTR: Left-to-Right (왼쪽에서 오른쪽으로)
         * RTL: Right-to-Left (오른쪽에서 왼쪽으로)
         * TTB: Top-to-Bottom (위에서 아래로)
         */
        fun isVerticalWritingSupported(languageCode: String): Boolean {
            val verticalWritingSupportedLanguages = listOf(
                "zh",    // 중국어 (Chinese) - 가로쓰기(LTR) 세로쓰기(TTB, RTL)
                "zh-CN", // Chinese (simplified)
                "zh-TW", // Chinese (traditional)
                "ZH-HANS", // Chinese (simplified)
                "ZH-HANT", // Chinese (traditional)
                "zh-Hans", // Chinese (simplified)
                "zh-Hant", // Chinese (traditional)
                "ja",    // 일본어 (Japanese) - 가로쓰기(LTR) 세로쓰기(TTB, RTL)
                "ko",    // 한국어 (Korean) - 가로쓰기(LTR) 세로쓰기(TTB, RTL)
                "mn",    // 몽골어 (Mongolian) - 가로쓰기(LTR) 세로쓰기(TTB, LTR)
                "bo",    // 티베트어 (Tibetan) - 가로쓰기(LTR) 세로쓰기(TTB, LTR)
                "vi",    // 베트남어 (Vietnamese) - 가로쓰기(LTR) 세로쓰기(TTB, RTL) (한자-놈 문자로 쓰일 때)
                "kk",    // 카자흐어 (Kazakh) - 가로쓰기(LTR) 세로쓰기(TTB, RTL) (아랍 문자로 쓰일 때)
                "mnc",   // 만주어 (Manchu) - 가로쓰기(LTR) 세로쓰기(TTB, LTR)
                "ug",    // 위구르어 (Uyghur) - 가로쓰기(LTR) 세로쓰기(TTB, RTL) (아랍 문자를 사용하는 경우)
                "tk"     // 투르크멘어 (Turkmen) - 가로쓰기(LTR) 세로쓰기(TTB, RTL) (아랍 문자를 사용하는 경우)
            )

            return verticalWritingSupportedLanguages.any { code ->
                languageCode.startsWith(code)
            }
        }

        /**
         * 언어별 텍스트 쓰기 방향
         * @param languageCode
         * @param isVerticalWriting 세로쓰기 여부
         */
        fun writingDirection(languageCode: String, isVerticalWriting: Boolean): WritingDirection {
            val rtlLanguages = listOf("ar", "he", "fa", "ur", "ps", "sd", "ckb", "dv", "ug")

            return when {
                isVerticalWriting -> {
                    when (languageCode) {
                        "zh", "zh-CN", "zh-TW", "ZH-HANS", "ZH-HANT", "zh-Hans", "zh-Hant", "ja", "ko", "vi" -> WritingDirection.TTB_RTL
                        "mn", "bo", "mnc" -> WritingDirection.TTB_LTR
                        "kk", "ug", "tk" -> WritingDirection.TTB_RTL // Assuming vertical writing follows RTL when using Arabic script
                        else -> WritingDirection.TTB_RTL
                    }
                }

                rtlLanguages.any { code -> languageCode.startsWith(code) } -> WritingDirection.RTL
                else -> WritingDirection.LTR
            }
        }

        /**
         * 띄어쓰기를 하지 않는 언어인지의 여부
         */
        fun isNonSpacingLanguage(languageCode: String): Boolean {
            val nonSpacingLanguages = listOf(
                "zh", // Chinese
                "zh-CN", // Chinese (simplified)
                "zh-TW", // Chinese (traditional)
                "ZH-HANS", // Chinese (simplified)
                "ZH-HANT", // Chinese (traditional)
                "zh-Hans", // Chinese (simplified)
                "zh-Hant", // Chinese (traditional)
                "ja", // Japanese
                "th", // Thai
                "lo", // Lao
                "my", // Burmese
                "km"  // Khmer
            )

            return nonSpacingLanguages.any { code ->
                languageCode.startsWith(code, ignoreCase = true)
            }
        }

        val displayNameMap = mapOf(
            "AUTO" to "Auto",
            "ZH-CN" to "${Locale("zh").displayName} (simplified)", // GOOGLE
            "ZH-TW" to "${Locale("zh").displayName} (traditional)", // GOOGLE
            "EN-GB" to "${Locale("en").displayName} (British)", // DeepLKit
            "EN-US" to "${Locale("en").displayName} (American)", // DeepLKit
            "PT-BR" to "${Locale("pt").displayName} (Brazilian)", // DeepLKit
            "PT-PT" to "${Locale("pt").displayName} (excluding Brazilian)", // DeepLKit
            "ZH-HANS" to "${Locale("zh").displayName} (simplified)", // DeepLKit
            "ZH-HANT" to "${Locale("zh").displayName} (traditional)", // DeepLKit
        )

        /**
         * 표준 Locale 표시명이 마땅치 않은(표시 이름이 부실한) 언어 코드 목록.
         * 여기에 담긴 언어는 언어 선택 목록에서 비-라틴 로케일 사용자 기준으로 맨 아래에 정렬된다.
         * ([TranslationRepository]의 supportedLanguagesAsSource/supportedLanguagesAsTarget 에서 사용)
         *
         * 과거 Azure 엔진 전용 이색 언어(Klingon, Otomi 등)가 있었으나 Azure 제거로 지금은 비어 있다.
         * 앞으로 표시명이 부실한 언어를 지원하는 엔진을 추가하면 해당 코드를 여기에 넣으면 동일하게 하단 정렬된다.
         */
        val noDisplayNameList = listOf<String>()
    }
}