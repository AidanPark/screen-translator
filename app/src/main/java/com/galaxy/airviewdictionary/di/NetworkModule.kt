package com.galaxy.airviewdictionary.di

import com.galaxy.airviewdictionary.data.remote.translation.goolge.GoogleWebKit
import com.galaxy.airviewdictionary.data.remote.translation.goolge.GoogleWebService
import com.galaxy.airviewdictionary.data.remote.translation.claude.ClaudeKit
import com.galaxy.airviewdictionary.data.remote.translation.claude.ClaudeService
import com.galaxy.airviewdictionary.data.remote.translation.gemini.GeminiKit
import com.galaxy.airviewdictionary.data.remote.translation.gemini.GeminiService
import com.galaxy.airviewdictionary.data.remote.translation.openai.OpenAiKit
import com.galaxy.airviewdictionary.data.remote.translation.openai.OpenAiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton


@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GoogleWebRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OpenAiRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GeminiRetrofit

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ClaudeRetrofit

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient().newBuilder()
            .addInterceptor(ResponseInterceptor())
            .readTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @GoogleWebRetrofit
    @Provides
    @Singleton
    fun provideGoogleWebRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(GoogleWebKit.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @GoogleWebRetrofit
    @Provides
    @Singleton
    fun provideGoogleWebService(@GoogleWebRetrofit retrofit: Retrofit): GoogleWebService {
        return retrofit.create(GoogleWebService::class.java)
    }

    @OpenAiRetrofit
    @Provides
    @Singleton
    fun provideOpenAiRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(OpenAiKit.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @OpenAiRetrofit
    @Provides
    @Singleton
    fun provideOpenAiService(@OpenAiRetrofit retrofit: Retrofit): OpenAiService {
        return retrofit.create(OpenAiService::class.java)
    }

    @GeminiRetrofit
    @Provides
    @Singleton
    fun provideGeminiRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(GeminiKit.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @GeminiRetrofit
    @Provides
    @Singleton
    fun provideGeminiService(@GeminiRetrofit retrofit: Retrofit): GeminiService {
        return retrofit.create(GeminiService::class.java)
    }

    @ClaudeRetrofit
    @Provides
    @Singleton
    fun provideClaudeRetrofit(okHttpClient: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(ClaudeKit.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    @ClaudeRetrofit
    @Provides
    @Singleton
    fun provideClaudeService(@ClaudeRetrofit retrofit: Retrofit): ClaudeService {
        return retrofit.create(ClaudeService::class.java)
    }
}

class ResponseInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        Timber.tag("response.code").d("response.code %s", response.code)
        // 실패 사유는 본문에 있다(모델 폐기·파라미터 거부·한도 등). 키는 헤더라 본문에 없다. 로그는 디버그 빌드만 남는다.
        if (!response.isSuccessful) {
            Timber.tag("response.code").w("%s %s -> %s", request.method, request.url.encodedPath, response.peekBody(2048).string())
        }
        return response
    }
}
