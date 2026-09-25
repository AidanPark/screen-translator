package com.galaxy.airviewdictionary.data.local.preference

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "사용법 안내" 의 오버레이 안내 초기화 범위 검증.
 *
 * 고정해 두는 것:
 *  - 안내 "보여줌" 플래그(말풍선 좌/우, 설정 재진입 코치마크)는 모두 지워진다.
 *  - 온보딩 완료·리뷰 요청·광고 게이트·사용자 설정은 그대로 남는다 — 지우면 온보딩이 다시 뜨거나
 *    리뷰 요청·광고 억제 상태가 풀린다.
 */
class OverlayGuideResetTest {

    @Test
    fun `안내 플래그만 지우고 나머지 값은 남긴다`() {
        val preferences = mutablePreferencesOf(
            PreferenceRepository.IS_SAY_HERE_L_SHOWN to true,
            PreferenceRepository.IS_SAY_HERE_R_SHOWN to true,
            PreferenceRepository.IS_SETTINGS_REOPEN_HINT_SHOWN to true,
            PreferenceRepository.WAS_TRAILER_SHOWN to true,
            PreferenceRepository.IS_REVIEW_DONE to true,
            PreferenceRepository.AD_GATE_SUPPRESSED_UNTIL to 1234L,
            PreferenceRepository.DRAG_HANDLE_DOCKING to true,
        )

        preferences.clearOverlayGuideShownFlags()

        assertNull(preferences[PreferenceRepository.IS_SAY_HERE_L_SHOWN])
        assertNull(preferences[PreferenceRepository.IS_SAY_HERE_R_SHOWN])
        assertNull(preferences[PreferenceRepository.IS_SETTINGS_REOPEN_HINT_SHOWN])

        assertEquals(true, preferences[PreferenceRepository.WAS_TRAILER_SHOWN])
        assertEquals(true, preferences[PreferenceRepository.IS_REVIEW_DONE])
        assertEquals(1234L, preferences[PreferenceRepository.AD_GATE_SUPPRESSED_UNTIL])
        assertEquals(true, preferences[PreferenceRepository.DRAG_HANDLE_DOCKING])
    }

    @Test
    fun `이미 첫 실행 상태여도 문제없이 지나간다`() {
        val preferences = mutablePreferencesOf()

        preferences.clearOverlayGuideShownFlags()

        assertEquals(0, preferences.asMap().size)
    }
}
