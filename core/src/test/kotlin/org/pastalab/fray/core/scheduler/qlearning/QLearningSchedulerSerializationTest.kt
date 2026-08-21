package org.pastalab.fray.core.scheduler.qlearning

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.pastalab.fray.core.scheduler.QLearningScheduler
import org.pastalab.fray.core.scheduler.Scheduler

/**
 * Only the positional `trace` is persisted to `schedule.json`; the Q-table, config, epsilon,
 * episode, and RNG are `@Transient`, so a recorded schedule replays deterministically.
 */
class QLearningSchedulerSerializationTest {

  @Test
  fun scheduleJsonCarriesOnlyTheTrace() {
    val scheduler: Scheduler = QLearningScheduler(mutableListOf(4, 8, 15, 16, 23, 42))
    val json = Json.encodeToString(scheduler)

    assertTrue(json.contains("trace"), "serialized form should contain the trace: $json")
    assertTrue(json.contains("42"), "trace values should be serialized: $json")
    assertFalse(json.contains("config"), "config must be @Transient: $json")
    assertFalse(json.contains("qTable"), "qTable must be @Transient: $json")
    assertFalse(json.contains("epsilon"), "epsilon must be @Transient: $json")
    assertFalse(json.contains("episode"), "episode must be @Transient: $json")
    assertFalse(json.contains("trajectory"), "trajectory must be @Transient: $json")
    assertFalse(json.contains("optimisticInit"), "no tuning knobs in schedule.json: $json")
  }

  @Test
  fun roundTripPreservesTraceAndResetsLearnedStateToDefault() {
    val original = QLearningScheduler(mutableListOf(1, 2, 3))
    // Make every learned/tuning field non-default before serializing.
    original.config = QLearningConfig(alpha = 0.42, tableCap = 7, optimisticInit = 99.0)
    original.qTable = QTable(original.config)
    original.qTable.batchUpdate(listOf(5L to 6L), terminalReward = 123.0)
    original.epsilon = 0.987
    original.episode = 55

    val json = Json.encodeToString<Scheduler>(original)
    val restored = Json.decodeFromString<Scheduler>(json) as QLearningScheduler

    assertEquals(listOf(1, 2, 3), restored.trace, "trace round-trips exactly")
    assertEquals(QLearningConfig.DEFAULT, restored.config, "config resets to DEFAULT on deserialize")
    assertEquals(0, restored.qTable.size, "learned table is empty after deserialize")
    // Unseen (s,a) on the never-trained replay instance reads default optimistic init.
    assertEquals(
        QLearningConfig.DEFAULT.optimisticInit,
        restored.qTable.q(5L, 6L),
        "restored table has no learned values")
  }

  @Test
  fun noArgConstructorUsesDefaultConfigWhenNoSystemProperties() {
    val scheduler = QLearningScheduler()
    assertEquals(QLearningConfig.DEFAULT, scheduler.config)
  }
}
