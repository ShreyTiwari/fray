package org.pastalab.fray.core.scheduler.qlearning

/**
 * Learned value table for the Q-learning scheduler: transient search state, never serialised and
 * never part of replay. Sparse `Map<StateKey, Map<LocationKey, Double>>`, rows created lazily;
 * unseen `(state, action)` pairs read as [QLearningConfig.optimisticInit], driving exploration
 * before epsilon fires. The outer access-ordered [LinkedHashMap] is capped at
 * [QLearningConfig.tableCap], evicting the LRU state row; eviction drops only learned values, so a
 * re-encountered row degrades safely back to optimistic init.
 */
class QTable(private val config: QLearningConfig) {
  // access-order map; evict manually in touchRow (not removeEldestEntry) so eviction happens only
  // at learning time, never mid-iteration action selection.
  private val rows = LinkedHashMap<Long, MutableMap<Long, Double>>(16, 0.75f, true)

  val size: Int
    get() = rows.size

  /** Q(state, action), optimistic init for an unseen pair. Read-only: never creates a row. */
  fun q(state: Long, action: Long): Double = rows[state]?.get(action) ?: config.optimisticInit

  /**
   * Highest-Q action among [candidateActions], returned as its INDEX into that list. Deterministic:
   * ties (including all-unseen, where every Q equals optimisticInit) break to the smallest
   * `(locationKey, index)` by explicit comparison, never map order or randomness. [candidateActions]
   * is in live sorted candidate order, so the index is the positional index for the replay trace.
   */
  fun bestActionIndex(state: Long, candidateActions: List<Long>): Int {
    var bestIndex = 0
    var bestQ = q(state, candidateActions[0])
    var bestKey = candidateActions[0]
    for (i in 1 until candidateActions.size) {
      val key = candidateActions[i]
      val value = q(state, key)
      if (value > bestQ || (value == bestQ && key < bestKey)) {
        bestQ = value
        bestKey = key
        bestIndex = i
      }
    }
    return bestIndex
  }

  /**
   * Monte-Carlo batch backup over one episode. For each visited `(state, action)` the target is
   * `G_t = terminalReward * gamma^(n-1-t)` and the value moves `Q += alpha * (G_t - Q)`. Only
   * learnable steps are on [trajectory]; the scheduler excludes `stackTraceHash == 0` steps.
   */
  fun batchUpdate(trajectory: List<Pair<Long, Long>>, terminalReward: Double) {
    val n = trajectory.size
    if (n == 0) return
    for (t in 0 until n) {
      val (state, action) = trajectory[t]
      val g = terminalReward * Math.pow(config.gamma, (n - 1 - t).toDouble())
      val row = touchRow(state)
      val current = row[action] ?: config.optimisticInit
      row[action] = current + config.alpha * (g - current)
    }
  }

  /** Fetch-or-create the row for [state], mark it MRU, and evict the LRU row if over capacity. */
  private fun touchRow(state: Long): MutableMap<Long, Double> {
    val existing = rows[state]
    if (existing != null) return existing
    val row = HashMap<Long, Double>()
    rows[state] = row
    if (rows.size > config.tableCap) {
      val eldest = rows.keys.iterator().next() // LRU end of an access-ordered LinkedHashMap.
      rows.remove(eldest)
    }
    return row
  }
}
