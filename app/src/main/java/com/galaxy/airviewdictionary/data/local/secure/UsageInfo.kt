package com.galaxy.airviewdictionary.data.local.secure

import android.content.Context


/**
 * 사용 통계 정보
 *
 * firstUseTime: 최초 사용 시각. 참여도 분석(번역 N회까지 걸린 시간)의 기준점.
 */
object UsageInfo {

    /**
     * 최초 사용 시각. 없으면 현재 시각으로 최초 1회 기록한다.
     * (저장 문자열 키는 기존 사용자 데이터 연속성을 위해 유지됨 — SecureStoreKey.FIRST_USE_TIME)
     */
    fun getFirstUseTime(context: Context): Long {
        val firstUseTime: SecureString = SecureStore.get(context, SecureStoreKey.FIRST_USE_TIME)
            ?: setFirstUseTime(context, System.currentTimeMillis())
        return firstUseTime.get().toLong()
    }

    private fun setFirstUseTime(context: Context, firstUseTimeMillis: Long): SecureString {
        return SecureString(firstUseTimeMillis.toString()).also {
            SecureStore.set(context, SecureStoreKey.FIRST_USE_TIME, it.get())
        }
    }

    /**
     * 최초 사용 이후 경과 시간(시). 번역 N회 도달까지 걸린 시간 분석에 사용.
     */
    fun elapsedHoursSinceFirstUse(context: Context): Int {
        val differenceInMillis = System.currentTimeMillis() - getFirstUseTime(context)
        return (differenceInMillis / (1000 * 60 * 60)).toInt()
    }

    /**
     * 최초 사용 이후 경과 시간(일).
     */
    fun elapsedDaysSinceFirstUse(context: Context): Int {
        val differenceInMillis = System.currentTimeMillis() - getFirstUseTime(context)
        return (differenceInMillis / (1000 * 60 * 60 * 24)).toInt()
    }
}
