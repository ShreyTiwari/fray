package org.pastalab.fray.core.scheduler.genetic

import org.pastalab.fray.core.randomness.Randomness

/**
 * Genetic operators (selection, crossover, mutation) producing the next genome from an
 * evaluated [Population]. Draws all randomness from Fray's [Randomness] so recorded runs
 * replay bit-identically.
 *
 * CAVEAT (review later): genome encoding is positional -- choice point i picks
 * `genome[i] % enabledCount`. Changing gene i shifts the meaning of every later gene past
 * the divergence point (schedule drift), so crossover/mutation are noisier than they look.
 * Planned upgrade: a choice-point-keyed genome that survives reordering.
 */
object Operators {

  fun randomGenome(
      rand: Randomness,
      length: Int = GeneticConfig.DEFAULT_GENOME_LENGTH,
      geneBound: Int = GeneticConfig.DEFAULT_GENE_BOUND,
  ): List<Int> {
    return List(length) { (rand.nextInt() % geneBound).coerceAtLeast(0) }
  }

  /**
   * Tournament selection by NSGA-II crowded-comparison ([Nsga.crowdedBetter]). Assumes the
   * caller has already run [Nsga.rankAndCrowd] so rank/crowding are current.
   */
  fun select(
      population: Population,
      rand: Randomness,
      tournamentSize: Int = GeneticConfig.DEFAULT_TOURNAMENT_SIZE,
  ): Individual {
    val members = population.all()
    var best: Individual = members[(rand.nextInt() % members.size).coerceAtLeast(0)]
    repeat((tournamentSize - 1).coerceAtLeast(0)) {
      val candidate = members[(rand.nextInt() % members.size).coerceAtLeast(0)]
      if (Nsga.crowdedBetter(candidate, best)) {
        best = candidate
      }
    }
    return best
  }

  /** Single-point crossover: child takes [a] up to a random cut, then [b]. */
  fun crossover(
      a: List<Int>,
      b: List<Int>,
      rand: Randomness,
      geneBound: Int = GeneticConfig.DEFAULT_GENE_BOUND,
  ): List<Int> {
    val length = maxOf(a.size, b.size)
    if (length == 0) return emptyList()
    val cut = (rand.nextInt() % length).coerceIn(0, length - 1)
    return List(length) { i ->
      val source = if (i < cut) a else b
      source.getOrElse(i) { (rand.nextInt() % geneBound).coerceAtLeast(0) }
    }
  }

  /** Uniform crossover: each gene is drawn independently from [a] or [b] with equal probability. */
  fun crossoverUniform(
      a: List<Int>,
      b: List<Int>,
      rand: Randomness,
      geneBound: Int = GeneticConfig.DEFAULT_GENE_BOUND,
  ): List<Int> {
    val length = maxOf(a.size, b.size)
    if (length == 0) return emptyList()
    return List(length) { i ->
      val source = if (rand.nextInt() % 2 == 0) a else b
      source.getOrElse(i) { (rand.nextInt() % geneBound).coerceAtLeast(0) }
    }
  }

  fun mutate(
      genome: List<Int>,
      rand: Randomness,
      mutationRate: Double = GeneticConfig.DEFAULT_MUTATION_RATE,
      geneBound: Int = GeneticConfig.DEFAULT_GENE_BOUND,
  ): List<Int> {
    return genome.map { gene ->
      if (rand.nextDouble() < mutationRate) {
        (rand.nextInt() % geneBound).coerceAtLeast(0)
      } else {
        gene
      }
    }
  }
}
