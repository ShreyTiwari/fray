package org.pastalab.fray.core.scheduler.genetic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.pastalab.fray.core.randomness.ControlledRandom

/**
 * Verifies the genetic operators honour their tuning params and that defaults reproduce the
 * previous fixed constants; a preseeded [ControlledRandom] makes the int/double streams deterministic.
 */
class OperatorsTest {

  private fun seededInts(vararg ints: Int) =
      ControlledRandom(integers = ints.toMutableList(), doubles = mutableListOf())

  private fun seededDoubles(vararg doubles: Double) =
      ControlledRandom(integers = mutableListOf(), doubles = doubles.toMutableList())

  private fun seeded(ints: List<Int>, doubles: List<Double>) =
      ControlledRandom(integers = ints.toMutableList(), doubles = doubles.toMutableList())

  @Test
  fun randomGenomeHonoursLengthAndBound() {
    val rand = seededInts(*IntArray(12) { it * 1000 })
    val genome = Operators.randomGenome(rand, length = 12, geneBound = 8)
    assertEquals(12, genome.size, "length param respected")
    assertTrue(genome.all { it in 0 until 8 }, "every gene reduced mod the bound: $genome")
  }

  @Test
  fun randomGenomeDefaultLengthIsTheOriginalConstant() {
    val genome = Operators.randomGenome(seededInts(1))
    assertEquals(GeneticConfig.DEFAULT_GENOME_LENGTH, genome.size)
  }

  @Test
  fun mutateRateZeroChangesNothing() {
    val genome = listOf(1, 2, 3, 4, 5)
    val out = Operators.mutate(genome, seededDoubles(0.5, 0.5, 0.5, 0.5, 0.5), mutationRate = 0.0, geneBound = 99)
    assertEquals(genome, out, "rate 0 => genome unchanged")
  }

  @Test
  fun mutateRateOneRerandomisesEveryGeneWithinBound() {
    // Each mutation draws nextDouble() (the rate test) then nextInt() for the new gene.
    val genome = listOf(1, 1, 1)
    val rand = seeded(ints = listOf(7, 7, 7), doubles = listOf(0.0, 0.0, 0.0))
    val out = Operators.mutate(genome, rand, mutationRate = 1.0, geneBound = 5)
    assertTrue(out.all { it in 0 until 5 }, "all genes within the new bound: $out")
    assertEquals(listOf(2, 2, 2), out, "7 % 5 == 2 for every gene")
  }

  @Test
  fun crossoverChildLengthIsMaxOfParents() {
    val a = listOf(9, 9, 9, 9)
    val b = listOf(0)
    val out = Operators.crossover(a, b, seededInts(0, 1, 2, 3), geneBound = 4)
    assertEquals(4, out.size, "child length is max(parent lengths)")
  }

  @Test
  fun selectWithTournamentSizeOneReturnsTheSingleSampled() {
    // tournamentSize=1 compares no extra candidates, so select returns the first sampled member.
    val pop = Population()
    val a = Individual(listOf(1), doubleArrayOf(10.0), 10.0, true)
    val b = Individual(listOf(2), doubleArrayOf(1.0), 1.0, true)
    pop.record(a)
    pop.record(b)
    Nsga.rankAndCrowd(pop.all())
    val chosen = Operators.select(pop, seededInts(0, 0, 0), tournamentSize = 1)
    assertEquals(pop.all()[0].genome, chosen.genome, "tournamentSize 1 returns the first sample")
  }
}
