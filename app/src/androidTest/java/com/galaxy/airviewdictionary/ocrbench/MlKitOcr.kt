package com.galaxy.airviewdictionary.ocrbench

/**
 * 덤프할 수 있는 표본 언어. 읽기는 프로덕션 길(`VisionRepository.read`)을 그대로 탄다 — 어느 인식기를 쓸지도
 * 프로덕션의 엔진 선택이 정한다.
 */
object MlKitOcr {

    /** ML Kit 인식기가 있는 문자만 의미가 있다. */
    val latin = setOf(
        "en", "de", "fr", "es", "it", "pt", "nl", "sv", "pl", "tr", "vi", "id", "cs", "ro", "da"
    )
    val nonLatin = setOf("ko", "ja", "zh", "hi")
}
