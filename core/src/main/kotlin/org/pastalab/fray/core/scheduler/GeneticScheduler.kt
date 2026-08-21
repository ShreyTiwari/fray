package org.pastalab.fray.core.scheduler

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.concurrency.operations.NonRacingOperation
import org.pastalab.fray.core.concurrency.operations.RacingOperation
import org.pastalab.fray.core.feedback.IterationFeedback
import org.pastalab.fray.core.randomness.ControlledRandom
import org.pastalab.fray.core.randomness.Randomness
import org.pastalab.fray.core.scheduler.genetic.GeneticConfig
import org.pastalab.fray.core.scheduler.genetic.Individual
import org.pastalab.fray.core.scheduler.genetic.Nsga
import org.pastalab.fray.core.scheduler.genetic.Operators
import org.pastalab.fray.core.scheduler.genetic.Population

/**
 * Grey-box, coverage-guided genetic scheduler. [scheduleNextOperation] decodes [genome] at each
 * choice point (`genome[i] % enabledCount`); [nextIteration] records the run's fitness into
 * [population] and breeds the next genome. [genome] is the only serialised state: a MutableList
 * because the run extends it in place when it outruns the seed, and persisting those extensions is
 * what makes schedule.json replay faithful. The @Transient search state is carried forward by hand
 * in [nextIteration], not via deserialisation.
 */
