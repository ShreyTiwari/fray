package org.pastalab.fray.core.feedback.sources

import org.pastalab.fray.core.feedback.HitBucket
import org.pastalab.fray.core.feedback.IterationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `observe(Long)` needs none of the RunContext/ThreadContext machinery, so it exercises the same
 * count -> [HitBucket].encode -> collect path that the location and handoff sources share.
 */
class StateFeedbackSourceTest {

  private fun result() = IterationResult(0, bugFound = null, reportFolder = null)

  @Test
  fun hitsOffIsBinaryMembership_unchangedFromPreC3() {
    val src = StateFeedbackSource(countHits = false)
    src.beforeIteration(0)
    repeat(100) { src.observe(42L) }
    src.observe(7L)
    val features = src.collect(result()).features
    assertEquals(2, features.size, "hits=off: count is ignored, only presence matters")
  }

  @Test
  fun hitCountModeIsOnByDefault() {
    // No-arg constructor must bucket: same value at two counts must yield two features.
    val a = StateFeedbackSource().apply { beforeIteration(0); observe(9L) }
    val b = StateFeedbackSource().apply { beforeIteration(0); repeat(50) { observe(9L) } }
    assertTrue(
        a.collect(result()).features != b.collect(result()).features,
        "default (no countHits arg) must be hit-count mode")
  }

  @Test
  fun hitsOnDistinguishesFrequencyOfTheSameValue() {
    // Same value at different counts across iterations must emit different features.
    val src = StateFeedbackSource(countHits = true)

    src.beforeIteration(0)
    src.observe(42L)
    val once = src.collect(result()).features

    src.beforeIteration(1)
    repeat(100) { src.observe(42L) }
    val many = src.collect(result()).features

    assertEquals(1, once.size)
    assertEquals(1, many.size)
    assertTrue(once != many, "same value at different hit counts must yield different features")
  }

  @Test
  fun hitsOnSameBucketCollapses() {
    // Counts landing in the same bucket must produce identical features -- no spurious novelty.
    val a = StateFeedbackSource(countHits = true).apply { beforeIteration(0); observe(5L); observe(5L) }
    val b = StateFeedbackSource(countHits = true).apply { beforeIteration(0); observe(5L); observe(5L); observe(5L) }
    assertEquals(a.collect(result()).features, b.collect(result()).features)
  }

  @Test
  fun hitsOnFeaturesStayWithin56BitPayload() {
    val payloadMask = 0x00FF_FFFF_FFFF_FFFFL
    val src = StateFeedbackSource(countHits = true)
    src.beforeIteration(0)
    repeat(5000) { src.observe(it.toLong()) }
    repeat(5000) { src.observe(1L) }
    src.collect(result()).features.forEach {
      assertEquals(it, it and payloadMask, "feature must stay within the 56-bit payload")
    }
  }

  @Test
  fun targetScoreStillEmittedInHitsMode() {
    // hits mode must not disturb the proximity score path.
    val src = StateFeedbackSource(target = 100L, countHits = true)
    src.beforeIteration(0)
    src.observe(98L)
    val signal = src.collect(result())
    assertEquals(1.0 / 3.0, signal.score, 1e-9)
  }
}
