package org.pastalab.fray.core.scheduler.qlearning

/**
 * Tuning knobs for the Q-learning scheduler. A plain (non-serializable) data class carried across
 * iterations by hand: keeping it out of `schedule.json` is what guarantees a recorded trace replays
 * the same interleaving regardless of how the learner that produced it was tuned.
 */
data class QLearningConfig(
    val alpha: Double = DEFAULT_ALPHA,
    val gamma: Double = DEFAULT_GAMMA,
    val eps0: Double = DEFAULT_EPS0,
    val epsDecay: Double = DEFAULT_EPS_DECAY,
    val epsMin: Double = DEFAULT_EPS_MIN,
    /**
     * Optimistic initial Q-value for an unseen `(state, action)`: set above realistic returns so the
     * greedy policy tries unseen actions first, driving exploration before epsilon even fires.
     */
    val optimisticInit: Double = DEFAULT_OPTIMISTIC_INIT,
    val tableCap: Int = DEFAULT_TABLE_CAP,
    /** Backup horizon; `0` = Monte-Carlo (whole-episode terminal credit). */
    val nStep: Int = DEFAULT_N_STEP,
    /**
     * How the abstract state key is computed. One of [KNOWN_MODES], or [MODE_AUTO] which the wiring
     * resolves to `observedState` when a `state` feedback source is active, else `candidateSet`.
     */
    val stateMode: String = DEFAULT_STATE_MODE,
    /** Fully-qualified class name of a user [StateAbstraction] for [MODE_CUSTOM]. */
    val stateClass: String = DEFAULT_STATE_CLASS,
    /** Reserved: recent-decision context length for a Markov window; 0 = off. */
    val windowK: Int = DEFAULT_WINDOW_K,
    val rewardBug: Double = DEFAULT_REWARD_BUG,
    val rewardNovelty: Double = DEFAULT_REWARD_NOVELTY,
    val rewardFitness: Double = DEFAULT_REWARD_FITNESS,
    val rewardMax: Double = DEFAULT_REWARD_MAX,
) {
  /** Clamp every field to a usable range so a misconfiguration degrades instead of failing the run. */
  fun sanitized(): QLearningConfig =
      QLearningConfig(
          alpha = alpha.coerceIn(0.0, 1.0),
          gamma = gamma.coerceIn(0.0, 1.0),
          eps0 = eps0.coerceIn(0.0, 1.0),
          epsDecay = epsDecay.coerceIn(0.0, 1.0),
          epsMin = epsMin.coerceIn(0.0, 1.0),
          optimisticInit = optimisticInit,
          tableCap = tableCap.coerceAtLeast(1),
          nStep = nStep.coerceAtLeast(0),
          // MODE_AUTO stays as-is (the wiring resolves it); any other unknown value falls back to
          // candidateSet.
          stateMode =
              if (stateMode in KNOWN_MODES || stateMode == MODE_AUTO) stateMode
              else MODE_CANDIDATE_SET,
          stateClass = stateClass,
          windowK = windowK.coerceAtLeast(0),
          rewardBug = rewardBug,
          rewardNovelty = rewardNovelty,
          rewardFitness = rewardFitness,
          rewardMax = rewardMax.coerceAtLeast(MIN_REWARD_MAX),
      )

  companion object {
    const val DEFAULT_ALPHA = 0.1
    const val DEFAULT_GAMMA = 0.99
    const val DEFAULT_EPS0 = 0.3
    const val DEFAULT_EPS_DECAY = 0.995
    const val DEFAULT_EPS_MIN = 0.05
    const val DEFAULT_OPTIMISTIC_INIT = 1.0
    const val DEFAULT_TABLE_CAP = 100_000
    const val DEFAULT_N_STEP = 0
    const val DEFAULT_WINDOW_K = 0

    // Reward weights: bug dominates novelty dominates fitness. Independent literals: tuning one does
    // not move the others.
    const val DEFAULT_REWARD_BUG = 100.0
    const val DEFAULT_REWARD_NOVELTY = 1.0
    const val DEFAULT_REWARD_FITNESS = 0.1
    const val DEFAULT_REWARD_MAX = 1000.0
    const val MIN_REWARD_MAX = 1e-6

    const val MODE_CANDIDATE_SET = "candidateSet"
    const val MODE_OBSERVED_STATE = "observedState"
    const val MODE_LOCATION = "location"
    const val MODE_CUSTOM = "custom"

    /** The empty string means "auto-select" -- resolved by the wiring. */
    const val MODE_AUTO = ""

    val KNOWN_MODES = setOf(MODE_CANDIDATE_SET, MODE_OBSERVED_STATE, MODE_LOCATION, MODE_CUSTOM)

    const val DEFAULT_STATE_MODE = MODE_AUTO
    const val DEFAULT_STATE_CLASS = ""

    val DEFAULT = QLearningConfig()

    /**
     * Build a config from `fray.qlearning.*` system properties (then [sanitized]). This is the JUnit
     * delivery channel: the scheduler is constructed reflectively with a no-arg ctor there, so CLI
     * flags can't reach it.
     */
    fun fromSystemProperties(): QLearningConfig {
      fun intProp(key: String, default: Int) = System.getProperty(key)?.toIntOrNull() ?: default
      fun doubleProp(key: String, default: Double) =
          System.getProperty(key)?.toDoubleOrNull() ?: default
      fun strProp(key: String, default: String) =
          System.getProperty(key)?.takeIf { it.isNotBlank() } ?: default
      return QLearningConfig(
              alpha = doubleProp("fray.qlearning.alpha", DEFAULT_ALPHA),
              gamma = doubleProp("fray.qlearning.gamma", DEFAULT_GAMMA),
              eps0 = doubleProp("fray.qlearning.eps0", DEFAULT_EPS0),
              epsDecay = doubleProp("fray.qlearning.epsDecay", DEFAULT_EPS_DECAY),
              epsMin = doubleProp("fray.qlearning.epsMin", DEFAULT_EPS_MIN),
              optimisticInit = doubleProp("fray.qlearning.optimisticInit", DEFAULT_OPTIMISTIC_INIT),
              tableCap = intProp("fray.qlearning.tableCap", DEFAULT_TABLE_CAP),
              nStep = intProp("fray.qlearning.nStep", DEFAULT_N_STEP),
              stateMode = strProp("fray.qlearning.stateMode", DEFAULT_STATE_MODE),
              stateClass = strProp("fray.qlearning.stateClass", DEFAULT_STATE_CLASS),
              windowK = intProp("fray.qlearning.windowK", DEFAULT_WINDOW_K),
              rewardBug = doubleProp("fray.qlearning.rewardBug", DEFAULT_REWARD_BUG),
              rewardNovelty = doubleProp("fray.qlearning.rewardNovelty", DEFAULT_REWARD_NOVELTY),
              rewardFitness = doubleProp("fray.qlearning.rewardFitness", DEFAULT_REWARD_FITNESS),
              rewardMax = doubleProp("fray.qlearning.rewardMax", DEFAULT_REWARD_MAX),
          )
          .sanitized()
    }
  }
}
