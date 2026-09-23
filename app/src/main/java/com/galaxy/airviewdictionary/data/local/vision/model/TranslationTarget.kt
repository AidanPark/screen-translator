package com.galaxy.airviewdictionary.data.local.vision.model

import android.graphics.Point

/**
 * 한 번의 번역 시도가 가리키는 대상.
 *
 * OCR 이 준 기하([visionText])는 두 경로 모두에서 쓴다 — 말풍선을 어디에 띄울지,
 * 이미지 경로라면 화면의 어디를 잘라 보낼지.
 *
 * 반면 "무엇을 번역할 텍스트인가"와 "어느 언어인가"는 경로마다 정하는 주체가 다르다.
 * 텍스트 경로는 OCR 이, 이미지 경로는 모델이 정한다. 그래서 그 둘은 여기 담지 않는다 —
 * 확정된 값은 [com.galaxy.airviewdictionary.data.remote.translation.Transaction] 이 들고 있다.
 *
 * [id] 는 이 시도의 신원이다. 번역 결과가 돌아왔을 때 "지금 화면의 대상에 대한 것인가"를
 * 이 값으로 판정한다. 예전에는 OCR 텍스트와 번역 원문을 문자열 비교해서 판정했는데,
 * 이미지 경로에서는 둘이 구조적으로 같을 수 없어(같지 않게 만드는 것이 그 경로의 목적이다)
 * 번역창이 뜬 지 100ms 만에 닫혔다.
 */
data class TranslationTarget(
    val id: Long,
    val visionText: VisionText,
    val pointerPosition: Point,
)
