package org.pastalab.fray.core.feedback.sources

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import org.pastalab.fray.core.feedback.CoverageSignal
import org.pastalab.fray.core.feedback.FeedbackSource
import org.pastalab.fray.core.feedback.HitBucket
import org.pastalab.fray.core.feedback.IterationResult

/**
 * Value/state feedback: scores the *program state* a schedule produced (values pushed via
 * [org.pastalab.fray.runtime.Runtime.observeState]), not the schedule shape, so a coverage-guided
 * scheduler is rewarded for driving the program into unseen states.
 *
 * Push-during-run: values arrive through [observe] at arbitrary points and are snapshotted in
 * [collect]; not a [org.pastalab.fray.rmi.ScheduleObserver] since it is not driven by scheduling
 * decisions. [observe] runs concurrently on worker threads (the side-channel does not serialize
 * them), so the accumulator must be thread-safe.
 */
class StateFeedbackSource
@JvmOverloads
constructor(
    override val name: String = "state",
    /**
     * Optional value to steer toward: [collect] emits a [CoverageSignal.score] proportional to the
     * closest approach this iteration, an extra gradient toward a specific value. Null = novelty
     * only.
     */
    private val target: Long? = null,
    /**
     * When true, [collect] folds each value's observation count into an AFL-style [HitBucket] so a
     * value seen in a new frequency bucket counts as new coverage; false = binary observed-or-not
     * feature (`--feedback state:hits=false`).
     */
    private val countHits: Boolean = true,
) : FeedbackSource {

  // Reserves the high 8 bits for the aggregator's per-source namespace tag (avoids cross-source
  // feature collisions in the global union).
  private val payloadMask = 0x00FF_FFFF_FFFF_FFFFL

  // Value-hash -> hit count this iteration; keys alone are the features when countHits = false.
  private val hits = ConcurrentHashMap<Long, Int>()

  @Volatile private var bestProximity = Long.MAX_VALUE

  fun observe(value: Long) {
    // merge is atomic, so concurrent observes of the same value count correctly.
    hits.merge(mix(value) and payloadMask, 1, Int::plus)
    if (target != null) {
      val d = abs(value - target)
      if (d < bestProximity) bestProximity = d
    }
  }

  override fun beforeIteration(iteration: Int) {
    hits.clear()
    bestProximity = Long.MAX_VALUE
  }

  override fun collect(result: IterationResult): CoverageSignal {
    // Snapshot copy keeps this safe against in-flight observes.
    val snapshot =
        if (countHits) {
          hits.entries.mapTo(mutableSetOf()) { (raw, count) -> HitBucket.encode(raw, count) }
        } else {
          hits.keys.toSet()
        }
    val score =
        if (target != null && bestProximity != Long.MAX_VALUE) 1.0 / (1.0 + bestProximity) else 0.0
    return CoverageSignal(name, snapshot, score)
  }

  /** SplitMix64 finalizer: stable 64-bit avalanche hash, injective over small value ranges. */
  private fun mix(value: Long): Long {
    var z = value + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
  }
}
