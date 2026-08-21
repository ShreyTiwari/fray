package org.pastalab.fray.core.scheduler.qlearning

/**
 * Exposes the latest `Runtime.observeState` value at `scheduleNextOperation` time.
 *
 * A QLearningScheduler is replaced every iteration and is not a `ScheduleObserver`, so it can't
 * subscribe to `observeState` itself; this holder lives on the long-lived `Configuration` (which owns
 * both the `observeState` fan-out and the live scheduler) and is injected into each fresh scheduler.
 *
 * Fray serializes execution and `observeState` is never a choice point, so the latest observed value
 * is a deterministic function of the schedule -- one Volatile field suffices, no lock. Replay never
 * reads this holder.
 */
class LastObservedStateHolder {
  @Volatile var value: Long = 0L
}
