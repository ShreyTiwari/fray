package org.pastalab.fray.core.scheduler.qlearning

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.pastalab.fray.core.feedback.IterationFeedback
import org.pastalab.fray.core.feedback.IterationResult

/** [RewardModel] reward is a pure function of [IterationFeedback]; never reads scheduler state. */
class RewardModelTest {

  private fun feedback(
      bug: Throwable? = null,
      objectives: Map<String, Double> = emptyMap(),
      fitness: Double = 0.0,
      newFeatures: Set<Long> = emptySet(),
  ) =
      IterationFeedback(
          result = IterationResult(iteration = 1, bugFound = bug, reportFolder = null),
          signals = emptyList(),
          newFeatures = newFeatures,
          fitness = fitness,
          objectives = objectives,
      )

  @Test
  fun emptyFeedbackYieldsBaselineZero() {
    val r = RewardModel(QLearningConfig.DEFAULT).reward(IterationFeedback.EMPTY)
    assertEquals(0.0, r, "no bug, no novelty, no fitness => baseline 0")
  }

  @Test
  fun bugDominates() {
    val model = RewardModel(QLearningConfig.DEFAULT)
    val withBug = model.reward(feedback(bug = RuntimeException("boom")))
    val withoutBug = model.reward(feedback(objectives = mapOf("state" to 3.0)))
    assertTrue(withBug > withoutBug, "a bug outweighs mere novelty")
    assertEquals(QLearningConfig.DEFAULT_REWARD_BUG, withBug, "bug term = rewardBug * 1")
  }

  @Test
  fun noveltyUsesObjectiveVectorSum() {
    val cfg = QLearningConfig(rewardBug = 0.0, rewardNovelty = 2.0, rewardFitness = 0.0)
    val r = RewardModel(cfg).reward(feedback(objectives = mapOf("a" to 1.5, "b" to 2.5)))
    assertEquals(2.0 * (1.5 + 2.5), r, 1e-9, "novelty term = rewardNovelty * sum(objectives)")
  }

  @Test
  fun fitnessContributes() {
    val cfg = QLearningConfig(rewardBug = 0.0, rewardNovelty = 0.0, rewardFitness = 0.5)
    val r = RewardModel(cfg).reward(feedback(fitness = 10.0))
    assertEquals(0.5 * 10.0, r, 1e-9)
  }

  @Test
  fun rewardIsClippedToMax() {
    val cfg = QLearningConfig(rewardBug = 1e9, rewardMax = 42.0)
    val r = RewardModel(cfg).reward(feedback(bug = RuntimeException()))
    assertEquals(42.0, r, "reward clipped at rewardMax")
  }

  @Test
  fun rewardNeverNegative() {
    val cfg = QLearningConfig(rewardBug = 0.0, rewardNovelty = 0.0, rewardFitness = 1.0)
    val r = RewardModel(cfg).reward(feedback(fitness = -100.0))
    assertEquals(0.0, r, "negative contributions clip at 0")
  }
}
