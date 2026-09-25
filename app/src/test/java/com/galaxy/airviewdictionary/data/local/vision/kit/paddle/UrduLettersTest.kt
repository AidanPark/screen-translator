package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import org.junit.Assert.assertEquals
import org.junit.Test

class UrduLettersTest {

    @Test
    fun arabicHehBecomesUrduLetters() {
        // کہ یہ بھی تھا — 낱말 끝·유기음 아닌 자리는 ہ, 유기음 자음 뒤에 글자가 이어지면 ھ
        assertEquals("كہ يہ بھي تھا", UrduLetters.fix("كه يه بهي تها"))
    }

    @Test
    fun textWithoutArabicHehIsUnchanged() {
        val text = "یہ ایک جملہ ہے"
        assertEquals(text, UrduLetters.fix(text))
    }

    @Test
    fun aspiratedOnlyInsideAWord() {
        // 유기음 자음 뒤라도 낱말 끝이면 ہ
        assertEquals("کہ", UrduLetters.fix("که"))
        assertEquals("کھا", UrduLetters.fix("كها".replace('ك', 'ک')))
    }
}
