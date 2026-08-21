package org.pastalab.fray.core.scheduler.qlearning

import kotlin.test.Test
import kotlin.test.assertEquals
import org.pastalab.fray.core.randomness.ControlledRandom

/**
 * Determinism guarantee: exactly one `nextDouble` (epsilon coin) then one `nextInt` (explore index)
 * are drawn per call unconditionally, regardless of branch, so the recorded `ControlledRandom` stream
 * can't desync between record and replay.
 */
class ExplorationPolicyTest {

  private fun seeded(ints: List<Int>, doubles: List<Double>) =
      ControlledRandom(integers = ints.toMutableList(), doubles = doubles.toMutableList())

  @Test
  fun epsilonZeroAlwaysExploitsArgmax() {
    val t = QTable(QLearningConfig(alpha = 1.0, gamma = 1.0, optimisticInit = 0.0))
    t.batchUpdate(listOf(1L to 20L), terminalReward = 5.0)
    // explore index 0 is seeded but must be ignored on the exploit branch.
    val rand = seeded(ints = listOf(0), doubles = listOf(0.9))
    val idx = EpsilonGreedy.selectIndex(1L, listOf(10L, 20L, 30L), t, epsilon = 0.0, rand)
    assertEquals(1, idx, "eps=0 => argmax (index 1)")
  }

  @Test
  fun epsilonOneAlwaysExplores() {
    val t = QTable(QLearningConfig(optimisticInit = 1.0))
    val rand = seeded(ints = listOf(8), doubles = listOf(0.0))
    val idx = EpsilonGreedy.selectIndex(1L, listOf(10L, 20L, 30L), t, epsilon = 1.0, rand)
    assertEquals(2, idx, "eps=1 => explore index 8 % 3 == 2")
  }

  @Test
  fun drawsExactlyOneDoubleAndOneIntOnExploitBranch() {
    val t = QTable(QLearningConfig(optimisticInit = 1.0))
    // ControlledRandom appends fresh values rather than throwing on over-draw, so we assert exact
    // consumption by checking the follow-up draws return the SECOND seeded values.
    val rand = seeded(ints = listOf(0, 999), doubles = listOf(0.9, 0.123))
    EpsilonGreedy.selectIndex(1L, listOf(10L, 20L), t, epsilon = 0.0, rand)
    assertEquals(0.123, rand.nextDouble(), "exactly one double consumed by selectIndex")
    assertEquals(999, rand.nextInt(), "exactly one int consumed by selectIndex")
  }

  @Test
  fun drawsExactlyOneDoubleAndOneIntOnExploreBranch() {
    val t = QTable(QLearningConfig(optimisticInit = 1.0))
    val rand = seeded(ints = listOf(1, 555), doubles = listOf(0.0, 0.777))
    EpsilonGreedy.selectIndex(1L, listOf(10L, 20L), t, epsilon = 1.0, rand)
    assertEquals(0.777, rand.nextDouble(), "exactly one double consumed on the explore branch too")
    assertEquals(555, rand.nextInt(), "exactly one int consumed on the explore branch too")
  }
}
