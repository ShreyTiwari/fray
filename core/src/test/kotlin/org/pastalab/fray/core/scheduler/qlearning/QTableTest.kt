package org.pastalab.fray.core.scheduler.qlearning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QTableTest {

  private fun config(
      alpha: Double = 0.5,
      gamma: Double = 1.0,
      optimisticInit: Double = 1.0,
      tableCap: Int = 100,
  ) =
      QLearningConfig(
          alpha = alpha, gamma = gamma, optimisticInit = optimisticInit, tableCap = tableCap)

  @Test
  fun unseenPairReadsOptimisticInit() {
    val t = QTable(config(optimisticInit = 7.0))
    assertEquals(7.0, t.q(state = 1L, action = 2L))
    assertEquals(0, t.size, "a plain read must not create a row")
  }

  @Test
  fun batchUpdateWithGammaOneMovesTowardTerminalReward() {
    // gamma=1 => every G_t == R; single step: Q = 0 + alpha*(R-0).
    val t = QTable(config(alpha = 0.5, gamma = 1.0, optimisticInit = 0.0))
    t.batchUpdate(listOf(1L to 2L), terminalReward = 10.0)
    assertEquals(5.0, t.q(1L, 2L), 1e-9)
  }

  @Test
  fun batchUpdateDiscountsEarlierStepsWithGamma() {
    // alpha=1 => Q becomes exactly G_t = R * gamma^(n-1-t).
    val t = QTable(config(alpha = 1.0, gamma = 0.5, optimisticInit = 0.0))
    t.batchUpdate(listOf(1L to 10L, 2L to 20L, 3L to 30L), terminalReward = 8.0)
    assertEquals(2.0, t.q(1L, 10L), 1e-9)
    assertEquals(4.0, t.q(2L, 20L), 1e-9)
    assertEquals(8.0, t.q(3L, 30L), 1e-9)
  }

  @Test
  fun bestActionIndexPicksHighestQ() {
    val t = QTable(config(alpha = 1.0, gamma = 1.0, optimisticInit = 0.0))
    t.batchUpdate(listOf(1L to 20L), terminalReward = 5.0)
    val chosen = t.bestActionIndex(state = 1L, candidateActions = listOf(10L, 20L, 30L))
    assertEquals(1, chosen, "index of the max-Q action (key 20)")
  }

  @Test
  fun bestActionIndexTieBreaksToSmallestKey() {
    // Unseen actions share the optimistic Q => tie broken to the smallest key.
    val t = QTable(config(optimisticInit = 1.0))
    val chosen = t.bestActionIndex(state = 99L, candidateActions = listOf(30L, 10L, 20L))
    assertEquals(1, chosen, "smallest key (10L) wins the tie; it is at index 1")
  }

  @Test
  fun bestActionIndexIsDeterministicAcrossCalls() {
    val t = QTable(config())
    val a = t.bestActionIndex(5L, listOf(7L, 3L, 9L))
    val b = t.bestActionIndex(5L, listOf(7L, 3L, 9L))
    assertEquals(a, b, "argmax + tie-break must be deterministic")
  }

  @Test
  fun boundedEvictionDropsLeastRecentlyUsedRow() {
    val t = QTable(config(alpha = 1.0, gamma = 1.0, optimisticInit = 0.0, tableCap = 2))
    t.batchUpdate(listOf(1L to 100L), terminalReward = 5.0)
    t.batchUpdate(listOf(2L to 100L), terminalReward = 5.0)
    t.batchUpdate(listOf(3L to 100L), terminalReward = 5.0)
    assertEquals(2, t.size, "capacity is enforced")
    assertEquals(0.0, t.q(1L, 100L), 1e-9, "evicted row falls back to optimistic init")
    assertEquals(5.0, t.q(2L, 100L), 1e-9, "state 2 retained")
    assertEquals(5.0, t.q(3L, 100L), 1e-9, "state 3 retained")
  }

  @Test
  fun recentlyUsedRowSurvivesEviction() {
    // Re-touching state 1 makes it MRU, so state 2 becomes the LRU victim.
    val t = QTable(config(alpha = 1.0, gamma = 1.0, optimisticInit = 0.0, tableCap = 2))
    t.batchUpdate(listOf(1L to 100L), terminalReward = 5.0)
    t.batchUpdate(listOf(2L to 100L), terminalReward = 5.0)
    t.batchUpdate(listOf(1L to 100L), terminalReward = 6.0)
    t.batchUpdate(listOf(3L to 100L), terminalReward = 5.0)
    assertEquals(2, t.size)
    assertTrue(t.q(1L, 100L) > 0.0, "recently-used state 1 survives")
    assertEquals(0.0, t.q(2L, 100L), 1e-9, "state 2 is the LRU victim")
  }

  @Test
  fun emptyTrajectoryIsNoOp() {
    val t = QTable(config())
    t.batchUpdate(emptyList(), terminalReward = 100.0)
    assertEquals(0, t.size)
  }
}
