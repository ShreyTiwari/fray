package org.pastalab.fray.core.scheduler

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.feedback.IterationFeedback
import org.pastalab.fray.core.randomness.ControlledRandom
import org.pastalab.fray.core.randomness.Randomness
import org.pastalab.fray.core.scheduler.qlearning.EpsilonGreedy
import org.pastalab.fray.core.scheduler.qlearning.ExplorationPolicy
import org.pastalab.fray.core.scheduler.qlearning.LastObservedStateHolder
import org.pastalab.fray.core.scheduler.qlearning.QLearningConfig
import org.pastalab.fray.core.scheduler.qlearning.QTable
import org.pastalab.fray.core.scheduler.qlearning.RewardModel
import org.pastalab.fray.core.scheduler.qlearning.StateAbstraction

/**
 * Grey-box, coverage-guided tabular Q-learning scheduler, structured like [GeneticScheduler]: ACTS in
 * [scheduleNextOperation], LEARNS in [nextIteration]. The only serialized state is the positional
 * decision [trace]; all learned state is `@Transient` and carried forward by hand across iterations.
 * On replay [trace] is decoded positionally -- table-free, RNG-free -- so a bug-triggering iteration
 * in `schedule.json` reproduces deterministically.
 */
@Serializable
class QLearningScheduler(
    val trace: MutableList<Int> = mutableListOf(),
    // The runtime-facing stream backing [Scheduler.nextInt]/[Scheduler.nextDouble]. Transient
    // because replay reads the persisted [trace].
    @Transient private val seedRand: Randomness = ControlledRandom(),
) : Scheduler(seedRand) {
  // Explicit no-arg ctor: the JUnit reflective path uses getConstructor().newInstance(), and a Kotlin
  // default parameter does NOT produce a callable zero-arg constructor. System properties are that
  // path's only tuning channel; with none set this is exactly QLearningConfig.DEFAULT.
  constructor() : this(mutableListOf()) {
    config = QLearningConfig.fromSystemProperties()
    qTable = QTable(config)
    abstraction = buildAbstraction()
  }

  // Single-argument constructor used to reconstruct the scheduler for replay.
  constructor(rand: Randomness) : this(mutableListOf(), rand)

  // Scheduler-private stream for policy draws, kept distinct from the inherited [Scheduler.rand]:
  // that one is drained by the runtime (spurious wakeups, signal targets), and replay decodes [trace]
  // positionally without drawing, so sharing it would shift the runtime's draw positions between
  // record and replay. Replay never consults this stream.
  @Transient var policyRand: Randomness = ControlledRandom()

  @Transient var config: QLearningConfig = QLearningConfig.DEFAULT

  @Transient var qTable: QTable = QTable(config)

  // Pluggable state projection. On replay it resets to candidateSet and is never consulted.
  @Transient var abstraction: StateAbstraction = StateAbstraction.candidateSet()

  // Bridge to the observeState holder on the long-lived Configuration. Injected Configuration-side
  // (not captured at construction: the scheduler predates Configuration). Read only by `observedState`
  // mode; null under replay / plain unit construction, which the abstraction tolerates (reads 0).
  @Transient var observedStateHolder: LastObservedStateHolder? = null

  @Transient var policy: ExplorationPolicy = EpsilonGreedy

  @Transient var epsilon: Double = QLearningConfig.DEFAULT_EPS0

  // Episodes this scheduler lineage has completed; drives the epsilon schedule.
  @Transient var episode: Int = 0

  @Transient private var decisionIndex = 0

  // (stateKey, positional-actionKey) per choice point; appended in scheduleNextOperation, drained by
  // the batch backup in nextIteration.
  @Transient val trajectory = mutableListOf<Pair<Long, Long>>()

  override fun scheduleNextOperation(
      threads: List<ThreadContext>,
      allThreads: Collection<ThreadContext>,
  ): ThreadContext {
    // Forced step: no choice, don't consume a trace slot. MUST be byte-identical in record and replay
    // and come BEFORE any trace read/write, or the positional index desyncs.
    if (threads.size == 1) {
      return threads[0]
    }

    val index: Int
    if (decisionIndex >= trace.size) {
      // The action is the positional index into the candidate list -- always available, always
      // learnable, and exactly what we serialize. (stackTraceHash is only a STATE ingredient in
      // `location` mode, never the action: senders blocking on the same lock line are indistinguishable.)
      val stateKey = abstraction.stateKey(threads)
      val candidateActions = List(threads.size) { it.toLong() }
      index = policy.selectIndex(stateKey, candidateActions, qTable, epsilon, policyRand)
      trajectory.add(stateKey to index.toLong())
      trace.add(index)
    } else {
      // Replay: pure positional decode. No table, no RNG.
      index = trace[decisionIndex] % threads.size
    }
    decisionIndex++
    return threads[index % threads.size]
  }

  // Feedback-free entry point required by [Scheduler]; with no feedback the reward is 0, so the
  // table stops learning and the policy just decays epsilon.
  override fun nextIteration(randomness: Randomness): Scheduler =
      nextIteration(randomness, IterationFeedback.EMPTY)

  override fun nextIteration(randomness: Randomness, feedback: IterationFeedback): Scheduler {
    // Monte-Carlo batch backup of this episode's trajectory against the terminal reward.
    val reward = RewardModel(config).reward(feedback)
    qTable.batchUpdate(trajectory, reward)

    // Geometric epsilon decay, floored at epsMin.
    val nextEpisode = episode + 1
    val nextEpsilon =
        (config.eps0 * Math.pow(config.epsDecay, nextEpisode.toDouble()))
            .coerceAtLeast(config.epsMin)

    if (FEEDBACK_DEBUG) {
      println(
          "[qlearning] episode=$episode reward=$reward objectives=${feedback.objectives} " +
              "fitness=${feedback.fitness} novel=${feedback.isNovel} " +
              "trajectory=${trajectory.size} qStates=${qTable.size} " +
              "epsilon=$epsilon -> $nextEpsilon traceLen=${trace.size}")
    }

    // Fresh instance with an EMPTY trace (next episode re-explores and extends its own), carrying the
    // learned/transient state forward by hand. observedStateHolder is re-injected Configuration-side.
    return QLearningScheduler(mutableListOf(), randomness).also {
      it.config = config
      it.qTable = qTable
      it.abstraction = abstraction
      it.observedStateHolder = observedStateHolder
      it.policy = policy
      // Carry the private policy stream forward so exploration continues rather than restarting.
      it.policyRand = policyRand
      it.epsilon = nextEpsilon
      it.episode = nextEpisode
    }
  }

  private fun buildAbstraction(): StateAbstraction =
      StateAbstraction.forMode(config.stateMode, config.stateClass) {
        observedStateHolder?.value ?: 0L
      }

  /** (Re)build the abstraction after [config] and [observedStateHolder] are set by the driver. */
  fun refreshAbstraction() {
    abstraction = buildAbstraction()
  }

  companion object {
    private val FEEDBACK_DEBUG = System.getProperty("fray.feedback.debug", "false").toBoolean()
  }
}
