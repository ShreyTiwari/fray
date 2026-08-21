package org.pastalab.fray.core.scheduler.genetic

import org.pastalab.fray.core.feedback.CoverageSignal
import org.pastalab.fray.core.feedback.IterationFeedback
import org.pastalab.fray.core.feedback.IterationResult
import org.pastalab.fray.core.randomness.ControlledRandom
import org.pastalab.fray.core.scheduler.GeneticScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins stagnation-restart: non-empty newFeatures is novel (resets the stagnation counter), empty is
 * non-novel (advances toward a restart).
 */
class GeneticSchedulerRestartTest {

  private var featureSeq = 1L

  /** Novel: a fresh feature each call so the union genuinely grows. */
  private fun novel(): IterationFeedback =
      IterationFeedback(
          result = IterationResult(0, null, null),
          signals = emptyList(),
          newFeatures = setOf(featureSeq++),
          fitness = 1.0,
          objectives = mapOf("s" to 1.0),
      )

  /** Non-novel: empty newFeatures => isNovel == false. */
  private fun stale(): IterationFeedback =
      IterationFeedback(
          result = IterationResult(0, null, null),
          signals = emptyList(),
          newFeatures = emptySet(),
          fitness = 0.0,
          objectives = mapOf("s" to 0.0),
      )

  private fun newScheduler(config: GeneticConfig): GeneticScheduler =
      GeneticScheduler(
              mutableListOf(1, 2, 3),
              ControlledRandom(integers = mutableListOf(), doubles = mutableListOf()),
          )
          .also {
            it.config = config
            it.population = Population(config.populationCap)
          }

  private fun rand() = ControlledRandom(integers = mutableListOf(), doubles = mutableListOf())

  @Test
  fun stagnationCounterAdvancesAndResetsOnNovelty() {
    var s = newScheduler(GeneticConfig(restartStagnation = 100)) // never restarts here
    s = s.nextIteration(rand(), stale()) as GeneticScheduler
    assertEquals(1, s.stagnationCounter)
    s = s.nextIteration(rand(), stale()) as GeneticScheduler
    assertEquals(2, s.stagnationCounter)
    s = s.nextIteration(rand(), novel()) as GeneticScheduler
    assertEquals(0, s.stagnationCounter, "a novel iteration resets the stagnation counter")
  }

  @Test
  fun restartDisabledNeverFlushesNorCountsStagnation() {
    var s = newScheduler(GeneticConfig(restartStagnation = 0))
    repeat(30) { s = s.nextIteration(rand(), stale()) as GeneticScheduler }
    // Restart disabled: counter never advances, archive only grows, never flushed to a front.
    assertEquals(0, s.stagnationCounter, "disabled => stagnation counter stays 0")
    assertTrue(s.population.size > 1, "disabled => archive accumulates, no restart flush")
  }

  @Test
  fun restartTriggersAtThresholdAndKeepsAFrontThenReseeds() {
    val threshold = 5
    // Seed the archive with novel iterations so there's a real population to flush.
    var s = newScheduler(GeneticConfig(restartStagnation = threshold, seedPopulation = 3))
    repeat(8) { s = s.nextIteration(rand(), novel()) as GeneticScheduler }
    val beforeRestart = s.population.size
    assertTrue(beforeRestart > 1, "precondition: a non-trivial archive built up")

    // Stagnate exactly `threshold` consecutive iterations to trip the restart on the last one.
    repeat(threshold) { s = s.nextIteration(rand(), stale()) as GeneticScheduler }

    // Archive flushed to its Pareto front (plus the triggering iteration's record), so no larger
    // than before stagnation.
    assertEquals(0, s.stagnationCounter, "counter resets after a restart")
    assertTrue(
        s.population.size <= beforeRestart,
        "restart flushed the archive to its front (was $beforeRestart, now ${s.population.size})")
    assertTrue(s.reseedRemaining >= 0, "reseed burst is queued/active after restart")
  }

  @Test
  fun stalePathStillProducesAUsableNextScheduler() {
    // Smoke: a long stagnating run keeps returning valid schedulers.
    var s = newScheduler(GeneticConfig(restartStagnation = 4, seedPopulation = 2))
    repeat(20) {
      s = s.nextIteration(rand(), if (it % 7 == 0) novel() else stale()) as GeneticScheduler
    }
    assertTrue(s.population.size >= 1, "population always retains at least the elite/seed")
  }
}
