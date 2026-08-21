package org.pastalab.fray.core

import org.pastalab.fray.core.concurrency.operations.ThreadStartOperation

/**
 * Reconstructs a schedule-stable spawn-path thread identity (`"r"`, `"r.0"`, `"r.0.1"`, …): each
 * thread named by its position in the family tree. Fray's birth-order [ThreadContext.index] is a
 * global counter grabbed in the scheduler-controlled creation hook, so the same logical thread gets a
 * different `index` when threads are born in a different order across schedules. A spawn-path is
 * stable because both coordinates are program-determined: the parent prefix (lineage is invariant
 * under reinterleaving) and the sibling ordinal, assigned as rank-by-`index` *among siblings* (not the
 * absolute `index`, which reshuffles). A parent builds its children synchronously in program order, so
 * each successive child grabs a strictly larger `index`; ranking siblings by `index` reproduces that
 * program order regardless of how cousins interleave. Residual instability only where a parent's
 * creation control-flow itself branches on a racy read (spawns a different set/order of children per
 * schedule): no scheme can name that, and this degrades to `index`-level there.
 *
 * Not thread-safe / single-iteration: driven from `onNewSchedule`, which fires only on the single
 * scheduled thread inside the scheduler's critical section, so plain maps are safe. Call [reset] each
 * iteration — the `index -> path` mapping is per-iteration because `index` is.
 */
class SpawnPathTracker {

  private val rootPath = "r"

  // index -> parentId (-1 for the root). Recorded once per thread, never overwritten.
  private val parentOf = HashMap<Int, Int>()

  private var cache: Map<Int, String>? = null

  fun reset() {
    parentOf.clear()
    cache = null
  }

  /**
   * Record the parent link of any thread still showing its initial [ThreadStartOperation] — the
   * window before it first runs, always caught at least once. [ThreadContext] adapter over
   * [recordParent].
   */
  fun observe(allThreads: Collection<ThreadContext>) {
    for (t in allThreads) {
      val op = t.pendingOperation
      if (op is ThreadStartOperation) recordParent(t.index, op.parentId)
    }
  }

  /** Idempotent: first link recorded for an index wins (a thread's parent is immutable). */
  fun recordParent(index: Int, parentId: Int) {
    if (parentOf.putIfAbsent(index, parentId) == null) cache = null
  }

  /** Spawn-path for this `index`; `"?<index>"` fallback for an unobserved link (shouldn't happen). */
  fun pathOf(index: Int): String {
    val map = cache ?: rebuild().also { cache = it }
    return map[index] ?: "?$index"
  }

  /**
   * Ascending `index` order resolves every parent before its children (a parent is constructed first,
   * so holds a smaller `index`); the per-parent counter assigns each successive child its sibling
   * ordinal.
   */
  private fun rebuild(): Map<Int, String> {
    // Known indices plus any referenced only as a parent (e.g. the root, whose ThreadStartOperation
    // may never have been caught because it ran before the first observe).
    val indices = sortedSetOf<Int>()
    indices.addAll(parentOf.keys)
    for (parent in parentOf.values) if (parent != -1) indices.add(parent)

    val result = HashMap<Int, String>()
    val nextOrdinal = HashMap<Int, Int>()
    for (i in indices) {
      // An index referenced as a parent but never recorded is the universal root.
      val parent = parentOf[i] ?: -1
      if (parent == -1) {
        result[i] = rootPath
        continue
      }
      val parentPath = result[parent] ?: rootPath
      val ordinal = nextOrdinal.merge(parent, 1, Int::plus)!! - 1
      result[i] = "$parentPath.$ordinal"
    }
    return result
  }
}
