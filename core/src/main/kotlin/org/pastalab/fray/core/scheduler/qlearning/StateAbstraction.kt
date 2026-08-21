package org.pastalab.fray.core.scheduler.qlearning

import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.concurrency.operations.RacingOperation

/**
 * Pluggable state projection for the Q-learning scheduler: a single `Long` `StateKey` per choice
 * point. Not full program state -- a user-chosen projection, since "same state" is domain-specific.
 * A `fun interface`: supply a lambda, or a class loaded by FQN (`--qlearning-state-class`).
 *
 * An implementation must be pure & deterministic given the schedule (same run => same key sequence),
 * may read each candidate's `pendingOperation`, and MUST NOT read `allThreads`
 * (`registeredThreads.values` iterates nondeterministically), block/schedule/mutate, or throw (a
 * throw degrades the step to a fixed key and learns nothing).
 */
fun interface StateAbstraction {
  /**
   * @param candidates enabled threads, ALREADY sorted by `thread.id`, so ordering is reproducible.
   */
  fun stateKey(candidates: List<ThreadContext>): Long

  companion object {
    private const val PAYLOAD_MASK = 0x00FF_FFFF_FFFF_FFFFL
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // FNV-1a offset basis
    private const val FNV_PRIME = 0x100000001b3L // FNV-1a prime

    /** FNV-1a over a char sequence, masked to the payload bits. */
    private fun fnv1a(s: CharSequence): Long {
      var hash = FNV_OFFSET
      for (c in s) {
        hash = hash xor c.code.toLong()
        hash *= FNV_PRIME
      }
      return hash and PAYLOAD_MASK
    }

    /** SplitMix64 finalizer -- avalanche hash of a single value. */
    private fun mix(value: Long): Long {
      var z = value + -0x61c8864680b583ebL
      z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
      z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
      return (z xor (z ushr 31)) and PAYLOAD_MASK
    }

    /** Per-candidate descriptor; `withLocation` folds in `stackTraceHash`. */
    private fun describe(t: ThreadContext, withLocation: Boolean): String {
      val op = t.pendingOperation
      val head = op.javaClass.simpleName
      return if (op is RacingOperation) {
        val loc = if (withLocation) ":loc=${op.stackTraceHash}" else ""
        "$head:${op.type}:${op.resource}$loc"
      } else {
        head
      }
    }

    /** Hash of the candidate multiset by `(op class, MemoryOpType, resource)`. No StackWalker. */
    fun candidateSet(): StateAbstraction = StateAbstraction { candidates ->
      val sb = StringBuilder("n=${candidates.size};")
      for (t in candidates) sb.append(describe(t, withLocation = false)).append('|')
      fnv1a(sb)
    }

    /** `candidateSet` plus each candidate's `RacingOperation.stackTraceHash`. */
    fun location(): StateAbstraction = StateAbstraction { candidates ->
      val sb = StringBuilder("n=${candidates.size};")
      for (t in candidates) sb.append(describe(t, withLocation = true)).append('|')
      fnv1a(sb)
    }

    /**
     * State IS the latest value the program pushed via [org.pastalab.fray.runtime.Runtime.observeState],
     * read through [provider]. Ignores the candidate set by design.
     */
    fun observedState(provider: () -> Long): StateAbstraction = StateAbstraction { mix(provider()) }

    /**
     * Resolve a possibly-`auto` mode string to a concrete built-in mode. `auto` (empty string) picks
     * observed-state when a `state` feedback source is active (via the `fray.feedback` property), else
     * candidate-set. Any unknown value also degrades to `candidateSet`.
     */
    fun resolveMode(mode: String): String {
      if (mode in QLearningConfig.KNOWN_MODES) return mode
      val spec = System.getProperty("fray.feedback").orEmpty()
      val hasState =
          spec.split(",").any { it.trim().substringBefore(":").trim() == "state" }
      return if (hasState) QLearningConfig.MODE_OBSERVED_STATE else QLearningConfig.MODE_CANDIDATE_SET
    }

    /**
     * Build the abstraction for a (possibly `auto`) mode. [stateClass] is the FQN for `custom` mode; a
     * custom class that is missing, uninstantiable, or not a [StateAbstraction] degrades to
     * `candidateSet` rather than failing the run.
     */
    fun forMode(
        mode: String,
        stateClass: String,
        observedProvider: () -> Long,
    ): StateAbstraction =
        when (resolveMode(mode)) {
          QLearningConfig.MODE_OBSERVED_STATE -> observedState(observedProvider)
          QLearningConfig.MODE_LOCATION -> location()
          QLearningConfig.MODE_CUSTOM -> loadCustom(stateClass) ?: candidateSet()
          else -> candidateSet()
        }

    /** Reflectively load a user [StateAbstraction] by FQN; returns null on any failure. */
    private fun loadCustom(stateClass: String): StateAbstraction? {
      if (stateClass.isBlank()) return null
      return runCatching {
            val loader =
                Thread.currentThread().contextClassLoader
                    ?: StateAbstraction::class.java.classLoader
            val clazz = loader.loadClass(stateClass)
            clazz.getConstructor().newInstance() as StateAbstraction
          }
          .getOrNull()
    }
  }
}
