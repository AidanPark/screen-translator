package com.galaxy.airviewdictionary.data.local.vision

import com.galaxy.airviewdictionary.data.local.vision.kit.VisionKit
import com.galaxy.airviewdictionary.data.local.vision.model.Paragraph
import com.galaxy.airviewdictionary.data.local.vision.ocr.OcrLine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 검출만 된 화면의 문단들. 문단마다 원 줄(검출 상자)을 기억하고, 읽은 문단을 캡처 단위로 캐시한다 — 드래그로 되돌아와도
 * 다시 읽지 않는다(`.docs/vision-engine-design.md` §10). 다 읽힌 화면(ML Kit)에는 없다.
 *
 * 문단은 객체 그 자체로 가린다. `Paragraph` 는 data class 라 내용이 같으면 같다고 보기 때문이다.
 */
class UnreadParagraphs internal constructor(
    /** 이 화면을 검출한 엔진. 읽기도 이 엔진이 한다. */
    internal val kit: VisionKit,
    /** 검출 문단 → 그 문단을 이루는 원 줄(문단 안 순서). */
    private val sources: IdentityHashMap<Paragraph, List<OcrLine>>,
) {
    private val mutex = Mutex()

    /** 검출 문단 → 읽은 문단. 읽었는데 남은 단어가 없으면 null 로 넣는다. */
    private val read: MutableMap<Paragraph, Paragraph?> = Collections.synchronizedMap(IdentityHashMap())

    /** 이미 읽은 문단. 아직 안 읽었거나 읽을 것이 없었으면 null. */
    fun cached(paragraph: Paragraph): Paragraph? = read[paragraph]

    /** 이 화면의 검출 문단인데 아직 읽지 않았나. 읽는 데 시간이 걸리니 기다리는 표시를 할지 가른다. */
    fun needsReading(paragraph: Paragraph): Boolean = sources.containsKey(paragraph) && !read.containsKey(paragraph)

    /**
     * [paragraph] 를 읽은 문단을 돌려준다. 처음이면 [readLines] 로 읽고 캐시한다. 이 화면의 검출 문단이 아니면 그대로 돌려준다.
     * 한 번에 한 문단만 읽는다 — 엔진은 동시에 둘을 돌려도 빨라지지 않는다.
     */
    internal suspend fun getOrRead(paragraph: Paragraph, readLines: suspend (List<OcrLine>) -> Paragraph?): Paragraph? {
        val lines = sources[paragraph] ?: return paragraph
        return mutex.withLock {
            if (read.containsKey(paragraph)) read[paragraph]
            else readLines(lines).also { read[paragraph] = it }
        }
    }
}
