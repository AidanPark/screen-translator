package com.galaxy.airviewdictionary.ocrbench

import androidx.test.platform.app.InstrumentationRegistry
import com.galaxy.airviewdictionary.data.local.vision.VisionRepository
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 조립기가 퇴행했는지 본다. **하네스에서 단정이 있는 곳은 여기뿐이다.**
 *
 * [GeometryEvalTest] 는 평가다 — "얼마나 좋나" 를 묻고 통과/실패가 없다. 이 파일은
 * 테스트다 — 확정된 값에서의 성적을 임계값으로 박아 두고, 나중에 조립기를 건드렸을 때
 * 그 아래로 떨어지면 실패한다.
 *
 * 임계값은 에셋의 `regression.tsv` 에 있다. 개선했으면 그 파일을 올리면 되고, 코드를
 * 고칠 필요는 없다.
 */
class GeometryRegressionTest {

    private class Expectation(
        val group: String,
        val role: String,
        val minClean: Int,
        val maxDirty: Int,
        val maxOrder: Int,
    )

    private val context get() = InstrumentationRegistry.getInstrumentation().context

    private fun rows(): List<Expectation> =
        context.assets.open("regression.tsv").use { String(it.readBytes(), Charsets.UTF_8) }
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val f = line.split("\t")
                Expectation(
                    f[0], f[1], f[2].trim().toInt(), f[3].trim().toInt(), f[4].trim().toInt()
                )
            }
            .toList()

    private fun expectations() = rows().filter { it.group != NON_LTR_ORDER }

    /**
     * LTR 이 아닌 표본 전체의 순서 위반 허용치. 기본 0 이다. 새 표본이 지금 값에서 이미 위반을
     * 내면(순회 결함) 그 수를 여기 둔다 — 그러지 않으면 결함과 무관한 모든 변경이 회귀에서 막힌다.
     * 결함을 고치면 0 으로 되돌린다.
     */
    private fun nonLtrOrderAllowance() = rows().firstOrNull { it.group == NON_LTR_ORDER }?.maxOrder ?: 0

    @Test
    fun paragraphAssemblyHasNotRegressed() {
        val repository = VisionRepository()
        val shipped = EvalSetting.shipped()
        val failures = mutableListOf<String>()

        for (expected in expectations()) {
            val samples = EvalSamples.load(context, setOf(expected.group), setOf(expected.role))
            if (samples.isEmpty()) {
                failures.add("${expected.group}/${expected.role}: 표본이 없다 — 덤프를 떴는지 보라")
                continue
            }
            val total = Score()
            for (sample in samples) {
                total += EvalMetrics.score(EvalMetrics.run(repository, sample, shipped), sample)
            }
            val where = "${expected.group}/${expected.role}"
            val orderViolations = total.orderedTotal - total.ordered
            log("$where: $total (기준 온전 >= ${expected.minClean}," +
                    " 오염 <= ${expected.maxDirty}, 순서위반 <= ${expected.maxOrder})")
            if (total.clean < expected.minClean) {
                failures.add("$where 온전한 문단 ${total.clean} < ${expected.minClean}")
            }
            if (total.dirty > expected.maxDirty) {
                failures.add("$where 문단 오염 ${total.dirty} > ${expected.maxDirty}")
            }
            if (orderViolations > expected.maxOrder) {
                failures.add("$where 읽기 순서 위반 $orderViolations > ${expected.maxOrder}")
            }
        }

        assertTrue(
            "조립 품질이 기준 아래로 떨어졌다:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /**
     * 네이티브 앱 화면에서 나란히 놓인 별개 라벨을 하나로 붙이지 않는지 본다.
     * 앱 화면은 긴 문단이 드물어 묶임보다 이쪽이 중요하다.
     */
    @Test
    fun nativeLabelsAreNotMerged() {
        val repository = VisionRepository()
        val shipped = EvalSetting.shipped()
        var mergedLines = 0
        var mergedParagraphs = 0
        val samples = EvalSamples.load(context, setOf("native"))
        for (sample in samples) {
            val run = EvalMetrics.run(repository, sample, shipped)
            for (paragraph in run.paragraphs) {
                for (line in paragraph.lines) {
                    val ids = line.words
                        .mapNotNull { run.unitBlock[it]?.takeIf { id -> id >= 0 } }.toSet()
                    if (ids.size > 1) mergedLines++
                }
            }
            for (units in run.paragraphUnits) {
                val ids = units.mapNotNull { run.unitBlock[it]?.takeIf { id -> id >= 0 } }.toSet()
                if (ids.size > 1) mergedParagraphs++
            }
        }
        log("native ${samples.size}면: 섞인 Line $mergedLines, 섞인 문단 $mergedParagraphs")
        // 2026-09-24 실측(7면 — 개인 정보가 찍힌 두 표본을 뺀 뒤): 섞인 Line 1, 섞인 문단 9.
        assertTrue("섞인 Line 이 $mergedLines 개로 늘었다(기준 3)", mergedLines <= 3)
        assertTrue("섞인 문단이 $mergedParagraphs 개로 늘었다(기준 12)", mergedParagraphs <= 12)
    }

    /**
     * RTL·세로쓰기 순회는 위반이 하나도 없어야 한다.
     *
     * 올바른 읽기 순서는 기하가 정하므로(가로쓰기는 위에서 아래, 세로쓰기는 오른쪽에서
     * 왼쪽, RTL 줄은 오른쪽 단어부터) 위반은 임계값을 둘 성질이 아니라 버그다. 집합 소속만
     * 재는 지표로는 이것을 통과해도 뒤집혀 있을 수 있어, 순서를 따로 단정한다.
     *
     * 지금 RTL 표본은 검출기 줄뿐이라 **문단 안 줄 순서만** 검사된다. 줄 안 단어 순서는
     * RTL 단어 단위 표본이 생겨야 검사된다(PP-OCRv5 의 CTC 단어를 정답과 같은 캡처로
     * 뜨면 된다).
     */
    @Test
    fun rightToLeftAndVerticalTraversalKeepsReadingOrder() {
        val repository = VisionRepository()
        val shipped = EvalSetting.shipped()
        val samples = EvalSamples.load(context).filter {
            it.direction != com.galaxy.airviewdictionary.data.local.vision.WritingDirection.LTR
        }
        if (samples.isEmpty()) return // 아직 표본이 없다 — 세로쓰기·RTL 표본을 넣으면 켜진다
        val total = Score()
        for (sample in samples) {
            total += EvalMetrics.score(EvalMetrics.run(repository, sample, shipped), sample)
        }
        val violations = total.orderedTotal - total.ordered
        val allowance = nonLtrOrderAllowance()
        log("LTR 이 아닌 표본 ${samples.size}면: 순서 검사 ${total.orderedTotal}건, 위반 $violations (허용 $allowance)")
        assertTrue("읽기 순서 위반 $violations 건(허용 $allowance) — 순회 분기를 보라", violations <= allowance)
    }

    private val report: File by lazy {
        val dir = File(
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: InstrumentationRegistry.getInstrumentation().targetContext.externalMediaDirs.first().path
        ).apply { mkdirs() }
        File(dir, "regression.txt").apply { if (opened.compareAndSet(false, true)) writeText("") }
    }

    /** logcat 은 앞줄이 사라지므로 파일로도 낸다(run_eval.py 가 원자료로 가져간다). */
    private fun log(message: String) {
        android.util.Log.i("GeometryRegression", message)
        report.appendText(message + "\n")
    }

    private companion object {
        const val NON_LTR_ORDER = "non-ltr-order"
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
