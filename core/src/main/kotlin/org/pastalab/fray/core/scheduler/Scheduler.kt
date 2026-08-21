package org.pastalab.fray.core.scheduler

import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.feedback.IterationFeedback
import org.pastalab.fray.core.randomness.ControlledRandom
import org.pastalab.fray.core.randomness.Randomness

abstract class Scheduler(val rand: Randomness) {
  /**
   * No-arg constructor required by kotlinx.serialization: a `@Serializable` subclass of a
   * non-serializable parent can only be generated when the parent has one. Subclasses that persist
   * their schedule (genome, decision trace) rely on this; [rand] is re-supplied by the driver.
   */
  constructor() : this(ControlledRandom())

  abstract fun scheduleNextOperation(
      threads: List<ThreadContext>,
      allThreads: Collection<ThreadContext>,
  ): ThreadContext

  abstract fun nextIteration(randomness: Randomness): Scheduler

  /**
   * Feedback-aware variant of [nextIteration]. [feedback] is the coverage-guided view of the
   * just-finished iteration; only schedulers that evolve across iterations consume it. The default
   * discards it, so schedulers that ignore feedback need only implement [nextIteration].
   */
  open fun nextIteration(randomness: Randomness, feedback: IterationFeedback): Scheduler =
      nextIteration(randomness)

  /** Random number requested by the runtime (e.g. spurious wakeups, signal target selection). */
  fun nextInt(): Int = rand.nextInt()

  fun nextDouble(): Double = rand.nextDouble()

  fun nextDouble(origin: Double, bound: Double): Double = rand.nextDouble(origin, bound)
}
