package org.pastalab.fray.core.observers

import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.concurrency.operations.RacingOperation
import org.pastalab.fray.core.feedback.sources.HandoffFeedbackSource
import org.pastalab.fray.rmi.ScheduleObserver
import org.pastalab.fray.rmi.TestStatusObserver

/**
 * Per iteration, records the set of resource ids Fray observed on shared-resource accesses, to measure
 * whether those ids are stable across the schedules a search explores (an id set that changes every
 * iteration is noise; one that recurs is stable).
 *
 * Records the exact id the handoff feedback keys on via the single source of truth
 * [HandoffFeedbackSource.resourceBucket], so it never re-derives the mechanism and moves with the
 * feedback's notion of "which resource".
 *
 * Pure measurement: reads what the scheduler already computed, never influences a decision. Gated
 * behind `-Dfray.resourceIdLog=<path>`; inert otherwise. Forces
 * [RacingOperation.resolveRacingOperationStackTraceHash] on (the bucket needs it). Output is JSONL, one
 * object per iteration appended as it finishes so a mid-campaign crash keeps completed iterations.
 */
class ResourceIdentityLogger(private val logPath: Path) :
    ScheduleObserver<ThreadContext>, TestStatusObserver {

  // Distinct resource ids this iteration; a set (we care about the set realized, not counts).
  private val ids = sortedSetOf<Int>()
  private var iteration = 0

  init {
    // The bucket is a code location, 0 unless resolution is on; force it.
    RacingOperation.resolveRacingOperationStackTraceHash = true
    // Truncate any prior run's log; iterations append.
    logPath.parent?.createDirectories()
    logPath.writeText("")
  }

  override fun onExecutionStart() {
    ids.clear()
  }

  override fun onNewSchedule(allThreads: Collection<ThreadContext>, scheduled: ThreadContext) {
    val op = scheduled.pendingOperation
    if (op !is RacingOperation) return
    // Id the feedback keys on; 0 = no user frame, not a real id -- skip.
    val id = HandoffFeedbackSource.resourceBucket(op)
    if (id != 0) ids.add(id)
  }

  override fun onContextSwitch(current: ThreadContext, next: ThreadContext) {}

  override fun onReportError(throwable: Throwable) {}

  override fun onExecutionDone(bugFound: Throwable?) {
    logPath.appendText("{\"iter\":$iteration,\"ids\":[${ids.joinToString(",")}]}\n")
    iteration++
  }

  override fun saveToReportFolder(path: Path) {}
}
