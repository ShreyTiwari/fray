package org.pastalab.fray.core.scheduler.genetic

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.pastalab.fray.core.scheduler.GeneticScheduler
import org.pastalab.fray.core.scheduler.Scheduler

/**
 * Only the genome is persisted to `schedule.json`; [GeneticConfig], population, and RNG are
 * `@Transient` search state, so a recorded schedule replays identically regardless of search tuning.
 * Serializes via the default [Json] as the production save path does.
 */
class GeneticSchedulerSerializationTest {

  @Test
  fun scheduleJsonCarriesOnlyTheGenome() {
    val scheduler: Scheduler = GeneticScheduler(mutableListOf(4, 8, 15, 16, 23, 42))
    val json = Json.encodeToString(scheduler)

    assertTrue(json.contains("genome"), "serialized form should contain the genome: $json")
    assertTrue(json.contains("42"), "genome values should be serialized: $json")
    assertFalse(json.contains("config"), "config must be @Transient, not serialized: $json")
    assertFalse(json.contains("tournamentSize"), "no tuning knobs in schedule.json: $json")
    assertFalse(json.contains("population"), "population must be @Transient: $json")
    assertFalse(json.contains("decisionIndex"), "decisionIndex must be @Transient: $json")
  }

  @Test
  fun roundTripPreservesGenomeAndResetsConfigToDefault() {
    // A non-default config must not survive the round-trip: replay must not depend on tuning.
    val original = GeneticScheduler(mutableListOf(1, 2, 3))
    original.config = GeneticConfig(tournamentSize = 9, populationCap = 7)

    val json = Json.encodeToString<Scheduler>(original)
    val restored = Json.decodeFromString<Scheduler>(json) as GeneticScheduler

    assertEquals(listOf(1, 2, 3), restored.genome, "genome round-trips exactly")
    assertEquals(GeneticConfig.DEFAULT, restored.config, "config resets to DEFAULT on deserialize")
  }

  @Test
  fun noArgConstructorUsesDefaultConfigWhenNoSystemProperties() {
    // The reflective JUnit path: no-arg ctor with no fray.genetic.* properties set.
    val scheduler = GeneticScheduler()
    assertEquals(GeneticConfig.DEFAULT, scheduler.config)
    assertEquals(GeneticConfig.DEFAULT_POPULATION_CAP, scheduler.population.maxSize)
  }
}
