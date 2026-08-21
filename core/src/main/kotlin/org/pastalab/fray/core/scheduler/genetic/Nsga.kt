package org.pastalab.fray.core.scheduler.genetic

/**
 * NSGA-II-lite: multi-objective ranking for the genetic scheduler when several feedback sources
 * are active. Collapsing sources into one scalar lets a generalist genome evict the specialists,
 * so we keep the per-source objective vector and order by Pareto dominance ([Individual.rank],
 * front 0 = non-dominated) plus crowding distance ([Individual.crowding], diversity tie-break with
 * +infinity at each objective's extremes). [crowdedBetter] combines them: lower rank wins, then
 * higher crowding.
 *
 * Pure function of the objective vectors -- no randomness/clock/shuffle -- so replays stay
 * bit-identical without needing a Randomness. With one objective this reduces to ranking by that
 * value, so single-source feedback behaves as before. O(n^2 * objectives) over the bounded
 * population.
 */
object Nsga {

  /**
   * Pareto dominance: [a] dominates [b] iff no worse on every objective and strictly better on
   * at least one (all objectives maximised). Compares the shared prefix (defensive against a
   * length mismatch).
   *
   * Per-axis sign comparison, so scaling any axis by a positive constant changes nothing -- a
   * per-source `weight` cannot steer pure-novelty sources; only a non-linear valuation
   * (saturation decay) re-orders fronts.
   */
  fun dominates(a: DoubleArray, b: DoubleArray): Boolean {
    val n = minOf(a.size, b.size)
    if (n == 0) return false
    var strictlyBetterSomewhere = false
    for (i in 0 until n) {
      if (a[i] < b[i]) return false
      if (a[i] > b[i]) strictlyBetterSomewhere = true
    }
    return strictlyBetterSomewhere
  }

  /**
   * Assign [Individual.rank] and [Individual.crowding] in place. Must run before
   * selection/truncation whenever the population's contents changed.
   */
  fun rankAndCrowd(members: List<Individual>) {
    if (members.isEmpty()) return

    // Fast non-dominated sort (Deb et al.): front 0 = dominated by nobody; peel fronts off by
    // decrementing counts.
    val dominatedBy = Array(members.size) { mutableListOf<Int>() } // p -> indices p dominates
    val dominationCount = IntArray(members.size) // p -> how many dominate p
    val fronts = mutableListOf<MutableList<Int>>()
    val firstFront = mutableListOf<Int>()

    for (p in members.indices) {
      for (q in members.indices) {
        if (p == q) continue
        if (dominates(members[p].objectives, members[q].objectives)) {
          dominatedBy[p].add(q)
        } else if (dominates(members[q].objectives, members[p].objectives)) {
          dominationCount[p]++
        }
      }
      if (dominationCount[p] == 0) {
        members[p].rank = 0
        firstFront.add(p)
      }
    }
    fronts.add(firstFront)

    var i = 0
    while (i < fronts.size && fronts[i].isNotEmpty()) {
      val next = mutableListOf<Int>()
      for (p in fronts[i]) {
        for (q in dominatedBy[p]) {
          dominationCount[q]--
          if (dominationCount[q] == 0) {
            members[q].rank = i + 1
            next.add(q)
          }
        }
      }
      if (next.isEmpty()) break
      fronts.add(next)
      i++
    }

    fronts.forEach { front -> assignCrowding(members, front) }
  }

  /**
   * Crowding distance for one front: per objective, sort by it and add the range-normalised gap
   * between each point's two neighbours; boundary points get +infinity so the extremes survive
   * truncation. The `/ range` normalisation is invariant under a per-axis scale but not a
   * non-linear remap -- the other half of why saturation decay, not `weight`, steers the search.
   */
  private fun assignCrowding(members: List<Individual>, front: List<Int>) {
    if (front.isEmpty()) return
    front.forEach { members[it].crowding = 0.0 }
    if (front.size <= 2) {
      // Two-or-fewer members are all boundaries.
      front.forEach { members[it].crowding = Double.POSITIVE_INFINITY }
      return
    }
    val objectiveCount = members[front[0]].objectives.size
    for (m in 0 until objectiveCount) {
      val ordered = front.sortedBy { members[it].objectives[m] }
      val lo = members[ordered.first()].objectives[m]
      val hi = members[ordered.last()].objectives[m]
      members[ordered.first()].crowding = Double.POSITIVE_INFINITY
      members[ordered.last()].crowding = Double.POSITIVE_INFINITY
      val range = hi - lo
      if (range == 0.0) continue // objective separates nobody in this front
      for (k in 1 until ordered.size - 1) {
        val prev = members[ordered[k - 1]].objectives[m]
        val nextVal = members[ordered[k + 1]].objectives[m]
        // Don't overwrite an already-infinite distance from another objective.
        if (members[ordered[k]].crowding != Double.POSITIVE_INFINITY) {
          members[ordered[k]].crowding += (nextVal - prev) / range
        }
      }
    }
  }

  /** Is [a] preferred over [b]? Lower rank wins, then higher crowding. False on a genuine tie. */
  fun crowdedBetter(a: Individual, b: Individual): Boolean =
      a.rank < b.rank || (a.rank == b.rank && a.crowding > b.crowding)

  /** Best-first comparator over the crowded-comparison order. Requires [rankAndCrowd] to have run. */
  val crowdedComparator: Comparator<Individual> =
      Comparator { a, b ->
        when {
          a.rank != b.rank -> a.rank - b.rank
          else -> b.crowding.compareTo(a.crowding)
        }
      }
}
