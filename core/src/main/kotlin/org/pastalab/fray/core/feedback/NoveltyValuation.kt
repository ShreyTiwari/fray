package org.pastalab.fray.core.feedback

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Turns a source's per-iteration novelty into its objective value:
 * `score + weight * (U(priorSize + newForSource) - U(priorSize))`, where `U` is a non-decreasing
 * concave utility with `U(0) = 0`. Concavity gives both saturation over time (a fixed batch of new
 * features is worth less once a source has discovered a lot) and diminishing returns within an
 * iteration; [Decay.LINEAR] makes `U(c) = c`, so the term collapses to `newForSource`.
 *
 * Saturation decay is the real steering lever: keyed on cumulative coverage, it is a
 * history-dependent nonlinear transform that re-orders Pareto fronts and reshapes crowding.
 * [weight] mostly does NOT steer -- Pareto dominance and crowding are invariant under a positive
 * per-axis scale, so for a pure-novelty source (`score = 0`) it changes only the reported scalar
 * fitness. It bites only when [score] is nonzero: then it scales the novelty term but not score,
 * an affine non-uniform transform that CAN flip dominance.
 *
 * [value] is pure (no RNG, clock, or mutable state); never serialised, no part in replay.
 */
data class NoveltyValuation(
    /** Per-source multiplier on the novelty term; see class KDoc for what it does not steer. */
    val weight: Double = 1.0,
    val decay: Decay = Decay.LINEAR,
    /** Knee/ceiling for [Decay.SATEXP] only; non-positive falls back to [DEFAULT_K] so `U(0)=0`. */
    val k: Double = DEFAULT_K,
) {
  /** Concave utility `U(c)`; `U(0) == 0` for every variant. */
  private fun u(c: Int): Double {
    val x = c.toDouble()
    return when (decay) {
      Decay.LINEAR -> x
      Decay.LOG -> ln(1.0 + x)
      Decay.SQRT -> sqrt(x)
      // Guard k > 0 here, not only at CLI parse: a non-positive k makes the expression NaN and
      // poisons every Pareto comparison. Degrade to the default knee instead of failing.
      Decay.SATEXP -> {
        val knee = if (k > 0.0) k else DEFAULT_K
        knee * (1.0 - exp(-x / knee))
      }
    }
  }

  /**
   * Objective value for one source this iteration.
   *
   * @param score scalar from [CoverageSignal.score] (0.0 for pure-novelty); added UNSCALED.
   * @param newForSource features new to this source this iteration, BEFORE the fold.
   * @param priorSize cumulative distinct-feature count BEFORE this iteration's fold; only matters
   *   for saturating curves (with [Decay.LINEAR] the `U` difference collapses to `newForSource`).
   */
  fun value(score: Double, newForSource: Int, priorSize: Int): Double =
      score + weight * (u(priorSize + newForSource) - u(priorSize))

  /** Utility curves `U(c)` selectable per source via `--feedback <kind>:decay=<...>`. */
  enum class Decay {
    /** `U(c) = c`. No decay. */
    LINEAR,
    /** `U(c) = ln(1 + c)`. Unbounded, parameter-free; default once any decay knob is requested. */
    LOG,
    /** `U(c) = sqrt(c)`. Milder, still-unbounded decay. */
    SQRT,
    /** `U(c) = k * (1 - e^(-c/k))`. Bounded by [k]; can flatline a saturated axis. */
    SATEXP,
  }

  companion object {
    /** Default SATEXP knee; also the fallback for a non-positive [k]. */
    const val DEFAULT_K = 4096.0

    /** Weight 1.0 + [Decay.LINEAR]: no-op valuation used for any source with no decay knobs. */
    val IDENTITY = NoveltyValuation()
  }
}
