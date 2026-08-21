package org.pastalab.fray.core.feedback

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [FeedbackAggregator] tests. Back-compat anchor: with no valuations, objectives equal the old
 * `score + newForSource`. Also covers per-source decay, the count-before-fold ordering invariant,
 * and one objective per source in registration order.
 */
class FeedbackAggregatorTest {

  private val eps = 1e-9

  private class StubSource(
      override val name: String,
      private val perIteration: List<CoverageSignal>,
  ) : FeedbackSource {
    private var i = 0

    override fun collect(result: IterationResult): CoverageSignal {
      val signal = perIteration[i.coerceAtMost(perIteration.size - 1)]
      i++
      return signal
    }
  }

  private fun result(iteration: Int) = IterationResult(iteration, bugFound = null, reportFolder = null)

  @Test
  fun noValuationsReproducesPreC5Objective() {
    val src =
        StubSource(
            "s",
            listOf(
                CoverageSignal("s", setOf(1L, 2L, 3L)),
                CoverageSignal("s", setOf(3L, 4L)),
            ),
        )
    val agg = FeedbackAggregator(listOf(src))

    val f1 = agg.aggregate(result(0))
    assertEquals(3.0, f1.objectives["s"]!!, eps, "iter1: 3 new features => objective 3")
    assertEquals(3.0, f1.fitness, eps)

    val f2 = agg.aggregate(result(1))
    assertEquals(1.0, f2.objectives["s"]!!, eps, "iter2: only feature 4 is new => objective 1")
  }

  @Test
  fun scoreIsAddedUnscaledUnderIdentity() {
    val src = StubSource("s", listOf(CoverageSignal("s", setOf(10L, 11L), score = 0.5)))
    val agg = FeedbackAggregator(listOf(src))
    val f = agg.aggregate(result(0))
    assertEquals(0.5 + 2.0, f.objectives["s"]!!, eps, "objective = score + newForSource")
  }

  @Test
  fun logDecayShrinksObjectiveAsCoverageGrows() {
    val src =
        StubSource(
            "s",
            listOf(
                CoverageSignal("s", setOf(1L, 2L)),
                CoverageSignal("s", setOf(3L, 4L)),
                CoverageSignal("s", setOf(5L, 6L)),
            ),
        )
    val valuation = NoveltyValuation(decay = NoveltyValuation.Decay.LOG)
    val agg = FeedbackAggregator(listOf(src), mapOf("s" to valuation))

    val o1 = agg.aggregate(result(0)).objectives["s"]!!
    val o2 = agg.aggregate(result(1)).objectives["s"]!!
    val o3 = agg.aggregate(result(2)).objectives["s"]!!

    assertEquals(ln(3.0) - ln(1.0), o1, eps)
    assertEquals(ln(5.0) - ln(3.0), o2, eps)
    assertEquals(ln(7.0) - ln(5.0), o3, eps)
    assertTrue(o1 > o2 && o2 > o3, "log decay should shrink equal batches: $o1 > $o2 > $o3")
  }

  @Test
  fun perSourceNoveltyCountedBeforeFoldOnFirstIteration() {
    // priorSize 0, so newForSource must equal the full feature-set size (count-before-fold).
    val src = StubSource("s", listOf(CoverageSignal("s", setOf(7L, 8L, 9L, 10L))))
    val agg = FeedbackAggregator(listOf(src))
    assertEquals(4.0, agg.aggregate(result(0)).objectives["s"]!!, eps)
  }

  @Test
  fun multiSourceProducesPerSourceObjectivesInOrder() {
    val a = StubSource("a", listOf(CoverageSignal("a", setOf(1L, 2L, 3L))))
    val b = StubSource("b", listOf(CoverageSignal("b", setOf(100L))))
    val agg = FeedbackAggregator(listOf(a, b))
    val f = agg.aggregate(result(0))

    assertEquals(listOf("a", "b"), f.objectives.keys.toList(), "objectives keyed in registration order")
    assertEquals(3.0, f.objectives["a"]!!, eps)
    assertEquals(1.0, f.objectives["b"]!!, eps)
    assertEquals(2, f.objectiveVector().size, "dense vector has one dim per source")
    // Disjoint sources are namespaced => 4 distinct features overall.
    assertEquals(4, f.newFeatures.size)
  }

