package org.pastalab.fray.core.scheduler.qlearning

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.pastalab.fray.core.RunContext
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.concurrency.operations.Operation
import org.pastalab.fray.core.concurrency.operations.ThreadStartOperation
import org.pastalab.fray.core.randomness.ControlledRandom
import org.pastalab.fray.core.scheduler.QLearningScheduler
import org.pastalab.fray.core.scheduler.Scheduler

/**
 * A schedule recorded by the Q-learning act path must replay bit-identically from the positional
 * `trace` ALONE -- no Q-table, no RNG, no `observeState` bridge -- and `observedState` mode must
 * never leak into replay.
 */
class QLearningDeterminismTest {

  private val context = QlTestSupport.newContext()

  private fun candidateList(vararg ops: Operation): List<ThreadContext> =
      ops.mapIndexed { i, op -> QlTestSupport.threadWith(context, i, op) }

  private fun driveAndCollectChoices(
      scheduler: Scheduler,
      steps: List<List<ThreadContext>>,
  ): List<Int> {
    val chosen = mutableListOf<Int>()
    for (candidates in steps) {
      val picked = scheduler.scheduleNextOperation(candidates, candidates)
      chosen.add(candidates.indexOf(picked))
    }
    return chosen
  }

  @Test
  fun recordedTraceReplaysIdenticallyWithNoTable() {
    // candidateSet mode so no stackTraceHash resolution is needed.
    val recorder = QLearningScheduler()
    recorder.policyRand = ControlledRandom()
    recorder.config = QLearningConfig(stateMode = QLearningConfig.MODE_CANDIDATE_SET)
    recorder.qTable = QTable(recorder.config)
    recorder.refreshAbstraction()

    val steps =
        listOf(
            candidateList(QlTestSupport.memOp(1), QlTestSupport.memOp(2)),
            candidateList(QlTestSupport.memOp(3)), // forced: size==1, no trace slot
            candidateList(QlTestSupport.memOp(4), QlTestSupport.memOp(5), QlTestSupport.memOp(6)),
            candidateList(QlTestSupport.memOp(7), QlTestSupport.memOp(8)),
        )
    val recordedChoices = driveAndCollectChoices(recorder, steps)

    val json = Json.encodeToString<Scheduler>(recorder)
    val replay = Json.decodeFromString<Scheduler>(json) as QLearningScheduler
    // Deliberately no table / rand / abstraction: replay must not need them.

    val replayChoices = driveAndCollectChoices(replay, steps)
    assertEquals(recordedChoices, replayChoices, "replay reproduces the recorded interleaving")

    val json2 = Json.encodeToString<Scheduler>(replay)
    assertEquals(json, json2, "schedule.json is identical after replay")
  }

  @Test
  fun forcedStepsConsumeNoTraceSlot() {
    val recorder = QLearningScheduler()
    recorder.policyRand = ControlledRandom()
    recorder.refreshAbstraction()
    val steps =
        listOf(
            candidateList(QlTestSupport.memOp(1)), // forced
            candidateList(QlTestSupport.memOp(2), QlTestSupport.memOp(3)), // genuine -> 1 slot
            candidateList(QlTestSupport.memOp(4)), // forced
            candidateList(QlTestSupport.memOp(5), QlTestSupport.memOp(6)), // genuine -> 1 slot
        )
    driveAndCollectChoices(recorder, steps)
    assertEquals(2, recorder.trace.size, "only the two genuine choice points extend the trace")
  }

  @Test
  fun observedStateBridgeDoesNotAffectReplay() {
    // Record under observedState mode with a changing holder; replay has no holder yet must
    // reproduce the interleaving from the positional trace.
    val holder = LastObservedStateHolder()
    val recorder = QLearningScheduler()
    recorder.policyRand = ControlledRandom()
    recorder.config = QLearningConfig(stateMode = QLearningConfig.MODE_OBSERVED_STATE)
    recorder.qTable = QTable(recorder.config)
    recorder.observedStateHolder = holder
    recorder.refreshAbstraction()

    val steps =
        listOf(
            candidateList(QlTestSupport.memOp(1), QlTestSupport.memOp(2)),
            candidateList(QlTestSupport.memOp(3), QlTestSupport.memOp(4)),
            candidateList(QlTestSupport.memOp(5), QlTestSupport.memOp(6)),
        )
    // Mutate the observed value between decisions to exercise the bridge during recording.
    val recordedChoices = mutableListOf<Int>()
    steps.forEachIndexed { i, candidates ->
      holder.value = (i * 1000 + 7).toLong()
      val picked = recorder.scheduleNextOperation(candidates, candidates)
      recordedChoices.add(candidates.indexOf(picked))
    }

    val json = Json.encodeToString<Scheduler>(recorder)
    val replay = Json.decodeFromString<Scheduler>(json) as QLearningScheduler
    val replayChoices = driveAndCollectChoices(replay, steps)
    assertEquals(
        recordedChoices, replayChoices, "positional replay ignores the observeState bridge entirely")
  }

  @Test
  fun stackTraceHashZeroStepsStillRecordAndReplay() {
    // Non-RacingOperation candidates (ThreadStartOperation) have stackTraceHash 0, so they are
    // excluded from learning but still occupy a positional trace slot.
    val recorder = QLearningScheduler()
    recorder.policyRand = ControlledRandom()
    recorder.refreshAbstraction()
    val steps =
        listOf(
            candidateList(ThreadStartOperation(0), ThreadStartOperation(0)),
            candidateList(QlTestSupport.memOp(1), QlTestSupport.memOp(2)),
        )
    val recordedChoices = driveAndCollectChoices(recorder, steps)
    assertTrue(recorder.trace.size == 2, "both genuine choice points recorded a trace slot")

    val json = Json.encodeToString<Scheduler>(recorder)
    val replay = Json.decodeFromString<Scheduler>(json) as QLearningScheduler
    assertEquals(recordedChoices, driveAndCollectChoices(replay, steps))
  }
}
