package com.galaxy.airviewdictionary.data.local.vision.model

/**
 * 한 번의 번역 시도가 가리키는 대상. OCR 이 준 기하([visionText])로 말풍선을 어디에 띄울지 정한다.
 *
 * "무엇을 번역할 텍스트인가"와 "어느 언어인가"의 확정값은 여기 담지 않는다 —
 * [com.galaxy.airviewdictionary.data.remote.translation.Transaction] 이 들고 있다.
 *
 * [id] 는 이 시도의 신원이다. 번역 결과가 돌아왔을 때 "지금 화면의 대상에 대한 것인가"를
 * 이 값으로 판정한다. 예전에는 OCR 텍스트와 번역 원문을 문자열 비교했는데, 원문이 OCR 텍스트와
 * 다른 경로(AI 이미지 경로, .docs/vision-engine-design.md §21 에서 걷어냄)에서 번역창이 뜬 지 100ms 만에 닫혔다.
 *
 * 포인터 위치는 담지 않는다 — 이미지 경로가 크롭 중심으로 쓰던 값이라 그 경로와 함께 걷어냈다(§23).
 */
data class TranslationTarget(
    val id: Long,
    val visionText: VisionText,
)
