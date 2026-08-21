package org.pastalab.fray.core.scheduler.qlearning

import org.pastalab.fray.core.feedback.IterationFeedback

/**
 * Turns one iteration's [IterationFeedback] into the scalar terminal reward for the Q-learning
 * backup. Pure consumer of [IterationFeedback] (scheduler/ imports feedback/, never the reverse).
 *
 * Novelty sums [IterationFeedback.objectiveVector] rather than `newFeatures.size` because the
 * objectives are bounded and saturating, keeping Bellman targets well-conditioned. `EMPTY` feedback
 * still credits a bug but has 0 novelty/fitness, so a feedback-less run learns nothing beyond bug
 * reinforcement.
 */
class RewardModel(private val config: QLearningConfig) {
  fun reward(feedback: IterationFeedback): Double {
    val bugTerm = if (feedback.result.bugFound != null) 1.0 else 0.0
    val noveltyTerm = feedback.objectiveVector().sum()
    val fitnessTerm = feedback.fitness
    val r =
        config.rewardBug * bugTerm +
            config.rewardNovelty * noveltyTerm +
            config.rewardFitness * fitnessTerm
    return r.coerceIn(0.0, config.rewardMax)
  }
}
