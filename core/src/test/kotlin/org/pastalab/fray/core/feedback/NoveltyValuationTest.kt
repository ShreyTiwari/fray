package org.pastalab.fray.core.feedback

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [NoveltyValuation.IDENTITY] must reproduce the legacy objective `score + newForSource` exactly
 * for every input (back-compat anchor); the rest pin each decay curve's shape.
 */
class NoveltyValuationTest {

  private val eps = 1e-9

  @Test
  fun identityReproducesPreC5Objective() {
    // Under LINEAR the U-difference collapses to newForSource, so value is priorSize-independent.
    val v = NoveltyValuation.IDENTITY
    val scores = listOf(0.0, 0.5, 0.9, 1.0, 3.25)
    val news = listOf(0, 1, 2, 7, 50)
    val priors = listOf(0, 1, 5, 50, 9999)
    for (s in scores) for (n in news) for (p in priors) {
      assertEquals(s + n, v.value(s, n, p), eps, "IDENTITY value mismatch for score=$s new=$n prior=$p")
    }
  }

  @Test
  fun explicitLinearWeightOneEqualsIdentity() {
    val linear = NoveltyValuation(weight = 1.0, decay = NoveltyValuation.Decay.LINEAR)
    assertEquals(NoveltyValuation.IDENTITY, linear, "linear+weight1 should equal IDENTITY by value")
    assertEquals(0.9 + 2.0, linear.value(0.9, 2, 50), eps)
  }

  @Test
  fun zeroNoveltyContributesOnlyScore() {
    // newForSource = 0 => ΔU = 0 for every curve, so the objective is just `score`.
    for (decay in NoveltyValuation.Decay.entries) {
      val v = NoveltyValuation(weight = 3.0, decay = decay)
      assertEquals(0.7, v.value(0.7, 0, 123), eps, "decay=$decay should add nothing when new=0")
      assertEquals(0.0, v.value(0.0, 0, 0), eps, "decay=$decay U(0)=0")
    }
  }

  @Test
  fun logValueMatchesClosedForm() {
    val v = NoveltyValuation(decay = NoveltyValuation.Decay.LOG)
    assertEquals(ln(3.0), v.value(0.0, 2, 0), eps)
    assertEquals(ln(8.0) - ln(6.0), v.value(0.0, 2, 5), eps)
  }

  @Test
  fun logSaturatesAsCumulativeCoverageGrows() {
    // The same novelty batch is worth strictly less the more coverage a source already has
    // (saturation: the transform is history-dependent).
    val v = NoveltyValuation(decay = NoveltyValuation.Decay.LOG)
    val batch = 2
    val early = v.value(0.0, batch, 0)
    val mid = v.value(0.0, batch, 5)
    val late = v.value(0.0, batch, 50)
    assertTrue(early > mid, "log: early batch ($early) should beat mid ($mid)")
    assertTrue(mid > late, "log: mid batch ($mid) should beat late ($late)")
  }

  @Test
  fun logHasDiminishingReturnsWithinAnIteration() {
    // Per-feature marginal value decreases as the iteration's own batch grows, because ΔU telescopes.
    val v = NoveltyValuation(decay = NoveltyValuation.Decay.LOG)
    val prior = 0
    val marginal1 = v.value(0.0, 1, prior) - v.value(0.0, 0, prior)
    val marginal5 = v.value(0.0, 5, prior) - v.value(0.0, 4, prior)
    val marginal10 = v.value(0.0, 10, prior) - v.value(0.0, 9, prior)
    assertTrue(marginal1 > marginal5, "1st feature ($marginal1) should beat 5th ($marginal5)")
    assertTrue(marginal5 > marginal10, "5th feature ($marginal5) should beat 10th ($marginal10)")
  }

  @Test
  fun sqrtValueMatchesClosedForm() {
    val v = NoveltyValuation(decay = NoveltyValuation.Decay.SQRT)
    assertEquals(sqrt(2.0) - sqrt(0.0), v.value(0.0, 2, 0), eps)
    assertEquals(sqrt(11.0) - sqrt(9.0), v.value(0.0, 2, 9), eps)
  }

  @Test
  fun satexpSaturatesAndStaysBoundedByK() {
    // U(c) = k(1 - e^(-c/k)) is bounded by k, so any single batch's ΔU < k and the curve flatlines
    // past the knee; a fixed batch's value collapses toward 0 deep past k.
    val k = 16.0
    val v = NoveltyValuation(decay = NoveltyValuation.Decay.SATEXP, k = k)
    val nearZero = v.value(0.0, 4, 0)
    val pastKnee = v.value(0.0, 4, 200)
    assertTrue(nearZero > pastKnee, "satexp: batch near 0 ($nearZero) should beat past-knee ($pastKnee)")
    assertTrue(pastKnee < 0.05, "satexp: a batch deep past the knee should be nearly worthless, was $pastKnee")
    // ΔU reaches k only in the float limit, where e^(-huge) underflows to 0.
    assertTrue(v.value(0.0, 100000, 0) <= k, "satexp single-batch ΔU must stay bounded by k=$k")
  }

  @Test
  fun satexpWithNonPositiveKDegradesInsteadOfNaN() {
    // A non-positive knee must fall back to DEFAULT_K, not produce NaN (which would poison every
    // Pareto comparison).
    for (badK in listOf(0.0, -1.0, -1000.0)) {
      val v = NoveltyValuation(decay = NoveltyValuation.Decay.SATEXP, k = badK)
      assertEquals(0.0, v.value(0.0, 0, 0), eps, "U(0)=0 must hold even for k=$badK")
      val value = v.value(0.0, 4, 0)
      assertTrue(value.isFinite(), "k=$badK must not yield NaN/inf, was $value")
      val withDefault = NoveltyValuation(decay = NoveltyValuation.Decay.SATEXP).value(0.0, 4, 0)
      assertEquals(withDefault, value, eps, "k=$badK should behave like DEFAULT_K")
    }
  }

  @Test
  fun weightScalesOnlyNoveltyTermNotScore() {
    // weight multiplies the novelty term but NOT the score (affine, non-uniform).
    val w = NoveltyValuation(weight = 0.4, decay = NoveltyValuation.Decay.LINEAR)
    assertEquals(0.9 + 0.4 * 2.0, w.value(0.9, 2, 0), eps)
  }
}
