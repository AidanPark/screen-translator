package com.galaxy.airviewdictionary.data.remote.translation.goolge

import com.galaxy.airviewdictionary.data.remote.translation.TranslationKit
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.di.GoogleWebRetrofit
import com.galaxy.airviewdictionary.data.remote.translation.Language
import com.galaxy.airviewdictionary.data.remote.translation.Transaction
import com.galaxy.airviewdictionary.data.remote.translation.TranslationResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import retrofit2.HttpException
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.UnsupportedEncodingException
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber


@Singleton
class GoogleWebKit @Inject constructor(@GoogleWebRetrofit private val googleWebService: GoogleWebService) : TranslationKit() {

    override fun available(): Boolean {
        return true
    }

    private val supportedSourceLanguageCodes: List<String> by lazy {
        mutableListOf(*supportedLanguageCodes)
            .apply { add(0, "auto") }
            .toList()
    }

    private val supportedTargetLanguageCodes: List<String> by lazy {
        supportedLanguageCodes.toList()
    }

    override val supportedLanguagesAsSource: List<Language> by lazy {
        supportedSourceLanguageCodes.map { Language(it).apply { supportKitTypes.add(TranslationKitType.GOOGLE) } }
    }

    override val supportedLanguagesAsTarget: List<Language> by lazy {
        supportedTargetLanguageCodes.map { Language(it).apply { supportKitTypes.add(TranslationKitType.GOOGLE) } }
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

    private fun token(a: String): String {
        var b = 406_644L
        val b1 = 3_293_161_072L
        val jd = "."
        val sb = "+-a^+6"
        val zb = "+-3^+b+-f"

        val e = mutableListOf<Long>()
        var g = 0

        while (g < a.length) {
            var m = a[g].code.toLong()
            when {
                m < 128 -> e.add(m)
                m < 2048 -> {
                    e.add(m shr 6 or 192)
                    e.add(m and 63 or 128)
                }
                m and 64512 == 55296L && g + 1 < a.length && a[g + 1].code.toLong() and 64512 == 56320L -> {
                    m = 65536 + ((m and 1023) shl 10) + (a[++g].code.toLong() and 1023)
                    e.add(m shr 18 or 240)
                    e.add(m shr 12 and 63 or 128)
                    e.add(m shr 6 and 63 or 128)
                    e.add(m and 63 or 128)
                }
                else -> {
                    e.add(m shr 12 or 224)
                    e.add(m shr 6 and 63 or 128)
                    e.add(m and 63 or 128)
                }
            }
            g++
        }

        for (f in e) {
            b = (b + f) and 0xFFFFFFFFL
            b = rl(b, sb)
        }
        b = rl(b, zb)
        b = ((b xor b1) and 0xFFFFFFFFL)

        if (b < 0) {
            b = (b and 2147483647) + 2147483648
        }
        b %= 1_000_000
        return "${b}${jd}${((b xor 406_644L) and 0xFFFFFFFFL)}"
    }

    private fun rl(a: Long, b: String): Long {
        var result = a
        var c = 0
        while (c < b.length - 2) {
            val d = b[c + 2].let { if (it >= 'a') it.code - 87 else it.toString().toInt() }
            val shift = if (b[c + 1] == '+') result ushr d else result shl d
            result = if (b[c] == '+') (result + shift) and 0xFFFFFFFFL else (result xor shift) and 0xFFFFFFFFL
            c += 3
        }
        return result
    }

    override suspend fun request(
        sourceLanguageCode: String,
        targetLanguageCode: String,
        sourceText: String
    ): TranslationResponse {

        var detectedLanguageCode = sourceLanguageCode
        val resultText: String

        try {
            val responseBody = googleWebService.send(
                sl = sourceLanguageCode,
                tl = targetLanguageCode,
                hl = sourceLanguageCode,
                tk = token(sourceText.trim { it <= ' ' }),
                sourceText = sourceText.trim { it <= ' ' },
            )
            val responseJson = withContext(Dispatchers.IO) {
                val `is` = responseBody.byteStream()
                val gis = GZIPInputStream(`is`)
                val `in` = BufferedReader(InputStreamReader(gis, "UTF-8"))
                var inputLine: String?
                val sb = StringBuilder()
                while (`in`.readLine().also { inputLine = it } != null) {
                    sb.append(inputLine)
                }
                sb.toString()
            }
            val jsonArr = JSONArray(responseJson)
            val jsonArr_0 = jsonArr[0] as JSONArray
            val originStringBuilder = StringBuilder()
            val transStringBuilder = StringBuilder()
            for (i in 0 until jsonArr_0.length()) {
                val jsonArr_0_n = jsonArr_0[i] as JSONArray
                try {
                    val origin = jsonArr_0_n[1] as String
                    originStringBuilder.append(origin)
                    val trans = jsonArr_0_n[0] as String
                    transStringBuilder.append(trans)
                } catch (e: Exception) {
                }
            }
            resultText = transStringBuilder.toString()

            try {
                detectedLanguageCode = jsonArr[2] as String
            } catch (e: Exception) {
                Timber.w(e, "감지 언어 없음")
            }
        } catch (e: UnsupportedEncodingException) {
            return TranslationResponse.Error(e)
        } catch (e: IOException) {
            return TranslationResponse.Error(e)
        } catch (e: HttpException) {
            return TranslationResponse.Error(e)
        } catch (e: CancellationException) {
            // 취소는 오류가 아니다. 여기서 삼키면 핸들이 떠나 취소된 요청이
            // 실패 안내로 둔갑하고, 상위 코루틴은 취소된 줄 모른 채 계속 진행한다.
            throw e
        } catch (e: Exception) {
            return TranslationResponse.Error(e)
        }

        return TranslationResponse.Success(
            Transaction(
                targetLanguageCode = targetLanguageCode,
                sourceText = sourceText,
                translationKitType = TranslationKitType.GOOGLE,
                // 응답에 구글이 판정한 원문 언어(세 번째 항목)가 있으면 그 값, 없으면 요청한 원문 코드 그대로다("auto" 일 수 있다).
                resolvedSourceLanguageCode = detectedLanguageCode,
                resultText = resultText
            )
        )
    }

    companion object {
        const val BASE_URL = "https://translate.google.com"

        val supportedLanguageCodes = arrayOf(
            "af", // Afrikaans
            "am", // Amharic
            "ar", // Arabic
            "az", // Azerbaijani
            "be", // Belarusian
            "bg", // Bulgarian
            "bn", // Bangla
            "bs", // Bosnian
            "ca", // Catalan
            "ceb", // Cebuano
            "co", // Corsican
            "cs", // Czech
            "cy", // Welsh
            "da", // Danish
            "de", // German
            "el", // Greek
            "en", // English
            "eo", // Esperanto
            "es", // Spanish
            "et", // Estonian
            "eu", // Basque
            "fa", // Persian
            "fi", // Finnish
            "fil", // Filipino
            "fj", // Fijian
            "fr", // French
            "ga", // Irish
            "gd", // Scottish Gaelic
            "gl", // Galician
            "gu", // Gujarati
            "ha", // Hausa
            "haw", // Hawaiian
            "he", // Hebrew
            "hi", // Hindi
            "hmn", // Hmong
            "hr", // Croatian
            "ht", // Haitian Creole
            "hu", // Hungarian
            "hy", // Armenian
            "id", // Indonesian
            "ig", // Igbo
            "is", // Icelandic
            "it", // Italian
            "ja", // Japanese
            "jw", // Javanese
            "ka", // Georgian
            "kk", // Kazakh
            "km", // Khmer
            "kn", // Kannada
            "ko", // Korean
            "ku", // Kurdish
            "ky", // Kyrgyz
            "la", // Latin
            "lb", // Luxembourgish
            "lo", // Lao
            "lt", // Lithuanian
            "lv", // Latvian
            "mg", // Malagasy
            "mi", // Māori
            "mk", // Macedonian
            "ml", // Malayalam
            "mn", // Mongolian
            "mr", // Marathi
            "ms", // Malay
            "mt", // Maltese
            "my", // Burmese
            "ne", // Nepali
            "nl", // Dutch
            "no", // Norwegian
            "ny", // Nyanja
            "or", // Odia
            "pa", // Punjabi
            "pl", // Polish
            "ps", // Pashto
            "pt", // Portuguese
            "ro", // Romanian
            "ru", // Russian
            "rw", // Kinyarwanda
            "sd", // Sindhi
            "si", // Sinhala
            "sk", // Slovak
            "sl", // Slovenian
            "sm", // Samoan
            "sn", // Shona
            "so", // Somali
            "sq", // Albanian
            "sr", // Serbian
            "st", // Southern Sotho
            "su", // Sundanese
            "sv", // Swedish
            "sw", // Swahili
            "ta", // Tamil
            "te", // Telugu
            "tg", // Tajik
            "th", // Thai
            "ti", // Tigrinya
            "tk", // Turkmen
            "tl", // Tagalog
            "tr", // Turkish
            "tt", // Tatar
            "ug", // Uyghur
            "uk", // Ukrainian
            "ur", // Urdu
            "uz", // Uzbek
            "vi", // Vietnamese
            "xh", // Xhosa
            "yi", // Yiddish
            "yo", // Yoruba
            "zh-CN", // zh-CN --------------- Chinese (simplified)
            "zh-TW", // zh-TW --------------- Chinese (traditional)
            "zu", // Zulu
        )
    }
}