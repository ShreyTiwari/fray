package org.pastalab.fray.core.feedback.sources

import java.nio.file.Path
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.concurrency.operations.RacingOperation
import org.pastalab.fray.core.feedback.CoverageSignal
import org.pastalab.fray.core.feedback.FeedbackSource
import org.pastalab.fray.core.feedback.HitBucket
import org.pastalab.fray.core.feedback.IterationResult
import org.pastalab.fray.rmi.ScheduleObserver
import org.pastalab.fray.rmi.TestStatusObserver

/**
 * Code-location coverage feedback: rewards the grey-box scheduler for reaching concurrency
 * code-sites ([RacingOperation.stackTraceHash], up to two user-code frames) not hit before --
 * AFL coverage-map novelty over code locations, not basic blocks (only concurrency ops are
 * instrumented).
 *
 * [RacingOperation.stackTraceHash] is 0 unless [RacingOperation.resolveRacingOperationStackTraceHash]
 * is on (off by default for cost); the `location` arm forces it on, else every hash collapses to 0.
 */
class CodeLocationFeedbackSource(
    override val name: String = "location",
    private val countHits: Boolean = true,
) : FeedbackSource, ScheduleObserver<ThreadContext>, TestStatusObserver {

  // location -> hit count this iteration. Plain map is safe: onNewSchedule is single-threaded inside
  // the scheduler critical section. When countHits = false only the keys matter.
  private val locationHits = HashMap<Long, Int>()

  override fun beforeIteration(iteration: Int) {
    locationHits.clear()
  }

  override fun onNewSchedule(allThreads: Collection<ThreadContext>, scheduled: ThreadContext) {
    val op = scheduled.pendingOperation
    if (op !is RacingOperation) return
    val hash = op.stackTraceHash
    // 0 = unresolved or no user frame -- not a real location.
    if (hash == 0) return
    // Widen to a positive Long; fits the payload bits the aggregator reserves below its per-source tag.
    val raw = hash.toLong() and 0xFFFFFFFFL
    locationHits.merge(raw, 1, Int::plus)
  }

  override fun collect(result: IterationResult): CoverageSignal {
    // hits=on packs the AFL bucket of the count into the feature, so a known site in a new frequency
    // bucket reads as novelty; hits=off makes the location a binary reached/not feature.
    val features =
        if (countHits) {
          locationHits.entries.mapTo(mutableSetOf()) { (raw, count) -> HitBucket.encode(raw, count) }
        } else {
          locationHits.keys.toSet()
        }
    // Pure novelty source: no score. Name must be the registered name or the aggregator namespaces
    // these features under tag 0 and collides with the source at index 0.
    return CoverageSignal(name, features)
  }

  override fun onContextSwitch(current: ThreadContext, next: ThreadContext) {}

  override fun onExecutionStart() {}

  override fun onExecutionDone(bugFound: Throwable?) {}

  override fun onReportError(throwable: Throwable) {}

  override fun saveToReportFolder(path: Path) {}
}
