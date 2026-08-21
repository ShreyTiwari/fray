package org.pastalab.fray.core.scheduler.genetic

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Load-bearing guarantee: [GeneticConfig.DEFAULT] equals the original hard-coded constants, so an
 * unconfigured run is unchanged.
 */
class GeneticConfigTest {

  private val keys =
      listOf(
          "fray.genetic.tournamentSize",
          "fray.genetic.mutationRate",
          "fray.genetic.genomeLength",
          "fray.genetic.geneBound",
          "fray.genetic.seedPopulation",
          "fray.genetic.populationCap",
          "fray.genetic.genome",
          "fray.genetic.crossover",
      )

  @AfterTest
  fun clearProps() {
    keys.forEach { System.clearProperty(it) }
  }

  @Test
  fun defaultMatchesOriginalConstants() {
    val d = GeneticConfig.DEFAULT
    assertEquals(3, d.tournamentSize)
    assertEquals(0.1, d.mutationRate)
    assertEquals(256, d.genomeLength)
    assertEquals(1 shl 16, d.geneBound)
    assertEquals(5, d.seedPopulation)
    assertEquals(50, d.populationCap)
    assertEquals(50, d.restartStagnation, "C4: restart is ON by default")
    assertFalse(d.priorityGenome, "positional genome is the default")
    assertFalse(d.posGenome, "seeded-POS genome is opt-in")
    assertFalse(d.uniformCrossover, "single-point crossover is the default")
  }

  @Test
  fun genomeAndCrossoverPropertiesSelectMode() {
    // genome is a three-way switch on one property; exactly one of the two flags is set (or neither).
    System.setProperty("fray.genetic.genome", "pos")
    GeneticConfig.fromSystemProperties().let {
      assertTrue(it.posGenome, "genome=pos enables the seeded-POS genome")
      assertFalse(it.priorityGenome)
    }
    System.setProperty("fray.genetic.genome", "priority")
    GeneticConfig.fromSystemProperties().let {
      assertTrue(it.priorityGenome, "genome=priority enables the priority genome")
      assertFalse(it.posGenome)
    }
    System.setProperty("fray.genetic.genome", "positional")
    GeneticConfig.fromSystemProperties().let {
      assertFalse(it.posGenome, "any other value is the positional default")
      assertFalse(it.priorityGenome)
    }
    System.setProperty("fray.genetic.crossover", "uniform")
    assertTrue(GeneticConfig.fromSystemProperties().uniformCrossover, "crossover=uniform enables it")
  }

  @Test
  fun restartStagnationClampsNegativeToZeroDisabled() {
    // 0 means "restart disabled" -- must survive sanitize as 0.
    assertEquals(0, GeneticConfig(restartStagnation = 0).sanitized().restartStagnation)
    assertEquals(0, GeneticConfig(restartStagnation = -5).sanitized().restartStagnation)
    assertEquals(20, GeneticConfig(restartStagnation = 20).sanitized().restartStagnation)
  }

  @Test
  fun sanitizedClampsToUsableRanges() {
    val bad =
        GeneticConfig(
            tournamentSize = 0,
            mutationRate = 5.0,
            genomeLength = -10,
            geneBound = 1,
            seedPopulation = 0,
            populationCap = -3,
        )
    val s = bad.sanitized()
    assertEquals(1, s.tournamentSize, "tournamentSize floored at 1")
    assertEquals(1.0, s.mutationRate, "mutationRate clamped to [0,1]")
    assertEquals(1, s.genomeLength, "genomeLength floored at 1")
    assertEquals(2, s.geneBound, "geneBound floored at 2")
    assertEquals(1, s.seedPopulation, "seedPopulation floored at 1")
    assertEquals(1, s.populationCap, "populationCap floored at 1")
  }

  @Test
  fun negativeMutationRateClampsToZero() {
    assertEquals(0.0, GeneticConfig(mutationRate = -0.5).sanitized().mutationRate)
  }

  @Test
  fun fromSystemPropertiesWithNoneSetIsDefault() {
    assertEquals(GeneticConfig.DEFAULT, GeneticConfig.fromSystemProperties())
  }

  @Test
  fun fromSystemPropertiesReadsAndClamps() {
    System.setProperty("fray.genetic.tournamentSize", "7")
    System.setProperty("fray.genetic.mutationRate", "0.25")
    System.setProperty("fray.genetic.populationCap", "0")
    val c = GeneticConfig.fromSystemProperties()
    assertEquals(7, c.tournamentSize)
    assertEquals(0.25, c.mutationRate)
    assertEquals(1, c.populationCap, "populationCap=0 clamps to 1")
    assertEquals(256, c.genomeLength)
  }

  @Test
  fun fromSystemPropertiesIgnoresUnparseableValues() {
    System.setProperty("fray.genetic.tournamentSize", "not-a-number")
    System.setProperty("fray.genetic.mutationRate", "")
    val c = GeneticConfig.fromSystemProperties()
    assertEquals(3, c.tournamentSize, "garbage int falls back to default")
    assertEquals(0.1, c.mutationRate, "garbage double falls back to default")
  }

  @Test
  fun defaultIsAlreadySanitized() {
    assertEquals(GeneticConfig.DEFAULT, GeneticConfig.DEFAULT.sanitized())
    assertTrue(GeneticConfig.DEFAULT.geneBound >= 2)
  }
}
