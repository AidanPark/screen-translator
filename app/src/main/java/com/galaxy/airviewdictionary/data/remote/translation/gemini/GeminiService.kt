package com.galaxy.airviewdictionary.data.remote.translation.gemini

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Google Gemini (Generative Language API). 전용 번역 엔드포인트가 없으므로
 * generateContent 에 번역 프롬프트를 보낸다. 모델은 URL 경로에, 키는 x-goog-api-key 헤더에 담는다.
 */
interface GeminiService {

    @Headers("Content-Type: application/json")
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body body: RequestBody,
    ): GeminiResponse
}

data class GeminiResponse(val candidates: List<GeminiCandidate>?)
data class GeminiCandidate(val content: GeminiContent?)
data class GeminiContent(val parts: List<GeminiPart>?)
data class GeminiPart(val text: String?)
