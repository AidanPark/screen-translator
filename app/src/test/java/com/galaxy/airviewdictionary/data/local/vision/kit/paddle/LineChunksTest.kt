package com.galaxy.airviewdictionary.data.local.vision.kit.paddle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LineChunks] — 넓은 줄을 인식기에 나눠 넣을 자리(`.docs/vision-engine-design.md` §22). */
class LineChunksTest {

    /** 글자(진하기 200)로 찬 줄에 [gaps] 의 (자리, 폭) 빈 틈(진하기 0)을 낸 열 진하기. */
    private fun line(width: Int, gaps: List<Pair<Int, Int>>): IntArray {
        val ink = IntArray(width) { 200 }
        for ((at, w) in gaps) for (x in at until at + w) ink[x] = 0
        return ink
    }

    @Test
    fun normalLineIsOneChunk() {
        assertEquals(listOf(LineChunks.Chunk(0, 1300, false)), LineChunks.plan(line(1300, emptyList())))
        assertEquals(listOf(LineChunks.Chunk(0, 3200, false)), LineChunks.plan(line(3200, emptyList())))
    }

    @Test
    fun wideLineIsCutAtTheWidestGapNearTheSplitPoint() {
        // 폭 8000 → 조각 3(등분점 2667·5333, 창 ±160). 창 안에 좁은 틈(글자 안)과 넓은 틈(낱말 사이)이 있으면 넓은 틈의 가운데를 자른다
        val ink = line(8000, listOf(2600 to 14, 2700 to 6, 5400 to 12))
        val chunks = LineChunks.plan(ink)
        assertEquals(listOf(LineChunks.Chunk(0, 2607, false), LineChunks.Chunk(2607, 5406, true), LineChunks.Chunk(5406, 8000, true)), chunks)
        assertTrue(chunks.all { it.end - it.start <= LineChunks.MAX_WIDTH })
    }

    @Test
    fun narrowGapIsNotASpace() {
        // 가장 넓은 틈이 SPACE_GAP 보다 좁으면(글자 사이) 공백을 넣지 않는다
        val chunks = LineChunks.plan(line(5000, listOf(2480 to 6)))
        assertEquals(2, chunks.size)
        assertEquals(2483, chunks[1].start)
        assertFalse(chunks[1].spaceBefore)
    }

    @Test
    fun withoutGapsTheLightestColumnNearestTheSplitPointIsCut() {
        // 줄에서 가장 옅은 열(100)은 창(2340..2660) 밖이다 — 창 안에는 빈 틈으로 칠 만큼 옅은 열이 없다
        val ink = IntArray(5000) { 200 }.also { it[100] = 0; it[2460] = 150; it[2540] = 150; it[2505] = 180 }
        val chunks = LineChunks.plan(ink)
        // 두 열이 똑같이 옅고 등분점(2500)과의 거리도 같으면 앞의 것
        assertEquals(listOf(LineChunks.Chunk(0, 2460, false), LineChunks.Chunk(2460, 5000, false)), chunks)
    }

    @Test
    fun chunksCoverTheLineWithinTheCap() {
        for (w in listOf(3201, 5759, 5760, 5761, 16000, 40000)) {
            val chunks = LineChunks.plan(line(w, emptyList()))
            assertEquals(0, chunks.first().start)
            assertEquals(w, chunks.last().end)
            chunks.zipWithNext { a, b -> assertEquals(a.end, b.start) }
            assertTrue("$w: ${chunks.map { it.end - it.start }}", chunks.all { it.end - it.start in 1..LineChunks.MAX_WIDTH + 1 })
        }
    }
}
