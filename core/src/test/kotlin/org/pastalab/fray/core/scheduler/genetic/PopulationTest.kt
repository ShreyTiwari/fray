package org.pastalab.fray.core.scheduler.genetic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for [Population.restartKeepingFront] and the Pareto-aware eviction it builds on. */
class PopulationTest {

  private fun ind(vararg objectives: Double) =
      Individual(objectives.toList().map { it.toInt() }, objectives, objectives.sum(), true)

  @Test
  fun restartKeepsOnlyTheParetoFront() {
    val pop = Population(maxSize = 10)
    pop.record(ind(3.0, 1.0))
    pop.record(ind(2.0, 2.0))
    pop.record(ind(1.0, 3.0))
    pop.record(ind(1.0, 1.0)) // dominated by all three -> dropped on restart

    pop.restartKeepingFront()

    assertEquals(3, pop.size, "only the rank-0 front survives")
    val kept = pop.all().map { it.objectives.toList() }
    assertTrue(kept.containsAll(listOf(listOf(3.0, 1.0), listOf(2.0, 2.0), listOf(1.0, 3.0))))
    assertTrue(kept.none { it == listOf(1.0, 1.0) }, "the dominated member is flushed")
  }

  @Test
  fun restartOnSingleObjectiveKeepsOnlyTheMaximum() {
    // 1-D dominance is numeric >, so the front is just the unique maximum.
    val pop = Population(maxSize = 10)
    pop.record(ind(1.0))
    pop.record(ind(5.0))
    pop.record(ind(3.0))
    pop.restartKeepingFront()
    assertEquals(1, pop.size)
    assertEquals(5.0, pop.all().single().objectives[0])
  }

  @Test
  fun restartOnEmptyIsNoOp() {
    val pop = Population(maxSize = 10)
    pop.restartKeepingFront()
    assertTrue(pop.isEmpty())
  }

  @Test
  fun evictionStillRespectsMaxSize() {
    val pop = Population(maxSize = 3)
    repeat(10) { pop.record(ind(it.toDouble())) }
    assertEquals(3, pop.size, "archive truncated to maxSize")
  }
}
