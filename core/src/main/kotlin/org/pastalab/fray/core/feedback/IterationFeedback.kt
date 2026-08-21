package org.pastalab.fray.core.feedback

/**
 * Aggregated per-iteration feedback from [FeedbackAggregator.aggregate] for
 * `Scheduler.nextIteration`.
 *
 * [objectives] is the per-source vector (`score + newFeatureCount`, in source order) that lets
 * the GA do NSGA-II Pareto ranking instead of collapsing sources into one scalar. [fitness] is
 * `Σ objectives`, kept only for reporting/debug; the GA ranks on [objectives].
 *
 * Transient; never serialised, so it stays off the replay path.
 */
data class IterationFeedback(
    val result: IterationResult,
    val signals: List<CoverageSignal>,
    val newFeatures: Set<Long>,
    val fitness: Double,
    val objectives: Map<String, Double> = emptyMap(),
) {
  val isNovel: Boolean
    get() = newFeatures.isNotEmpty()

  /**
   * Dense objective vector in source order. Every individual in a run shares one length (source
   * count is fixed per run), the invariant Nsga relies on.
   */
  fun objectiveVector(): DoubleArray = objectives.values.toDoubleArray()

  companion object {
    /**
     * Seed feedback for the first iteration. Its 0-dim objective vector is only used when no
     * feedback source is registered, so the GA never compares a 0-dim individual against an N-dim
     * one in the same run.
     */
    val EMPTY =
        IterationFeedback(
            result = IterationResult(iteration = 0, bugFound = null, reportFolder = null),
            signals = emptyList(),
            newFeatures = emptySet(),
            fitness = 0.0,
            objectives = emptyMap(),
        )
  }
}
