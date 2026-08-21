package org.pastalab.fray.core.scheduler.genetic

/**
 * One evaluated population member: the genome that ran and its search feedback.
 *
 * [objectives] is the per-source objective vector the GA ranks on via Pareto dominance + crowding
 * ([Nsga]); all individuals in a run share its length. [fitness] is the scalar `Σ objectives`,
 * kept only for debug/reporting. [rank]/[crowding] are NSGA-II scratch, meaningless until
 * [Nsga.rankAndCrowd] has run.
 */
class Individual(
    val genome: List<Int>,
    val objectives: DoubleArray,
    val fitness: Double,
    val novel: Boolean,
) {
  var rank: Int = 0
  var crowding: Double = 0.0
}

/**
 * The genetic population, carried across iterations as transient search state (never serialised,
 * never part of replay).
 *
 * Bounded archive: over [maxSize] it evicts by NSGA-II crowded-comparison order
 * ([Nsga.crowdedComparator]). Pareto-aware eviction preserves specialists; a plain scalar-sum
 * eviction would discard the strong-in-one-objective genomes multi-objective search exists to keep.
 */
class Population(val maxSize: Int = GeneticConfig.DEFAULT_POPULATION_CAP) {
  private val members = mutableListOf<Individual>()

  val size: Int
    get() = members.size

  fun isEmpty(): Boolean = members.isEmpty()

  fun record(individual: Individual) {
    members.add(individual)
    if (members.size > maxSize) {
      Nsga.rankAndCrowd(members)
      members.sortWith(Nsga.crowdedComparator)
      while (members.size > maxSize) {
        members.removeAt(members.size - 1)
      }
    }
  }

  /**
   * Restart: retain only the current Pareto front (rank 0) as elite, discard the rest. Keeps the
   * best discoveries while flushing stale members whose frozen objectives would ossify selection.
   */
  fun restartKeepingFront() {
    if (members.isEmpty()) return
    Nsga.rankAndCrowd(members)
    val front = members.filter { it.rank == 0 }
    members.clear()
    members.addAll(front)
  }

  fun all(): List<Individual> = members.toList()
}