@Serializable
class GeneticScheduler(
    val genome: MutableList<Int> = mutableListOf(),
    // Supplies fresh genes when the run outruns the seed. Transient because replay reads the
    // persisted [genome]; passed up so the inherited [Scheduler.rand] is the same instance.
    @Transient private val seedRand: Randomness = ControlledRandom(),
) : Scheduler(seedRand) {
  // Explicit no-arg ctor: the reflective @ConcurrencyTest path uses newInstance(), and a Kotlin
  // default parameter does not produce a callable zero-arg constructor. GA tuning comes from
  // `fray.genetic.*` system properties because the reflective path has no other config channel
  // (the CLI path overrides `config` after construction).
  constructor() : this(mutableListOf()) {
    config = GeneticConfig.fromSystemProperties()
    population = Population(config.populationCap)
  }

  // Single-argument constructor used to reconstruct the scheduler for replay.
  constructor(rand: Randomness) : this(mutableListOf(), rand)

  @Transient var config: GeneticConfig = GeneticConfig.DEFAULT

  // The archive of evaluated genomes the GA evolves from.
  @Transient var population: Population = Population(config.populationCap)

  @Transient private var decisionIndex = 0

  // Per-iteration state for the seeded-POS genome (config.posGenome). Transient: a fresh scheduler is
  // built every iteration by [nextIteration], so these reset per iteration exactly like POSScheduler's.
  // priorities are keyed by ThreadContext to mirror POSScheduler; nonRacingBuffer is scratch reused per
  // decision to avoid per-call allocation.
  @Transient private val posThreadPriority = mutableMapOf<ThreadContext, Double>()
  @Transient private val nonRacingBuffer = mutableListOf<ThreadContext>()

  // Restart state:
  //  - stagnationCounter: consecutive non-novel iterations; triggers a restart at the threshold.
  //  - reseedRemaining: after a restart, force this many fresh random genomes so the search
  //    re-diversifies rather than immediately breeding from the kept elite.
  @Transient var stagnationCounter: Int = 0
  @Transient var reseedRemaining: Int = 0

  override fun scheduleNextOperation(
      threads: List<ThreadContext>,
      allThreads: Collection<ThreadContext>,
  ): ThreadContext {
    // Forced step: no scheduling information, don't consume a gene.
    if (threads.size == 1) {
      return threads[0]
    }

    // Seeded partial-order-sampling genome (fray.genetic.genome=pos): POSScheduler's decode, but each
    // fresh priority comes from the genome instead of the RNG, so the search steers POS's per-race
    // reshuffle. Placed before the priority/positional branches; those are unreachable when posGenome.
    if (config.posGenome) {
      return schedulePosGenome(threads)
    }

    // Priority-vector genome (fray.genetic.genome=priority): gene[t.index] is thread t's priority; run
    // the lowest-priority enabled thread. Extend the genome with a fresh random priority when a thread
    // index is first seen (mirrors PCT/POS assigning a priority at spawn); appended genes are persisted
    // in [genome] so replay stays faithful. Keying on thread.index lets mutation/crossover act on
    // run-order directly (the state-determining variable); index is schedule-stable except when parents
    // race to spawn concurrently, where it can rebind to a different thread (see feedback/thread-identity.md).
    if (config.priorityGenome) {
      val maxIndex = threads.maxOf { it.index }
      while (genome.size <= maxIndex) {
        genome.add((rand.nextInt() % config.geneBound).coerceAtLeast(0))
      }
      return threads.minBy { genome[it.index] }
    }

    // Extend the genome in place when the run outruns the seed; persisted for faithful replay.
    if (decisionIndex >= genome.size) {
      genome.add((rand.nextInt() % config.geneBound).coerceAtLeast(0))
    }
    val gene = genome[decisionIndex]
    decisionIndex++

    // CAVEAT: positional decoding. The index depends on how many threads are enabled here, so an
    // upstream mutation that changes the enabled set shifts this gene's meaning (schedule drift).
    // Planned fix is a choice-point-keyed genome.
    val index = gene % threads.size
    return threads[index]
  }

  // Seeded POS decode, faithful to POSScheduler except that a fresh priority is pulled from the genome
  // (via [nextPriorityGene]) rather than rand.nextDouble(). Schedule all non-racing ops first; else run
  // the lowest-priority enabled thread; then drop the chosen thread's priority AND every enabled racing
  // thread's priority so they redraw on their next step -- POS's partial-order sampling. Because a random
  // genome yields random priorities, a fresh/seed individual decodes to a plain POS run; crossover and
  // mutation then explore neighbouring POS schedules under the coverage objective.
  private fun schedulePosGenome(threads: List<ThreadContext>): ThreadContext {
    nonRacingBuffer.clear()
    for (thread in threads) {
      if (thread !in posThreadPriority) {
        posThreadPriority[thread] = nextPriorityGene()
      }
      if (thread.pendingOperation is NonRacingOperation) {
        nonRacingBuffer.add(thread)
      }
    }
    val next =
        if (nonRacingBuffer.isNotEmpty()) {
          nonRacingBuffer.minBy { posThreadPriority[it]!! }
        } else {
          threads.minBy { posThreadPriority[it]!! }
        }
    posThreadPriority.keys.removeIf {
      val pendingOp = it.pendingOperation
      it == next ||
          (pendingOp is RacingOperation && it in threads && pendingOp.isRacing(next.pendingOperation))
    }
    return next
  }

  // One genome-sourced priority in [0.0, 1.0). Consumes genome[decisionIndex], extending the genome in
  // place with a fresh random gene when the run outruns the seed (persisted for faithful replay, exactly
  // like the positional decode). geneBound is the exclusive gene bound, so gene / geneBound is in [0, 1).
  private fun nextPriorityGene(): Double {
    if (decisionIndex >= genome.size) {
      genome.add((rand.nextInt() % config.geneBound).coerceAtLeast(0))
    }
    val gene = genome[decisionIndex]
    decisionIndex++
    return gene.toDouble() / config.geneBound
  }

  // Feedback-free entry point required by [Scheduler]; the GA degenerates to random seeding when
  // nothing calls the feedback-aware overload.
  override fun nextIteration(randomness: Randomness): Scheduler =
      nextIteration(randomness, IterationFeedback.EMPTY)

  override fun nextIteration(randomness: Randomness, feedback: IterationFeedback): Scheduler {
    // Record the genome with its objective vector (NSGA-II ranks on the vector, not scalar fitness).
    // First call has empty feedback (0-dim, fitness 0) -- harmless, seeds a baseline individual.
    population.record(
        Individual(genome.toList(), feedback.objectiveVector(), feedback.fitness, feedback.isNovel))

    // Stagnation-triggered restart: once coverage stops growing for `restartStagnation` iterations,
    // the search has likely converged on a local optimum -- keep the Pareto front as elite, flush
    // the rest, and queue a burst of fresh random genomes. `restartStagnation == 0` disables it.
    if (feedback.isNovel) {
      stagnationCounter = 0
    } else if (config.restartStagnation > 0) {
      stagnationCounter++
      if (stagnationCounter >= config.restartStagnation) {
        population.restartKeepingFront()
        // Refill the seed pool beyond the kept elite.
        reseedRemaining = (config.seedPopulation - population.size).coerceAtLeast(1)
        stagnationCounter = 0
        if (FEEDBACK_DEBUG) {
          println(
              "[genetic] RESTART after stagnation: kept front of ${population.size}, " +
                  "reseeding $reseedRemaining")
        }
      }
    }

    // Seed fresh random genomes while below the seed pool OR during a post-restart reseed burst;
    // otherwise breed: select two parents, cross, mutate.
    val seeding = population.size < config.seedPopulation || reseedRemaining > 0
    if (reseedRemaining > 0) reseedRemaining--
    val nextGenome =
        if (seeding) {
          Operators.randomGenome(randomness, config.genomeLength, config.geneBound)
        } else {
          // Refresh Pareto rank + crowding so the tournament in select() compares on current values.
          Nsga.rankAndCrowd(population.all())
          val parentA = Operators.select(population, randomness, config.tournamentSize)
          val parentB = Operators.select(population, randomness, config.tournamentSize)
          val crossed =
              if (config.uniformCrossover) {
                Operators.crossoverUniform(parentA.genome, parentB.genome, randomness, config.geneBound)
              } else {
                Operators.crossover(parentA.genome, parentB.genome, randomness, config.geneBound)
              }
          Operators.mutate(crossed, randomness, config.mutationRate, config.geneBound)
        }

    // Opt-in tracing (-Dfray.feedback.debug=true) that the feedback drives the GA.
    if (FEEDBACK_DEBUG) {
      println(
          "[genetic] received objectives=${feedback.objectives} fitness=${feedback.fitness} " +
              "novel=${feedback.isNovel} ranGenomeLen=${genome.size} " +
              "populationSize=${population.size} " +
              "mode=${if (seeding) "seed-random" else "breed(select+crossover+mutate)"} " +
              "nextGenomeLen=${nextGenome.size}")
    }

    // Carry transient search state forward by hand (never deserialised). [randomness] goes through
    // the constructor because the inherited [Scheduler.rand] is a val.
    return GeneticScheduler(nextGenome.toMutableList(), randomness).also {
      it.config = config
      it.population = population
      it.stagnationCounter = stagnationCounter
      it.reseedRemaining = reseedRemaining
    }
  }

  companion object {
    private val FEEDBACK_DEBUG = System.getProperty("fray.feedback.debug", "false").toBoolean()
  }
}
