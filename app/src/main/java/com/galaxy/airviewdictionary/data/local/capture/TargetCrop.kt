package com.galaxy.airviewdictionary.data.local.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect

/**
 * 번역 대상 주변을 잘라내고 표적 위치에 마커를 그린 이미지를 만든다.
 *
 * 왜 필요한가: OCR(ML Kit)은 아랍어·페르시아어처럼 글자가 이어지는 문자나
 * 태국어·일본어처럼 띄어쓰기가 없는 문자에서 텍스트 경계를 자주 잘못 긋는다.
 * 잘못 잘린 텍스트를 번역 엔진에 넘기면 어떤 모델을 쓰든 품질에 상한이 생긴다.
 * 이미지를 받는 AI 엔진에는 자른 화면을 그대로 주고 대상 위치만 알려주면,
 * 모델이 이미지에서 직접 경계를 판단할 수 있다.
 *
 * 좌표는 텍스트로 설명하는 것보다 이미지에 직접 그리는 편이 비전 모델에 잘 전달된다.
 */
object TargetCrop {

    /**
     * 감지 영역 대비 확장 비율(각 변 기준). 단락처럼 큰 영역에서 주로 작동한다.
     */
    private const val EXPAND_RATIO = 0.2f

    /**
     * 비율만 쓰면 단어처럼 작은 영역에 의미 있는 여유가 생기지 않는다.
     * (100×40 박스의 20%는 좌우 10px뿐이라, 반으로 잘린 아랍어 단어의 나머지를 못 담는다)
     * 그래서 비율과 이 최소값 중 큰 쪽을 쓴다.
     */
    private const val MIN_MARGIN_PX = 48

    /** 마커 색. 화면 내용과 헷갈리지 않도록 잘 쓰이지 않는 색을 쓴다. */
    private const val MARKER_COLOR = 0xFFFF00FF.toInt()

    /**
     * 전송 전 긴 변을 이 크기로 줄인다.
     * 글자를 읽는 데 원본 해상도는 필요 없고, 이미지 토큰은 넓이에 비례한다.
     * (1440x668 원본을 그대로 보냈을 때 입력 1,634 토큰 · 왕복 5초가 나왔다 — 2026-09-21 실측)
     */
    private const val MAX_DIMENSION_PX = 768

    private const val MARKER_RADIUS_PX = 18f
    private const val MARKER_STROKE_PX = 4f

    /** 프롬프트에서 마커를 지칭할 때 쓰는 이름. 색과 함께 바뀌면 안 된다. */
    const val MARKER_DESCRIPTION = "magenta circle"

    /**
     * [source] 에서 [area] 만 잘라낸 비트맵을 돌려준다. 마커는 그리지 않는다.
     * 잘라낼 영역이 유효하지 않으면 null.
     *
     * 사용자가 직접 영역을 그리는 SELECT 모드용이다. 표적이 한 점이 아니라 영역 자체이므로
     * 가리킬 마커가 필요 없고, 여유 마진도 두지 않는다 — 사용자가 고른 경계가 곧 의도다.
     */
    fun buildArea(source: Bitmap, area: Rect): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null

        val crop = Rect(area).apply { intersect(0, 0, source.width, source.height) }
        if (crop.width() <= 0 || crop.height() <= 0) return null

        val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())
        val scale = MAX_DIMENSION_PX.toFloat() / maxOf(crop.width(), crop.height())
        if (scale >= 1f) {
            // createBitmap 은 잘라낼 영역이 원본 전체와 같으면 원본을 그대로 돌려준다.
            // 호출부가 비트맵을 따로 다루므로 원본과 같은 객체를 넘기지 않는다.
            return if (cropped === source) cropped.copy(Bitmap.Config.ARGB_8888, false) else cropped
        }
        val scaled = Bitmap.createScaledBitmap(
            cropped,
            (crop.width() * scale).toInt().coerceAtLeast(1),
            (crop.height() * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (cropped !== source && cropped !== scaled) cropped.recycle()
        return scaled
    }

    /**
     * [source] 에서 [targetBox] 주변을 잘라내고 [pointer] 위치에 마커를 그린 비트맵을 돌려준다.
     * 잘라낼 영역이 유효하지 않으면 null.
     *
     * @param source 전체 화면 캡처
     * @param targetBox OCR 이 잡은 대상 영역(틀릴 수 있다 — 그래서 여유를 둔다)
     * @param pointer 사용자가 가리킨 지점(전체 화면 좌표)
     */
    fun build(source: Bitmap, targetBox: Rect, pointer: Point): Bitmap? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null

        val marginX = maxOf((targetBox.width() * EXPAND_RATIO).toInt(), MIN_MARGIN_PX)
        val marginY = maxOf((targetBox.height() * EXPAND_RATIO).toInt(), MIN_MARGIN_PX)

        // 표적이 박스 밖에 있을 수도 있으므로(OCR 이 빗나간 경우) 둘을 모두 포함시킨다.
        val crop = Rect(targetBox).apply {
            union(pointer.x, pointer.y)
            inset(-marginX, -marginY)
            intersect(0, 0, source.width, source.height)
        }
        if (crop.width() <= 0 || crop.height() <= 0) return null

        val cropped = Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height())

        // 줄이고 나서 마커를 그린다. 먼저 그리면 마커도 함께 축소돼 흐려진다.
        val scale = MAX_DIMENSION_PX.toFloat() / maxOf(crop.width(), crop.height())
        val canvasBitmap = if (scale < 1f) {
            val scaled = Bitmap.createScaledBitmap(
                cropped,
                (crop.width() * scale).toInt().coerceAtLeast(1),
                (crop.height() * scale).toInt().coerceAtLeast(1),
                true,
            )
            if (cropped !== source && cropped !== scaled) cropped.recycle()
            // createScaledBitmap 도 크기가 같으면 원본을 그대로 돌려줄 수 있다.
            if (scaled === source) scaled.copy(Bitmap.Config.ARGB_8888, true) ?: return null else scaled
        } else {
            // createBitmap 은 잘라낼 영역이 원본 전체와 같으면 원본을 그대로 돌려준다.
            // 그 위에 마커를 그리면 캡처 원본이 오염되므로 복사본에 그린다.
            val copy = cropped.copy(Bitmap.Config.ARGB_8888, true) ?: return null
            if (cropped !== source) cropped.recycle()
            copy
        }

        val drawScale = if (scale < 1f) scale else 1f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MARKER_COLOR
            style = Paint.Style.STROKE
            strokeWidth = MARKER_STROKE_PX
        }
        // 속이 빈 원으로 그린다. 채우면 표적 바로 아래 글자를 가려버린다.
        Canvas(canvasBitmap).drawCircle(
            (pointer.x - crop.left) * drawScale,
            (pointer.y - crop.top) * drawScale,
            MARKER_RADIUS_PX,
            paint,
        )
        return canvasBitmap
    }
}
