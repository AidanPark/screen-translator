package com.galaxy.airviewdictionary.data.local.ads

import com.google.android.gms.ads.rewarded.RewardedAd

/**
 * 로드했지만 아직 표시하지 않은 리워드 광고를 프로세스 범위에서 보관한다.
 *
 * 게이트 액티비티가 닫힐 때마다 로드해둔 광고를 버리면, 광고를 보지 않는 사용자는
 * 번역할 때마다 새 요청을 만든다(노출 0, 수익 0). 요청 대비 노출 비율이 낮으면
 * AdMob 이 무효 트래픽으로 볼 수 있다 — 실제로 한 지역에서 7일간 요청 5,851건에
 * 노출률 0.02% 가 관측됐고, 그게 전체 요청의 절반이었다.
 *
 * 표시되지 않은 리워드 광고는 유효기간 안에서 다시 보여줄 수 있으므로 여기 담아 재사용한다.
 * 이렇게 하면 요청 수가 실제 노출 수에 비례한다.
 */
object RewardedAdCache {

    /** 리워드 광고의 유효기간은 약 1시간이다. 경계에서 만료된 광고를 쓰지 않도록 여유를 둔다. */
    private const val VALID_MILLIS = 50 * 60_000L

    @Volatile
    private var ad: RewardedAd? = null

    @Volatile
    private var loadedAtMillis: Long = 0L

    /** 재사용 가능한 광고. 없거나 유효기간이 지났으면 null(만료된 것은 여기서 버린다). */
    @Synchronized
    fun get(): RewardedAd? {
        val cached = ad ?: return null
        if (System.currentTimeMillis() - loadedAtMillis >= VALID_MILLIS) {
            clearInternal()
            return null
        }
        return cached
    }

    /** 로드했지만 아직 표시하지 않은 광고를 보관한다. */
    @Synchronized
    fun put(rewardedAd: RewardedAd) {
        ad = rewardedAd
        loadedAtMillis = System.currentTimeMillis()
    }

    /** 한 번 표시한 광고는 재사용할 수 없다. 표시 시점에 비운다. */
    @Synchronized
    fun clear() {
        clearInternal()
    }

    private fun clearInternal() {
        ad = null
        loadedAtMillis = 0L
    }
}
