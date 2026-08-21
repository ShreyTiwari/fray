package org.pastalab.fray.core.scheduler.genetic

/**
 * Tuning knobs for the genetic scheduler. Not serialized (absent from `schedule.json`): transient
 * search state, so a recorded genome replays the same interleaving regardless of how the search
 * that produced it was tuned. Delivered two ways -- `--genetic-*` CLI flags, or `fray.genetic.*`
 * system properties for the JUnit path (see [fromSystemProperties]).
 */
data class GeneticConfig(
    val tournamentSize: Int = DEFAULT_TOURNAMENT_SIZE,
    val mutationRate: Double = DEFAULT_MUTATION_RATE,
    /** Length of a fresh/restart genome; choice points beyond it are appended. */
    val genomeLength: Int = DEFAULT_GENOME_LENGTH,
    /** Exclusive gene upper bound; only needs to exceed max concurrently-enabled threads, as genes
     * are reduced mod `enabledCount` at decode time. */
    val geneBound: Int = DEFAULT_GENE_BOUND,
    val seedPopulation: Int = DEFAULT_SEED_POPULATION,
    val populationCap: Int = DEFAULT_POPULATION_CAP,
    /**
     * Restart after this many consecutive non-novel iterations: keep the current Pareto front as
     * elite, flush the rest of the archive, and re-enter seeding to inject fresh random genomes.
     * 0 disables restart.
     */
    val restartStagnation: Int = DEFAULT_RESTART_STAGNATION,
    /**
     * Genome representation. false (default) = positional: gene[i] = the choice at the i-th
     * multi-thread decision point (suffers schedule drift). true = thread-priority vector:
     * gene[thread.index] = that thread's priority, decode runs the lowest-priority enabled thread
     * (PCT/POS-style), so mutation/crossover operate directly on run-order -- the state-determining
     * variable. thread.index is schedule-stable except when parents race to spawn concurrently.
     * Set via fray.genetic.genome=priority. Mutually exclusive with [posGenome].
     */
    val priorityGenome: Boolean = DEFAULT_PRIORITY_GENOME,
    /**
     * Seeded partial-order-sampling genome. false (default) uses [priorityGenome] / positional. true
     * decodes as POSScheduler -- schedule all non-racing ops first, else run the lowest-priority
     * enabled thread, then drop the chosen thread's and every racing thread's priority so they redraw
     * -- but each fresh priority is drawn from the genome (positionally) rather than the RNG. A random
     * genome therefore decodes to a plain POS schedule, so the reachable set is POS's churny
     * (handoff-rich) schedule space and the search floor is POS; novelty selection can then push past
     * it. The fixed-priority [priorityGenome] cannot reach that space (no per-race reshuffle => blocky
     * runs => few handoffs). Set via fray.genetic.genome=pos.
     */
    val posGenome: Boolean = DEFAULT_POS_GENOME,
    /**
     * Crossover operator. false (default) = single-point: child takes parent A up to a random cut,
     * then parent B (long contiguous runs survive from each parent). true = uniform: each gene is
     * drawn independently from A or B with equal probability, mixing parents at every position. For
     * the positional / seeded-POS genomes (a stream of per-decision draws) uniform crossover perturbs
     * more finely, which can help or hurt depending on how locally-structured good schedules are.
     * Set via fray.genetic.crossover=uniform.
     */
    val uniformCrossover: Boolean = DEFAULT_UNIFORM_CROSSOVER,
) {
  /** Clamp every field to a usable range so a misconfiguration degrades instead of failing the run. */
  fun sanitized(): GeneticConfig =
      GeneticConfig(
          tournamentSize = tournamentSize.coerceAtLeast(1),
          mutationRate = mutationRate.coerceIn(0.0, 1.0),
          genomeLength = genomeLength.coerceAtLeast(1),
          geneBound = geneBound.coerceAtLeast(2),
          seedPopulation = seedPopulation.coerceAtLeast(1),
          populationCap = populationCap.coerceAtLeast(1),
          restartStagnation = restartStagnation.coerceAtLeast(0),
          priorityGenome = priorityGenome,
          posGenome = posGenome,
          uniformCrossover = uniformCrossover,
      )

  companion object {
    const val DEFAULT_TOURNAMENT_SIZE = 3
    const val DEFAULT_MUTATION_RATE = 0.1
    const val DEFAULT_GENOME_LENGTH = 256
    const val DEFAULT_GENE_BOUND = 1 shl 16
    const val DEFAULT_SEED_POPULATION = 5
    const val DEFAULT_POPULATION_CAP = 50
    const val DEFAULT_PRIORITY_GENOME = false
    const val DEFAULT_POS_GENOME = false
    const val DEFAULT_UNIFORM_CROSSOVER = false

    // Unvalidated heuristic (independent of the pop cap), tunable once measured. With no `--feedback`
    // source registered `isNovel` is always false, so restart trips every N iterations -- benign:
    // objectives are 0-dim so the front-keep retains the whole archive and only reseeds fresh randoms.
    const val DEFAULT_RESTART_STAGNATION = 50

    val DEFAULT = GeneticConfig()

    /**
     * Build a config from `fray.genetic.*` system properties, falling back to [DEFAULT] for any
     * absent or unparseable property, then [sanitized]. This is the JUnit path's delivery channel:
     * the scheduler is constructed reflectively with a no-arg ctor there, so flags can't reach it.
     */
    fun fromSystemProperties(): GeneticConfig {
      fun intProp(key: String, default: Int) =
          System.getProperty(key)?.toIntOrNull() ?: default
      fun doubleProp(key: String, default: Double) =
          System.getProperty(key)?.toDoubleOrNull() ?: default
      return GeneticConfig(
              tournamentSize = intProp("fray.genetic.tournamentSize", DEFAULT_TOURNAMENT_SIZE),
              mutationRate = doubleProp("fray.genetic.mutationRate", DEFAULT_MUTATION_RATE),
              genomeLength = intProp("fray.genetic.genomeLength", DEFAULT_GENOME_LENGTH),
              geneBound = intProp("fray.genetic.geneBound", DEFAULT_GENE_BOUND),
              seedPopulation = intProp("fray.genetic.seedPopulation", DEFAULT_SEED_POPULATION),
              populationCap = intProp("fray.genetic.populationCap", DEFAULT_POPULATION_CAP),
              restartStagnation =
                  intProp("fray.genetic.restartStagnation", DEFAULT_RESTART_STAGNATION),
              priorityGenome = System.getProperty("fray.genetic.genome") == "priority",
              posGenome = System.getProperty("fray.genetic.genome") == "pos",
              uniformCrossover = System.getProperty("fray.genetic.crossover") == "uniform",
          )
          .sanitized()
    }
  }
}
