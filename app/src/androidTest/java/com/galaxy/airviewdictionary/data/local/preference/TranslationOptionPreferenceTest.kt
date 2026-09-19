package com.galaxy.airviewdictionary.data.local.preference

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.remote.translation.TranslationContextMode
import com.galaxy.airviewdictionary.data.remote.translation.TranslationDomain
import com.galaxy.airviewdictionary.data.remote.translation.TranslationKitType
import com.galaxy.airviewdictionary.data.remote.translation.TranslationStrength
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 엔진별 번역 옵션이 서로 섞이지 않는지 실제 DataStore 로 검증한다.
 *
 * 설정 다이얼로그 3개가 같은 모양의 코드라 복붙 실수로 다른 엔진 키를 읽/쓰기 쉬운데,
 * 사용자가 직접 확인하기 어려운 부분이라 테스트로 고정한다.
 */
@RunWith(AndroidJUnit4::class)
class TranslationOptionPreferenceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = PreferenceRepository(context)

    private val optionKeys: List<Preferences.Key<String>> = listOf(
        PreferenceRepository.OPENAI_CONTEXT_MODE,
        PreferenceRepository.OPENAI_TRANSLATION_STRENGTH,
        PreferenceRepository.OPENAI_TRANSLATION_DOMAIN,
        PreferenceRepository.GEMINI_CONTEXT_MODE,
        PreferenceRepository.GEMINI_TRANSLATION_STRENGTH,
        PreferenceRepository.GEMINI_TRANSLATION_DOMAIN,
        PreferenceRepository.CLAUDE_CONTEXT_MODE,
        PreferenceRepository.CLAUDE_TRANSLATION_STRENGTH,
        PreferenceRepository.CLAUDE_TRANSLATION_DOMAIN,
    )

    /** 이 테스트가 기기에 남긴 값을 지워 원래(기본값) 상태로 되돌린다. */
    private fun clearOptionKeys() = runBlocking {
        context.preferenceDataStore.edit { prefs -> optionKeys.forEach { prefs.remove(it) } }
    }

    @Before
    fun setUp() {
        clearOptionKeys()
    }

    @After
    fun tearDown() {
        clearOptionKeys()
    }

    /** update() 가 비동기(IO 스코프)라 값이 반영될 때까지 기다린다. */
    private suspend fun <T> await(expected: T, read: suspend () -> T): T {
        val deadline = System.currentTimeMillis() + 5_000
        var value = read()
        while (value != expected && System.currentTimeMillis() < deadline) {
            delay(25)
            value = read()
        }
        return value
    }

    private suspend fun <T> Flow<T>.awaitValue(expected: T): T = await(expected) { first() }

    @Test
    fun 미설정이면_각_엔진이_기본값을_돌려준다() = runBlocking {
        assertEquals(TranslationContextMode.NEARBY, repository.openAiContextModeFlow.first())
        assertEquals(TranslationContextMode.NEARBY, repository.geminiContextModeFlow.first())
        assertEquals(TranslationContextMode.NEARBY, repository.claudeContextModeFlow.first())

        assertEquals(TranslationStrength.LITERAL, repository.openAiTranslationStrengthFlow.first())
        assertEquals(TranslationStrength.LITERAL, repository.geminiTranslationStrengthFlow.first())
        assertEquals(TranslationStrength.LITERAL, repository.claudeTranslationStrengthFlow.first())

        assertEquals(TranslationDomain.GENERAL, repository.openAiTranslationDomainFlow.first())
        assertEquals(TranslationDomain.GENERAL, repository.geminiTranslationDomainFlow.first())
        assertEquals(TranslationDomain.GENERAL, repository.claudeTranslationDomainFlow.first())
    }

    @Test
    fun 엔진별로_다른_값을_저장해도_서로_섞이지_않는다() = runBlocking {
        // 세 엔진에 서로 겹치지 않는 값을 넣는다.
        repository.update(PreferenceRepository.OPENAI_CONTEXT_MODE, TranslationContextMode.SCREEN.name)
        repository.update(PreferenceRepository.OPENAI_TRANSLATION_STRENGTH, TranslationStrength.FREE.name)
        repository.update(PreferenceRepository.OPENAI_TRANSLATION_DOMAIN, TranslationDomain.GAME.name)

        repository.update(PreferenceRepository.GEMINI_CONTEXT_MODE, TranslationContextMode.OFF.name)
        repository.update(PreferenceRepository.GEMINI_TRANSLATION_STRENGTH, TranslationStrength.NATURAL.name)
        repository.update(PreferenceRepository.GEMINI_TRANSLATION_DOMAIN, TranslationDomain.COMIC.name)

        repository.update(PreferenceRepository.CLAUDE_CONTEXT_MODE, TranslationContextMode.NEARBY.name)
        repository.update(PreferenceRepository.CLAUDE_TRANSLATION_STRENGTH, TranslationStrength.LITERAL.name)
        repository.update(PreferenceRepository.CLAUDE_TRANSLATION_DOMAIN, TranslationDomain.TECH.name)

        assertEquals(TranslationContextMode.SCREEN, repository.openAiContextModeFlow.awaitValue(TranslationContextMode.SCREEN))
        assertEquals(TranslationStrength.FREE, repository.openAiTranslationStrengthFlow.awaitValue(TranslationStrength.FREE))
        assertEquals(TranslationDomain.GAME, repository.openAiTranslationDomainFlow.awaitValue(TranslationDomain.GAME))

        assertEquals(TranslationContextMode.OFF, repository.geminiContextModeFlow.awaitValue(TranslationContextMode.OFF))
        assertEquals(TranslationStrength.NATURAL, repository.geminiTranslationStrengthFlow.awaitValue(TranslationStrength.NATURAL))
        assertEquals(TranslationDomain.COMIC, repository.geminiTranslationDomainFlow.awaitValue(TranslationDomain.COMIC))

        assertEquals(TranslationContextMode.NEARBY, repository.claudeContextModeFlow.awaitValue(TranslationContextMode.NEARBY))
        assertEquals(TranslationStrength.LITERAL, repository.claudeTranslationStrengthFlow.awaitValue(TranslationStrength.LITERAL))
        assertEquals(TranslationDomain.TECH, repository.claudeTranslationDomainFlow.awaitValue(TranslationDomain.TECH))
    }

    @Test
    fun contextModeFlow_는_현재_엔진의_설정을_고른다() = runBlocking {
        repository.update(PreferenceRepository.OPENAI_CONTEXT_MODE, TranslationContextMode.SCREEN.name)
        repository.update(PreferenceRepository.GEMINI_CONTEXT_MODE, TranslationContextMode.OFF.name)
        repository.update(PreferenceRepository.CLAUDE_CONTEXT_MODE, TranslationContextMode.NEARBY.name)

        assertEquals(
            TranslationContextMode.SCREEN,
            repository.contextModeFlow(TranslationKitType.OPENAI).awaitValue(TranslationContextMode.SCREEN),
        )
        assertEquals(
            TranslationContextMode.OFF,
            repository.contextModeFlow(TranslationKitType.GEMINI).awaitValue(TranslationContextMode.OFF),
        )
        assertEquals(
            TranslationContextMode.NEARBY,
            repository.contextModeFlow(TranslationKitType.CLAUDE).awaitValue(TranslationContextMode.NEARBY),
        )
    }

    @Test
    fun AI_가_아닌_엔진은_문맥을_쓰지_않는다() = runBlocking {
        // AI 엔진 쪽을 켜 두어도 Google/DeepL 은 문맥 수집 자체를 하지 않아야 한다.
        repository.update(PreferenceRepository.OPENAI_CONTEXT_MODE, TranslationContextMode.SCREEN.name)
        repository.openAiContextModeFlow.awaitValue(TranslationContextMode.SCREEN)

        assertEquals(TranslationContextMode.OFF, repository.contextModeFlow(TranslationKitType.GOOGLE).first())
        assertEquals(TranslationContextMode.OFF, repository.contextModeFlow(TranslationKitType.DEEPL).first())
    }
}