  @Test
  fun perSourceDecayIsIndependent() {
    val a =
        StubSource(
            "a",
            listOf(CoverageSignal("a", setOf(1L, 2L)), CoverageSignal("a", setOf(3L, 4L))),
        )
    val b =
        StubSource(
            "b",
            listOf(CoverageSignal("b", setOf(10L, 20L)), CoverageSignal("b", setOf(30L, 40L))),
        )
    val agg =
        FeedbackAggregator(listOf(a, b), mapOf("a" to NoveltyValuation(decay = NoveltyValuation.Decay.LOG)))

    agg.aggregate(result(0))
    val f2 = agg.aggregate(result(1))
    assertEquals(2.0, f2.objectives["b"]!!, eps, "b stays linear")
    assertEquals(ln(5.0) - ln(3.0), f2.objectives["a"]!!, eps, "a decays under log")
    assertTrue(f2.objectives["a"]!! < f2.objectives["b"]!!, "decayed a should fall below linear b")
  }

  @Test
  fun measurementOnlySourceIsReportedButNotOptimized() {
    // A measurement-only source must be collected (visible per-source) yet contribute nothing to
    // search: no objective, no vector dimension, no growth of the global novelty union.
    val a = StubSource("a", listOf(CoverageSignal("a", setOf(1L, 2L, 3L))))
    val b = StubSource("b", listOf(CoverageSignal("b", setOf(100L, 200L))))
    val agg = FeedbackAggregator(listOf(a, b), emptyMap(), measurementOnly = setOf("b"))

    val f = agg.aggregate(result(0))

    assertEquals(listOf("a"), f.objectives.keys.toList(), "measurement-only source has no objective")
    assertEquals(1, f.objectiveVector().size, "objective vector dimension excludes measured-only")
    assertEquals(3.0, f.objectives["a"]!!, eps)
    assertEquals(3.0, f.fitness, eps, "fitness sums objectives => excludes measured-only")

    assertEquals(3, f.newFeatures.size, "measured-only features stay out of the novelty union")
    assertEquals(3, agg.totalFeaturesSeen(), "measured-only features stay out of globalCoverage")

    assertEquals(2, agg.featuresSeenBySource()["b"], "measured-only source still reported per-source")
    assertEquals(3, agg.featuresSeenBySource()["a"])
  }

  @Test
  fun measurementOnlySourceCannotMakeIterationNovel() {
    // A measured-only source finding new features must never flip isNovel: that would reset the
    // genetic scheduler's stagnation/restart counter and perturb a search it should be invisible to.
    val a =
        StubSource(
            "a",
            listOf(CoverageSignal("a", setOf(1L)), CoverageSignal("a", setOf(1L))), // novel, then not
        )
    val m =
        StubSource(
            "m",
            listOf(CoverageSignal("m", setOf(10L)), CoverageSignal("m", setOf(20L))), // always new
        )
    val agg = FeedbackAggregator(listOf(a, m), emptyMap(), measurementOnly = setOf("m"))

    assertTrue(agg.aggregate(result(0)).isNovel, "iter1 novel via the feedback source")
    assertFalse(
        agg.aggregate(result(1)).isNovel,
        "iter2 must be non-novel: the measured-only source's new feature must not count")
  }

  @Test
  fun hitCountBucketsCountAsNoveltyAcrossIterations() {
    // AFL-style "same edge, higher hit count = new coverage": HitBucket re-encoding the same raw
    // feature at a new bucket emits a distinct feature, growing the union with no aggregator change.
    val raw = 0xABCDEFL
    val src =
        StubSource(
            "loc",
            listOf(
                CoverageSignal("loc", setOf(HitBucket.encode(raw, 1))),
                CoverageSignal("loc", setOf(HitBucket.encode(raw, 1))), // same bucket -> not novel
                CoverageSignal("loc", setOf(HitBucket.encode(raw, 64))), // higher bucket -> novel
            ),
        )
    val agg = FeedbackAggregator(listOf(src))

    assertTrue(agg.aggregate(result(0)).isNovel, "first sighting of the site is novel")
    assertFalse(agg.aggregate(result(1)).isNovel, "same site, same bucket: not novel")
    assertTrue(
        agg.aggregate(result(2)).isNovel,
        "same site at a much higher hit count (new bucket) is novel again -- the C3 payoff")
  }
}
