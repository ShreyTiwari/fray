package org.pastalab.fray.core.feedback

/**
 * A single form of execution feedback (schedule novelty, coverage, happens-before
 * edges, external file state, ...), normalised to [CoverageSignal].
 *
 * Not an RMI `Remote` observer: this is a once-per-iteration returns-a-value step, not
 * a per-decision returns-nothing callback. Streaming sources may also implement an
 * observer and return the accumulated snapshot from [collect]; pull-at-end sources do
 * all their work in [collect].
 *
 * This package must not import `scheduler/`: `Scheduler` imports `IterationFeedback`,
 * so importing back would cycle.
 */
interface FeedbackSource {
  val name: String

  /**
   * Pull-at-end sources delete the previous iteration's artifact here so a target that
   * fails to write isn't scored on stale data; streaming sources reset their accumulator.
   */
  fun beforeIteration(iteration: Int) {}

  /**
   * A missing, partial, or corrupt artifact must yield an empty signal, never throw
   * (the aggregator's runCatching is a backstop, not a license to rely on it).
   */
  fun collect(result: IterationResult): CoverageSignal
}
