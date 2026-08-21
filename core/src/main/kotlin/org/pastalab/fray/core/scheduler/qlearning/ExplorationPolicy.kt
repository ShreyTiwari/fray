package org.pastalab.fray.core.scheduler.qlearning

import org.pastalab.fray.core.randomness.Randomness

/**
 * How the Q-learning scheduler chooses an action at a choice point: exploit the learned table or
 * explore. MVP is [EpsilonGreedy]; UCB / Boltzmann are deferred behind this interface.
 *
 * An implementation must draw a fixed, branch-independent number of values from the
 * scheduler-private [rand] (never `config.randomness`), or the recorded `ControlledRandom` stream
 * desyncs between record and replay.
 */
interface ExplorationPolicy {
  /**
   * @return index into [candidateActions] of the chosen action; candidates are in live sorted order,
   *   so this doubles as the positional index for the replay trace.
   */
  fun selectIndex(
      state: Long,
      candidateActions: List<Long>,
      qTable: QTable,
      epsilon: Double,
      rand: Randomness,
  ): Int
}

/**
 * Epsilon-greedy: with probability `epsilon` pick a uniformly random candidate, else the argmax-Q
 * one. Both draws (coin, explore index) happen unconditionally before branching; the explore index
 * is ignored on the exploit branch. This fixed two-draw discipline keeps the recorded random stream
 * aligned across record/replay regardless of branch.
 */
object EpsilonGreedy : ExplorationPolicy {
  override fun selectIndex(
      state: Long,
      candidateActions: List<Long>,
      qTable: QTable,
      epsilon: Double,
      rand: Randomness,
  ): Int {
    // Draw both unconditionally before branching.
    val coin = rand.nextDouble()
    val exploreIndex = (rand.nextInt() % candidateActions.size).coerceAtLeast(0)
    return if (coin < epsilon) {
      exploreIndex
    } else {
      qTable.bestActionIndex(state, candidateActions)
    }
  }
}
