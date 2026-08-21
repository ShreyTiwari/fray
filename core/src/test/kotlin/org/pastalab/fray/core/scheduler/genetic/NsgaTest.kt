package org.pastalab.fray.core.scheduler.genetic

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the NSGA-II scale-invariance properties, so a refactor of [Nsga] or of how objectives are
 * valued can't silently break them:
 *  1. Constant positive per-axis scaling leaves dominance, rank, and crowding unchanged -- so a bare
 *     per-source weight is (mostly) not a steering knob for the genetic scheduler.
 *  2. A per-axis non-linear (concave) remap DOES change crowding, reshaping the crowding tie-break.
 *  3. A strictly-increasing per-axis remap alone never flips dominance, but a history-dependent decay
 *     (same novelty count valued differently by prior coverage) can -- which is why decay keys on
 *     cumulative coverage, not the per-iteration count.
 */
class NsgaTest {

  private val eps = 1e-9

  private fun ind(vararg objectives: Double) = Individual(emptyList(), objectives, objectives.sum(), true)

  @Test
  fun constantPerAxisScaleLeavesDominanceUnchanged() {
    // dominates() is a per-axis sign comparison, so positive per-axis scaling can't change it.
    val base = listOf(ind(1.0, 4.0), ind(2.0, 2.0), ind(3.0, 1.0), ind(2.0, 3.0))
    val c = 7.0
    val d = 0.25
    val scaled = base.map { ind(it.objectives[0] * c, it.objectives[1] * d) }

    for (p in base.indices) for (q in base.indices) {
      assertEquals(
          Nsga.dominates(base[p].objectives, base[q].objectives),
          Nsga.dominates(scaled[p].objectives, scaled[q].objectives),
          "dominance($p,$q) must be invariant under positive per-axis scaling",
      )
    }
  }

  @Test
  fun constantPerAxisScaleLeavesRankAndCrowdingUnchanged() {
    val base = listOf(ind(1.0, 5.0), ind(2.0, 4.0), ind(3.0, 3.0), ind(4.0, 2.0), ind(5.0, 1.0), ind(2.5, 2.5))
    val scaled = base.map { ind(it.objectives[0] * 10.0, it.objectives[1] * 0.5) }

    Nsga.rankAndCrowd(base)
    Nsga.rankAndCrowd(scaled)

    for (i in base.indices) {
      assertEquals(base[i].rank, scaled[i].rank, "rank[$i] must be scale-invariant")
      // Crowding normalizes by each axis's range, so a constant per-axis factor cancels.
      if (base[i].crowding.isInfinite()) {
        assertTrue(scaled[i].crowding.isInfinite(), "boundary crowding[$i] must stay infinite")
      } else {
        assertEquals(base[i].crowding, scaled[i].crowding, eps, "crowding[$i] must be scale-invariant")
      }
    }
  }

  @Test
  fun nonLinearRemapChangesCrowding() {
    // Anti-diagonal Pareto front (all mutually non-dominated); a monotone per-axis remap keeps one
    // front but reshapes gaps non-affinely, changing which interior point is most crowded.
    // Front: A(1,5) B(2,4) C(4,3) D(7,2) E(11,1); interior = B,C,D.
    val a0 = doubleArrayOf(1.0, 2.0, 4.0, 7.0, 11.0)
    val a1 = doubleArrayOf(5.0, 4.0, 3.0, 2.0, 1.0)

    val linear = a0.indices.map { ind(a0[it], a1[it]) }
    Nsga.rankAndCrowd(linear)
    linear.forEach { assertEquals(0, it.rank, "anti-diagonal points are all on front 0") }
    val (lb, lc, ld) = Triple(linear[1].crowding, linear[2].crowding, linear[3].crowding)
    // Linear interior crowding increases toward the wide-gap end: B < C < D.
    assertTrue(lb < lc && lc < ld, "linear interior crowding B<C<D, was [$lb,$lc,$ld]")

    // Remap only axis0 by ln(1+x): monotone (order/dominance preserved), gaps change concavely.
    val logged = a0.indices.map { ind(ln(1.0 + a0[it]), a1[it]) }
    Nsga.rankAndCrowd(logged)
    logged.forEach { assertEquals(0, it.rank, "remap preserves the single front") }
    val (gb, gc, gd) = Triple(logged[1].crowding, logged[2].crowding, logged[3].crowding)
    // The concave remap compresses far gaps: D is no longer most-crowded, so interior order changed.
    assertTrue(gc > gd, "after log remap C should out-crowd D, was C=$gc D=$gd")
    assertTrue(gc > gb, "after log remap C should be the most-crowded interior point")
  }

  @Test
  fun perAxisMonotoneRemapDoesNotFlipDominance() {
    // Dominance only cares about per-axis sign, and a strictly-increasing map preserves signs.
    val base = listOf(ind(1.0, 4.0), ind(2.0, 2.0), ind(3.0, 1.0), ind(2.0, 3.0), ind(0.0, 0.0))
    val remapped = base.map { ind(ln(1.0 + it.objectives[0]), ln(1.0 + it.objectives[1])) }
    for (p in base.indices) for (q in base.indices) {
      assertEquals(
          Nsga.dominates(base[p].objectives, base[q].objectives),
          Nsga.dominates(remapped[p].objectives, remapped[q].objectives),
          "per-axis monotone remap must not change dominance($p,$q)",
      )
    }
  }

  @Test
  fun historyDependentDecayCanFlipDominance() {
    // The same novelty count valued against different prior coverage yields different objectives --
    // what lets decay re-order the front (a fixed per-axis remap cannot). A found 1 feature early
    // (prior 0), B found 2 late (prior 50): raw B(2) dominates A(1), but B's late finds are near
    // worthless under log saturation, so after decay A dominates B -- a flip driven only by history.
    fun logMarginal(newCount: Int, prior: Int) = ln(1.0 + prior + newCount) - ln(1.0 + prior)

    val aRaw = doubleArrayOf(1.0)
    val bRaw = doubleArrayOf(2.0)
    assertTrue(Nsga.dominates(bRaw, aRaw), "raw counts: B(2) dominates A(1)")
    assertTrue(!Nsga.dominates(aRaw, bRaw), "raw counts: A does not dominate B")

    val aLog = doubleArrayOf(logMarginal(1, 0))
    val bLog = doubleArrayOf(logMarginal(2, 50))
    assertTrue(
        Nsga.dominates(aLog, bLog),
        "history-dependent log decay flips it -- A dominates B (early discovery rewarded): " +
            "A=${aLog.toList()} B=${bLog.toList()}",
    )
    assertTrue(!Nsga.dominates(bLog, aLog), "after decay B no longer dominates A")
  }

  @Test
  fun singleObjectiveDominanceIsPlainNumericComparison() {
    // Degenerate 1-D case: dominance reduces to numeric >, matching pre-multi-objective behavior.
    assertTrue(Nsga.dominates(doubleArrayOf(5.0), doubleArrayOf(3.0)))
    assertTrue(!Nsga.dominates(doubleArrayOf(3.0), doubleArrayOf(5.0)))
    assertTrue(!Nsga.dominates(doubleArrayOf(4.0), doubleArrayOf(4.0)), "equal vectors don't dominate")
  }
}
