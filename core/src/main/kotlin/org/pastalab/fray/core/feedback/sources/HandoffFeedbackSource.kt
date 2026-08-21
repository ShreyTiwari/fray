package org.pastalab.fray.core.feedback.sources

import java.nio.file.Path
import org.pastalab.fray.core.SpawnPathTracker
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.concurrency.operations.RacingOperation
import org.pastalab.fray.core.feedback.CoverageSignal
import org.pastalab.fray.core.feedback.FeedbackSource
import org.pastalab.fray.core.feedback.HitBucket
import org.pastalab.fray.core.feedback.IterationResult
import org.pastalab.fray.rmi.ScheduleObserver
import org.pastalab.fray.rmi.TestStatusObserver
import org.pastalab.fray.runtime.MemoryOpType

/**
 * Resource-handoff feedback: rewards the grey-box scheduler for finding new ways a shared resource is
 * passed between threads. Each scheduled [RacingOperation] is paired with the previous *distinct*
 * accessor of the same resource into a directed handoff ("A passed the resource to B"); each distinct
 * handoff is a coverage feature -- the AFL coverage-map idea applied to interleavings.
 *
 * A handoff is inferred from access *sequence*, not a verified release->acquire edge (Fray exposes no
 * unlock event to observers): temporal adjacency, not happens-before. The name says "handoff" on purpose.
 *
 * Endpoints are keyed by spawn-path ([SpawnPathTracker]), not raw birth-order [ThreadContext.index]: a
 * steered scheduler acts on threads, so feedback must name them stably across the schedules the search
 * explores, and `index` scrambles the same logical thread run-to-run. Endpoint op types ([MemoryOpType])
 * are part of the handoff, so write->read differs from read->read.
 *
 * The resource *bucket* is keyed on access code location ([RacingOperation.stackTraceHash]), not the raw
 * `identityHashCode`-based [RacingOperation.resource] (fresh id every iteration as the object is
 * re-allocated); a code location is schedule-stable. Cost: an over-merge of distinct resources acquired
 * at the same source line. Endpoints stay thread ids, so the self-access guard still compares threads.
 *
 * Streaming-observer source: [onNewSchedule] accumulates, [collect] reports at iteration end.
 * Accumulation uses plain collections because [onNewSchedule] fires only on the single scheduled thread
 * inside the scheduler's critical section, unlike [StateFeedbackSource.observe] (arbitrary worker
 * threads, needs a concurrent set).
 *
 * Dormant until registered as a [ScheduleObserver] in `Configuration.registerFeedbackSpec`; until then
 * it collects an empty signal.
 *
 * With [countHits] = true (default) each handoff's per-iteration fire count is folded into an AFL bucket,
 * so a known handoff at a new frequency reads as novelty; `handoff:hits=false` makes it binary.
 */
class HandoffFeedbackSource(
    override val name: String = "handoff",
    private val countHits: Boolean = true,
) : FeedbackSource, ScheduleObserver<ThreadContext>, TestStatusObserver {

  data class Access(val id: Int, val type: MemoryOpType)

  /** A directed handoff on a resource: accessed by [from], then next by a different accessor [to]. */
  data class Handoff(val from: Access, val to: Access)

  // Per-iteration hit count per (bucket, handoff) feature. Plain map is safe -- onNewSchedule is
  // single-threaded inside the scheduler critical section. When countHits = false the keys are the
  // features and the counts are ignored.
  private val handoffHits = HashMap<Long, Int>()

  // Most recent access per resource bucket, to pair consecutive distinct accessors into a handoff.
  private val lastAccess = HashMap<Int, Access>()

  // Reconstructs each thread's spawn-path from the (index, parentId) on the wire. Keyed on `index`,
  // so reset per iteration.
  private val spawnPaths = SpawnPathTracker()

  override fun beforeIteration(iteration: Int) {
    handoffHits.clear()
    lastAccess.clear()
    spawnPaths.reset()
  }

  override fun onNewSchedule(allThreads: Collection<ThreadContext>, scheduled: ThreadContext) {
    // Learn parent links BEFORE keying any access on a thread. A thread shows its ThreadStartOperation
    // (carrying parentId) on every decision until it first runs, so the link is captured in time.
    spawnPaths.observe(allThreads)
    val op = scheduled.pendingOperation
    if (op !is RacingOperation) return
    // A 0 bucket means "no user frame / resolution off" -- skip it so unrelated accesses don't merge
    // into one bogus bucket.
    val bucket = resourceBucket(op)
    if (bucket == 0) return
    val current = Access(accessId(op, scheduled), op.type)
    val previous = lastAccess.put(bucket, current)
    // A handoff needs two different accessors; a thread re-accessing its own resource is not one.
    if (previous != null && previous.id != current.id) {
      handoffHits.merge(featureId(bucket, Handoff(previous, current)), 1, Int::plus)
    }
  }

  override fun collect(result: IterationResult): CoverageSignal {
    val features =
        if (countHits) {
          handoffHits.entries.mapTo(mutableSetOf()) { (raw, count) -> HitBucket.encode(raw, count) }
        } else {
          handoffHits.keys.toSet()
        }
    // Name MUST be the registered name or the aggregator namespaces these features under tag 0 and
    // collides with the source at index 0.
    return CoverageSignal(name, features)
  }

  // Unused ScheduleObserver / TestStatusObserver callbacks.
  override fun onContextSwitch(current: ThreadContext, next: ThreadContext) {}

  override fun onExecutionStart() {}

  override fun onExecutionDone(bugFound: Throwable?) {}

  override fun onReportError(throwable: Throwable) {}

  override fun saveToReportFolder(path: Path) {}

  /**
   * Endpoint id for a handoff edge, the acting thread's spawn-path interned via [String.hashCode]
   * (deterministic, so schedule-stable). Path resolved by [spawnPaths], populated in [onNewSchedule]
   * before any access is keyed. Coverage is a set of FNV hashes, so a path-hash collision is benign.
   */
  private fun accessId(op: RacingOperation, scheduled: ThreadContext): Int =
      spawnPaths.pathOf(scheduled.index).hashCode()

  /**
   * Fingerprint one (resource-bucket, handoff) into a feature id. Hashes the readable [describe] key
   * rather than bit-packing so the structure stays legible. Masked to the low 56 bits because
   * `FeedbackAggregator` reserves the high 8 bits as a per-source namespace tag.
   */
  private fun featureId(bucket: Int, handoff: Handoff): Long {
    var hash = -0x340d631b7bdddcdbL // FNV-1a offset basis
    for (c in describe(bucket, handoff)) {
      hash = hash xor c.code.toLong()
      hash *= 0x100000001b3L // FNV-1a prime
    }
    return hash and PAYLOAD_MASK
  }

  /** Readable key for one handoff, e.g. `loc=123 2:MEMORY_WRITE -> 3:MEMORY_READ`. */
  private fun describe(bucket: Int, handoff: Handoff): String =
      "loc=$bucket ${handoff.from.id}:${handoff.from.type} -> ${handoff.to.id}:${handoff.to.type}"

  companion object {
    // Keep the payload clear of the aggregator's high-8-bit per-source tag.
    private const val PAYLOAD_MASK = 0x00FF_FFFF_FFFF_FFFFL

    /**
     * The resource a handoff is grouped under: access code location, not the `identityHashCode`-based
     * [RacingOperation.resource] (reshuffles every iteration). Exposed as the single source of truth so
     * measurement observers key on the same id the feedback does. A 0 return means "no user frame /
     * resolution off"; callers skip those.
     */
    fun resourceBucket(op: RacingOperation): Int = op.stackTraceHash
  }
}
