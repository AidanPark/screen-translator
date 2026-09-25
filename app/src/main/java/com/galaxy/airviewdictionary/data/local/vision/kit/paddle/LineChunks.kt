package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import kotlin.math.abs

/**
 * 아주 넓은 줄을 인식기에 나눠 넣을 자리(`.docs/vision-engine-design.md` §22).
 *
 * 인식기 목(neck)의 전역 어텐션 때문에 한 번의 순전파가 쓰는 메모리는 입력 폭의 제곱에 가깝게 는다(맥 실측: 폭 3200 +53MB, 8000 +176MB,
 * 16000 +423MB). 가로 화면·태블릿의 작은 글씨는 높이 48 로 맞추면 폭이 수천을 넘는다. 폭이 [MAX_WIDTH] 를 넘는 줄은 조각으로 나눠 따로 읽는다 —
 * 조각 수는 폭을 상한의 90% 로 나눈 올림, 자를 자리는 등분점 ± 상한의 5% 안에서 가장 넓은 빈 틈(열의 가장 진한 픽셀이 줄 안 진하기 범위의
 * 아래 1/4 이하)의 가운데다. 빈 틈이 없으면 가장 옅은 열. 자른 틈이 [SPACE_GAP] 이상이면 낱말 사이로 보고 조각 사이에 공백을 넣는다 — 틈을 가운데서
 * 자르면 양쪽 조각 모두 가장자리에 공백을 내지 않기 때문이다.
 *
 * 파이썬 포트(`tools/paddle/pipeline.py` 의 `plan_chunks`)와 같은 알고리즘이다. 안드로이드 타입을 쓰지 않는다.
 */
internal object LineChunks {

    /**
     * 인식기 입력 폭(높이 48)의 상한. 폰 세로 화면의 줄은 1300 안팎이고, 훑기 캡처(1440 폭, 238면) 7,392줄 중 가장 넓은 줄이 2,744 다 —
     * 모두 한 번에 읽는다.
     */
    const val MAX_WIDTH = 3200

    /**
     * 조각 사이에 공백을 넣는 빈 틈 폭(높이 48 기준, 줄 높이의 0.2). 실측(7개 언어 줄 표본): 인식기가 공백을 낸 틈의 95% 가 10 이상이고, 공백이
     * 아닌 틈은 3% 만 10 이상이다.
     */
    const val SPACE_GAP = 10

    /** 입력 열 [start, end) 한 조각. [spaceBefore] 면 앞 조각과의 사이에 공백을 넣는다. */
    data class Chunk(val start: Int, val end: Int, val spaceBefore: Boolean)

    /** [ink] 는 입력 열마다 가장 진한 픽셀의 진하기(바탕 0 ~ 글자 255). 폭이 [maxWidth] 이하면 한 조각. */
    fun plan(ink: IntArray, maxWidth: Int = MAX_WIDTH, spaceGap: Int = SPACE_GAP): List<Chunk> {
        val w = ink.size
        if (w <= maxWidth) return listOf(Chunk(0, w, false))
        val slack = maxWidth / 20
        val step = maxWidth - 2 * slack
        val n = (w + step - 1) / step
        val lo = ink.min()
        val hi = ink.max()
        val threshold = lo + (hi - lo) / 4
        val blank = BooleanArray(w) { ink[it] <= threshold }

        val chunks = mutableListOf<Chunk>()
        var previous = 0
        var spaceBefore = false
        for (k in 1 until n) {
            val ideal = (k.toLong() * w + n / 2).div(n).toInt()
            val a = maxOf(ideal - slack, previous + 1)
            val b = minOf(ideal + slack, w - 1)
            var cut = -1
            var cutWidth = -1
            var x = a
            while (x <= b) {
                if (!blank[x]) { x++; continue }
                var s = x
                while (s > 0 && blank[s - 1]) s--
                var e = x
                while (e < w && blank[e]) e++
                val c = ((s + e) / 2).coerceIn(a, b)
                val width = e - s
                if (width > cutWidth || (width == cutWidth && abs(c - ideal) < abs(cut - ideal))) { cut = c; cutWidth = width }
                x = e
            }
            val space: Boolean
            if (cut >= 0) {
                space = cutWidth >= spaceGap
            } else {
                // 빈 틈이 없다 — 가장 옅은 열, 같으면 등분점에 가까운 열
                cut = a
                for (i in a..b) if (ink[i] < ink[cut] || (ink[i] == ink[cut] && abs(i - ideal) < abs(cut - ideal))) cut = i
                space = false
            }
            chunks.add(Chunk(previous, cut, spaceBefore))
            previous = cut
            spaceBefore = space
        }
        chunks.add(Chunk(previous, w, spaceBefore))
        return chunks
    }
}
