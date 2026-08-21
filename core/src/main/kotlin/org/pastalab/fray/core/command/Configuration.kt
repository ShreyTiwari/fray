package org.pastalab.fray.core.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.defaultByName
import com.github.ajalt.clikt.parameters.groups.groupChoice
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.TimeSource
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.concurrency.operations.RacingOperation
import org.pastalab.fray.core.debugger.DebuggerRegistry
import org.pastalab.fray.core.feedback.FeedbackAggregator
import org.pastalab.fray.core.feedback.FeedbackSource
import org.pastalab.fray.core.feedback.IterationFeedback
import org.pastalab.fray.core.feedback.IterationResult
import org.pastalab.fray.core.feedback.NoveltyValuation
import org.pastalab.fray.core.feedback.sources.ArtifactFeedbackSource
import org.pastalab.fray.core.feedback.sources.CodeLocationFeedbackSource
import org.pastalab.fray.core.feedback.sources.HandoffFeedbackSource
import org.pastalab.fray.core.feedback.sources.StateFeedbackSource
import org.pastalab.fray.core.logger.FrayLogger
import org.pastalab.fray.core.observers.AntithesisErrorReporter
import org.pastalab.fray.core.observers.ResourceOrderingCoverage
import org.pastalab.fray.core.observers.ScheduleRecorder
import org.pastalab.fray.core.observers.ScheduleRecording
import org.pastalab.fray.core.observers.ScheduleVerifier
import org.pastalab.fray.core.observers.ResourceIdentityLogger
import org.pastalab.fray.core.observers.ThreadIdentityLogger
import org.pastalab.fray.core.observers.ThreadOrderingCoverage
import org.pastalab.fray.core.observers.ThreadPausingTimeLogger
import org.pastalab.fray.core.observers.TimelineCoverage
import org.pastalab.fray.core.observers.TimelineCoverageType
import org.pastalab.fray.core.randomness.AntithesisSdkRandomProvider
import org.pastalab.fray.core.randomness.ControlledRandomProvider
import org.pastalab.fray.core.randomness.Randomness
import org.pastalab.fray.core.randomness.RandomnessProvider
import org.pastalab.fray.core.randomness.RecordedRandomProvider
import org.pastalab.fray.core.scheduler.*
import org.pastalab.fray.core.scheduler.genetic.GeneticConfig
import org.pastalab.fray.core.scheduler.genetic.Population
import org.pastalab.fray.core.scheduler.qlearning.LastObservedStateHolder
import org.pastalab.fray.core.scheduler.qlearning.QLearningConfig
import org.pastalab.fray.core.scheduler.qlearning.QTable
import org.pastalab.fray.core.scheduler.qlearning.StateAbstraction
import org.pastalab.fray.rmi.Constant.FRAY_DEBUGGER_DEBUG
import org.pastalab.fray.rmi.Constant.FRAY_DEBUGGER_DISABLED
import org.pastalab.fray.rmi.Constant.FRAY_DEBUGGER_MCP
import org.pastalab.fray.rmi.Constant.FRAY_DEBUGGER_PROPERTY_KEY
import org.pastalab.fray.rmi.Constant.FRAY_DEBUGGER_REPLAY
import org.pastalab.fray.rmi.ScheduleObserver
import org.pastalab.fray.rmi.TestStatusObserver

@Serializable
data class ExecutionInfo(
    @Polymorphic val executor: Executor,
    val ignoreUnhandledExceptions: Boolean,
    val interleaveMemoryOps: Boolean,
    val maxScheduledStep: Int,
)

sealed class ExecutionConfig(name: String) : OptionGroup(name) {
  open fun getExecutionInfo(
      ignoreUnhandledExceptions: Boolean,
      interleaveMemoryOps: Boolean,
      maxScheduledStep: Int,
  ): ExecutionInfo {
    return ExecutionInfo(
        MethodExecutor("", "", emptyList(), emptyList(), emptyMap()),
        ignoreUnhandledExceptions,
        interleaveMemoryOps,
        maxScheduledStep,
    )
  }
}

class EmptyExecutionConfig : ExecutionConfig("empty") {
  override fun getExecutionInfo(
      ignoreUnhandledExceptions: Boolean,
      interleaveMemoryOps: Boolean,
      maxScheduledStep: Int,
  ): ExecutionInfo {
    return ExecutionInfo(
        LambdaExecutor {},
        ignoreUnhandledExceptions,
        interleaveMemoryOps,
        maxScheduledStep,
    )
  }
}

class CliExecutionConfig : ExecutionConfig("cli") {
  val clazz by option().required()
  val method by option().required()
  val targetArgs by
      option("-a", "--args", help = "Arguments passed to target application")
          .split(":")
          .default(emptyList())
  val classpaths by
      option("-cp", "--classpath", help = "Arguments passed to target application")
          .split(":")
          .default(emptyList())
  val properties by option("-D", help = "System properties").pair().multiple()

  override fun getExecutionInfo(
      ignoreUnhandledExceptions: Boolean,
      interleaveMemoryOps: Boolean,
      maxScheduledStep: Int,
  ): ExecutionInfo {
    val propertyMap = properties.toMap()
    return ExecutionInfo(
        MethodExecutor(clazz, method, targetArgs, classpaths, propertyMap),
        ignoreUnhandledExceptions,
        interleaveMemoryOps,
        maxScheduledStep,
    )
  }
}

class JsonExecutionConfig : ExecutionConfig("json") {
  val path by option("--config-path").file().required()

  override fun getExecutionInfo(
      ignoreUnhandledExceptions: Boolean,
      interleaveMemoryOps: Boolean,
      maxScheduledStep: Int,
  ): ExecutionInfo {
    val module = SerializersModule {
      polymorphic(Executor::class) {
        subclass(MethodExecutor::class)
        defaultDeserializer { MethodExecutor.serializer() }
      }
    }
    val json = Json {
      serializersModule = module
      @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
      namingStrategy = JsonNamingStrategy.SnakeCase
    }
    return json.decodeFromString<ExecutionInfo>(path.readText())
  }
}

sealed class RandomnessProviderOption(name: String) : OptionGroup(name) {
  open fun getRandomnessProvider(): RandomnessProvider {
    return ControlledRandomProvider()
  }
}

class ControlledRandomOption : RandomnessProviderOption("controlled-random") {
  override fun getRandomnessProvider(): RandomnessProvider {
    return ControlledRandomProvider()
  }
}

class RecordedRandomOption : RandomnessProviderOption("recorded-random") {
  val path by option("--path-to-random").file().required()

  override fun getRandomnessProvider(): RandomnessProvider {
    return RecordedRandomProvider(path.absolutePath)
  }
}

class AntithesisSdkRandomOption : RandomnessProviderOption("antithesis-sdk-random") {
  override fun getRandomnessProvider(): RandomnessProvider {
    return AntithesisSdkRandomProvider()
  }
}

sealed class ScheduleAlgorithm(name: String, val isReplay: Boolean) : OptionGroup(name) {
  open fun getScheduler(randomness: Randomness): Scheduler {
    return FifoScheduler()
  }
}

class Fifo : ScheduleAlgorithm("fifo", false) {
  override fun getScheduler(randomness: Randomness): Scheduler {
    return FifoScheduler()
  }
}

class POS : ScheduleAlgorithm("pos", false) {
  override fun getScheduler(randomness: Randomness): Scheduler {
    return POSScheduler(randomness)
  }
}

class ReplayFromRecordings : ScheduleAlgorithm("replay-from-recordings", true) {
  val path by option("--path-to-recordings").file().required()

  override fun getScheduler(randomness: Randomness): Scheduler {
    val recordingPath = "${path.absolutePath}/recording.json"
    val scheduleRecordings =
        Json.decodeFromString<List<ScheduleRecording>>(File(recordingPath).readText())
    return ReplayScheduler(scheduleRecordings)
  }
}

class Replay : ScheduleAlgorithm("replay-from-scheduler", true) {
  val path by option("--path-to-scheduler").file().required()

  override fun getScheduler(randomness: Randomness): Scheduler {
    // The schedule is fully determined by (scheduler class, randomness).
    val className = File("${path.absolutePath}/scheduler").readText().trim()
    val recordedRandom =
        Json.decodeFromString<Randomness>(File("${path.absolutePath}/random.json").readText())
    return this::class
        .java
        .classLoader
        .loadClass(className)
        .getConstructor(Randomness::class.java)
        .newInstance(recordedRandom) as Scheduler
  }
}

class Rand : ScheduleAlgorithm("random", false) {
  override fun getScheduler(randomness: Randomness): Scheduler {
    return RandomScheduler(randomness)
  }
}

class PCT : ScheduleAlgorithm("pct", false) {
  val numSwitchPoints by option().int().default(3)
  val maxStep by option().int().default(0)

  override fun getScheduler(randomness: Randomness): Scheduler {
    return PCTScheduler(randomness, numSwitchPoints, maxStep)
  }
}

class SURW : ScheduleAlgorithm("surw", false) {
  override fun getScheduler(randomness: Randomness): Scheduler {
    return SURWScheduler(randomness, mutableMapOf(), mutableSetOf())
  }
}

class Fest : ScheduleAlgorithm("fest", false) {
  val festBase by
      option(
              "--fest-base",
              help =
                  "Base RSBT algorithm that Fest adaptively guides. Fest mutates the recordings " +
                      "produced by this algorithm.",
          )
          .choice("random", "pos", "pct")
          .default("pos")
  val mutationCount by
      option(
              "--fest-mutation-count",
              help = "Average number of recorded values mutated per replay.",
          )
          .int()
          .default(FestScheduler.DEFAULT_MUTATION_COUNT)
  val mutationBudget by
      option(
              "--fest-mutation-budget",
              help = "Number of mutations spent on each saved recording before moving to the next.",
          )
          .int()
          .default(FestScheduler.DEFAULT_MUTATION_BUDGET)

  override fun getScheduler(randomness: Randomness): Scheduler {
    // Construct the base bound to the seed randomness; Fest shares that single stream with it.
    val base =
        when (festBase) {
          "random" -> RandomScheduler(randomness)
          "pos" -> POSScheduler(randomness)
          "pct" -> PCTScheduler(randomness, 3, 0)
          else -> RandomScheduler(randomness)
        }
    return FestScheduler(base, mutationCount, mutationBudget)
  }
}

class Genetic : ScheduleAlgorithm("genetic", false) {
  // Nullable so an unpassed flag (null) falls back to the fray.genetic.* base, then GeneticConfig.DEFAULT.
  private val tournamentSize by option("--genetic-tournament-size").int()
  private val mutationRate by option("--genetic-mutation-rate").double()
  private val genomeLength by option("--genetic-genome-length").int()
  private val geneBound by option("--genetic-gene-bound").int()
  private val seedPopulation by option("--genetic-seed-population").int()
  private val populationCap by option("--genetic-population-cap").int()
  // Consecutive non-novel iterations before a restart; 0 disables.
  private val restartStagnation by option("--genetic-restart-stagnation").int()

  override fun getScheduler(randomness: Randomness): Scheduler {
    // System-property base so CLI and JUnit share one resolution path; overlay passed flags, then clamp.
    val base = GeneticConfig.fromSystemProperties()
    val config =
        base.copy(
                tournamentSize = tournamentSize ?: base.tournamentSize,
                mutationRate = mutationRate ?: base.mutationRate,
                genomeLength = genomeLength ?: base.genomeLength,
                geneBound = geneBound ?: base.geneBound,
                seedPopulation = seedPopulation ?: base.seedPopulation,
                populationCap = populationCap ?: base.populationCap,
                restartStagnation = restartStagnation ?: base.restartStagnation,
            )
            .sanitized()
    return GeneticScheduler(mutableListOf(), randomness).also {
      it.config = config
      it.population = Population(config.populationCap)
    }
  }
}

class Dynamic : ScheduleAlgorithm("dynamic", false) {
  val schedulerName by option("--scheduler-name")

  override fun getScheduler(randomness: Randomness): Scheduler {
    val clazz = this::class.java.classLoader.loadClass(schedulerName)
    val scheduler =
        clazz.getConstructor(Randomness::class.java).newInstance(randomness) as Scheduler
    return scheduler
  }
}

class QLearning : ScheduleAlgorithm("qlearning", false) {
  // Nullable so an unpassed flag (null) falls back to the fray.qlearning.* base, then QLearningConfig.DEFAULT.
  private val alpha by option("--qlearning-alpha").double()
  private val gamma by option("--qlearning-gamma").double()
  private val eps0 by option("--qlearning-eps0").double()
  private val epsDecay by option("--qlearning-eps-decay").double()
  private val epsMin by option("--qlearning-eps-min").double()
  private val optimisticInit by option("--qlearning-optimistic-init").double()
  private val tableCap by option("--qlearning-table-cap").int()
  private val nStep by option("--qlearning-n-step").int()
  private val stateMode by option("--qlearning-state-mode")
  private val stateClass by option("--qlearning-state-class")
  private val rewardBug by option("--qlearning-reward-bug").double()
  private val rewardNovelty by option("--qlearning-reward-novelty").double()
  private val rewardFitness by option("--qlearning-reward-fitness").double()

  override fun getScheduler(randomness: Randomness): Scheduler {
    // System-property base so CLI and JUnit share one resolution path; overlay passed flags, then clamp.
    val base = QLearningConfig.fromSystemProperties()
    val config =
        base.copy(
                alpha = alpha ?: base.alpha,
                gamma = gamma ?: base.gamma,
                eps0 = eps0 ?: base.eps0,
                epsDecay = epsDecay ?: base.epsDecay,
                epsMin = epsMin ?: base.epsMin,
                optimisticInit = optimisticInit ?: base.optimisticInit,
                tableCap = tableCap ?: base.tableCap,
                nStep = nStep ?: base.nStep,
                stateMode = stateMode ?: base.stateMode,
                stateClass = stateClass ?: base.stateClass,
                rewardBug = rewardBug ?: base.rewardBug,
                rewardNovelty = rewardNovelty ?: base.rewardNovelty,
                rewardFitness = rewardFitness ?: base.rewardFitness,
            )
            .sanitized()
    // location mode reads RacingOperation.stackTraceHash, which is 0 unless resolution is forced on.
    if (StateAbstraction.resolveMode(config.stateMode) == QLearningConfig.MODE_LOCATION) {
      RacingOperation.resolveRacingOperationStackTraceHash = true
    }
    // policyRand is deliberately left at its own ControlledRandom(): sharing [randomness] would put
    // policy draws on the runtime's recorded stream, and replay decodes the trace without drawing,
    // so the runtime's draws would read the wrong positions back. See QLearningScheduler.policyRand.
    return QLearningScheduler(mutableListOf(), randomness).also {
      it.config = config
      it.qTable = QTable(config)
      it.epsilon = config.eps0
      // observedStateHolder is injected Configuration-side later, so observedState mode resolves
      // lazily via the provider closure inside refreshAbstraction.
      it.refreshAbstraction()
    }
  }
}

class MainCommand : CliktCommand() {
  val report by
      option("-o", "--output", help = "Report output directory.")
          .path()
          .default(Files.createTempDirectory("fray-workspace"))
  val timeout by
      option("-t", "--timeout", help = "Testing timeout in seconds.").int().default(60 * 10)
  val iter by option("-i", "--iter", help = "Number of iterations.").int().default(100000)
  val fullSchedule by
      option(
              "-f",
              "--full",
              help =
                  "If the report should save full schedule. Otherwise, Fray only saves schedules points if there are more than one runnable threads.",
          )
          .flag()

  val ignoreUnhandledExceptions by option("-e", "--ignore-unhandled-exceptions").flag()
  val interleaveMemoryOps by option("-m", "--memory").flag()
  val maxScheduledStep by option("-s", "--max-scheduled-step").int().default(-1)
  val scheduler by
      option(help = "Scheduling algorithm.")
          .groupChoice(
              "fifo" to Fifo(),
              "pos" to POS(),
              "random" to Rand(),
              "pct" to PCT(),
              "surw" to SURW(),
              "fest" to Fest(),
              "genetic" to Genetic(),
              "qlearning" to QLearning(),
              "dynamic" to Dynamic(),
              "replay-from-recordings" to ReplayFromRecordings(),
              "replay" to Replay(),
          )
          .defaultByName("random")
  val randomnessProvider by
      option(help = "Randomness provider.")
          .groupChoice(
              "controlled-random" to ControlledRandomOption(),
              "recorded-random" to RecordedRandomOption(),
              "antithesis-sdk-random" to AntithesisSdkRandomOption(),
          )
          .defaultByName("controlled-random")
  val noFray by option("--no-fray", help = "Runnning in no-Fray mode.").flag()
  val exploreMode by
      option(
              "--explore",
              help = "Running in explore mode and Fray will continue if a failure is found.",
          )
          .flag()
  val noExitWhenBugFound by
      option("--no-exit-on-bug", help = "Fray will not immediately exit when a failure is found.")
          .flag()
  val runConfig by
      option("--run-config", help = "Run configuration for the application.")
          .groupChoice(
              "cli" to CliExecutionConfig(),
              "json" to JsonExecutionConfig(),
              "empty" to EmptyExecutionConfig(),
          )
          .defaultByName("cli")
  val dummyRun by
      option(
              "--dummy-run",
              help =
                  "Run the target application without dummy run. The dummy run (run target once " +
                      "before launching Fray) helps Fray to prune out non-determinism " +
                      "introduced by the constructors and initializers.",
          )
          .flag()
  val networkDelegateType by
      option(
              "--network-delegate-type",
              help =
                  "Network delegate type. Possible values: PROACTIVE, REACTIVE, NONE. " +
                      "Default is PROACTIVE.",
          )
          .choice(
              "proactive" to NetworkDelegateType.PROACTIVE,
              "reactive" to NetworkDelegateType.REACTIVE,
              "none" to NetworkDelegateType.NONE,
          )
          .default(NetworkDelegateType.REACTIVE)
  val systemTimeDelegateType by
      option(
              "--system-time-delegate-type",
              help = "Time delegate type. Possible values: MOCK, NONE. " + "Default is MOCK.",
          )
          .choice(
              "mock" to SystemTimeDelegateType.MOCK,
              "none" to SystemTimeDelegateType.NONE,
          )
          .default(SystemTimeDelegateType.NONE)
  val virtualTimeDelta by
      option(
              "--virtual-time-delta",
              help =
                  "Amount of virtual time to advance per step (only valid when system time delegate type is MOCK). Must be a positive value.",
          )
          .long()
          .default(100_000L)
  val ignoreTimedBlock by
      option(
              "--ignore-timed-block",
              help =
                  "Ignore timed block in the target application (e.g., Thread.sleep) will be " +
                      "unblocked immediately.",
          )
          .flag("--no-ignore-timed-block", default = true)
  val sleepAsYield by
      option("--sleep-as-yield", help = "Treat Thread.sleep as Thread.yield.")
          .flag("--no-sleep-as-yield", default = false)
  val resetClassLoader by
      option(
              "--reset-class-loader",
              help =
                  "Reset the class loader before each iteration. This helps to eliminate " +
                      "non-determinism introduced by static initializers.",
          )
          .flag("--no-reset-class-loader", default = true)
  val redirectStdout by
      option("--redirect-stdout", help = "Redirect stdout and stderr to files in report dir.")
          .flag()
  val abortThreadExecutionAfterMainExit by
      option(
              "--abort-thread-after-main-exit",
              help =
                  "Abort all other threads' execution after the main thread exits. This helps to " +
                      "eliminate non-determinism introduced by background threads.",
          )
          .flag("--no-abort-thread-after-main-exit", default = false)
  private val timelineCoverageOption by
      option(
              "--timeline-coverage",
              help =
                  "Timeline coverage type to collect during testing. " +
                      "Options: thread-ordering, resource-ordering, none.",
          )
          .choice("thread-ordering", "resource-ordering", "none")
          .default("resource-ordering")
  val timelineCoverageType: TimelineCoverageType?
    get() =
        when (timelineCoverageOption) {
          "thread-ordering" -> TimelineCoverageType.THREAD_ORDERING
          "resource-ordering" -> TimelineCoverageType.RESOURCE_ORDERING
          else -> null
        }

  val feedback by
      option(
          "--feedback",
          help =
              "Comma-separated grey-box feedback sources for coverage-guided schedulers (e.g. " +
                  "genetic). When set, this is the COMPLETE list of sources. Kinds: " +
                  "'state[:target=N]', 'artifact:path=/p', 'handoff', 'location', 'none'. " +
                  "Examples: 'state' (value feedback); 'artifact:path=/tmp/x' (pull-at-end file " +
                  "hashing); 'handoff' (resource handoffs between threads); 'location' " +
                  "(concurrency code-site coverage); 'none'. If omitted, no feedback sources are " +
                  "registered. Each source may also carry ';'-separated C5 novelty-valuation " +
                  "knobs: 'decay=linear|log|sqrt|satexp' (saturation curve; LOG once any knob is " +
                  "given, else linear/pre-C5), 'weight=W' (>0; mostly affects reported fitness -- " +
                  "see feedback/README.md §11), 'k=K' (satexp knee). The during-run sources " +
                  "(state/handoff/location) use AFL-style hit-count bucketing (count, not just " +
                  "presence) by default; pass 'hits=false' to opt out to plain set membership. E.g. " +
                  "'location:decay=log', 'state:target=4999;weight=2;decay=log', 'handoff:hits=false'.",
      )

  val measure by
      option(
          "--measure",
          help =
              "Comma-separated feedback sources to COLLECT FOR REPORTING ONLY, using the same source " +
                  "grammar as --feedback (e.g. 'handoff,location' or 'location:hits=false'). These are " +
                  "measured every iteration and appear in the -Dfray.feedback.debug 'globalBySource' " +
                  "trace (so they can be plotted), but they are NOT used as search feedback: a " +
                  "coverage-guided scheduler (e.g. genetic) does not rank or restart on them. This " +
                  "decouples what to MEASURE from what to USE AS FEEDBACK -- e.g. '--feedback state " +
                  "--measure handoff,location' searches on state alone while still plotting all three " +
                  "coverages. A source named in BOTH --feedback and --measure stays a search objective " +
                  "(--feedback wins). C5 valuation knobs on a --measure entry are ignored (they only " +
                  "shape objectives).",
      )

  override fun run() {}

  fun toConfiguration(): Configuration {
    // Set before constructing Configuration so init{} sees these on both the CLI and any in-JVM caller.
    feedback?.let { System.setProperty("fray.feedback", it) }
    measure?.let { System.setProperty("fray.measure", it) }
    val executionInfo =
        runConfig.getExecutionInfo(ignoreUnhandledExceptions, interleaveMemoryOps, maxScheduledStep)
    val randomnessProvider = randomnessProvider.getRandomnessProvider()
    val resolveStackTraceHash =
        scheduler is SURW || timelineCoverageType == TimelineCoverageType.THREAD_ORDERING
    val configuration =
        Configuration(
            executionInfo,
            report,
            iter,
            timeout,
            scheduler.getScheduler(randomnessProvider.getRandomness()),
            randomnessProvider,
            fullSchedule,
            exploreMode,
            noExitWhenBugFound,
            scheduler.isReplay,
            noFray,
            dummyRun,
            networkDelegateType,
            systemTimeDelegateType,
            virtualTimeDelta,
            ignoreTimedBlock,
            sleepAsYield,
            resetClassLoader,
            redirectStdout,
            abortThreadExecutionAfterMainExit,
            resolveRacingOperationStackTraceHash = resolveStackTraceHash,
            timelineCoverageType = timelineCoverageType,
        )
    if (System.getProperty("fray.antithesisSdk", "false").toBoolean()) {
      configuration.testStatusObservers.add(AntithesisErrorReporter())
    }
    return configuration
  }
}

data class Configuration(
    val executionInfo: ExecutionInfo,
    val report: Path,
    var iter: Int,
    val timeout: Int,
    var scheduler: Scheduler,
    val randomnessProvider: RandomnessProvider,
    val fullSchedule: Boolean,
    val exploreMode: Boolean,
    var noExitWhenBugFound: Boolean,
    val isReplay: Boolean,
    val noFray: Boolean,
    val dummyRun: Boolean,
    val networkDelegateType: NetworkDelegateType,
    val systemTimeDelegateType: SystemTimeDelegateType,
    val virtualTimeDelta: Long,
    val ignoreTimedBlock: Boolean,
    val sleepAsYield: Boolean,
    val resetClassLoader: Boolean,
    val redirectStdout: Boolean,
    val abortThreadExecutionAfterMainExit: Boolean,
    val resolveRacingOperationStackTraceHash: Boolean,
    val timelineCoverageType: TimelineCoverageType?,
) {
  val scheduleObservers = mutableListOf<ScheduleObserver<ThreadContext>>()
  val testStatusObservers = mutableListOf<TestStatusObserver>()
  val timelineCoverage: TimelineCoverage? =
      when (timelineCoverageType) {
        TimelineCoverageType.THREAD_ORDERING -> ThreadOrderingCoverage()
        TimelineCoverageType.RESOURCE_ORDERING -> ResourceOrderingCoverage()
        TimelineCoverageType.NONE -> null
        null -> null
      }
          // Fest is feedback-guided and is meaningless without coverage: if none was specified,
          // fall back to the default measurement so it still has a signal to steer by.
          ?: if (scheduler is FestScheduler) ResourceOrderingCoverage() else null
  // Grey-box feedback sources; some are also schedule/test-status observers. Runtime-only: never
  // serialised, plays no part in replay.
  val feedbackSources = mutableListOf<FeedbackSource>()
  // Per-source novelty valuation keyed by source name; a source absent here uses NoveltyValuation.IDENTITY.
  val feedbackValuations = mutableMapOf<String, NoveltyValuation>()
  // Sources collected and reported but excluded from the search (no objective, no novelty contribution).
  val measurementOnlySources = mutableSetOf<String>()
  var feedbackAggregator: FeedbackAggregator? = null
  // Feedback from the just-finished iteration, cached here (not passed ad-hoc) so the CLI and JUnit
  // drivers, which evolve the scheduler at different moments, can't drift apart.
  var lastFeedback: IterationFeedback = IterationFeedback.EMPTY
  // Decision-time bridge for the Q-learning observedState abstraction; only ever read by a live
  // QLearningScheduler. Long-lived (one per run) so the per-iteration scheduler swap can't strand it.
  val lastObservedState = LastObservedStateHolder()
  val feedbackDebug = System.getProperty("fray.feedback.debug", "false").toBoolean()
  var nextSavedIndex = 0
  var currentIteration = 0
  val startTime = TimeSource.Monotonic.markNow()

  /** Build the aggregator once, after all sources are registered. No-op if already built. */
  fun initFeedback() {
    if (feedbackAggregator == null) {
      feedbackAggregator =
          FeedbackAggregator(
              feedbackSources.toList(), feedbackValuations.toMap(), measurementOnlySources.toSet())
    }
  }

  fun beforeIterationFeedback() {
    feedbackAggregator?.beforeIteration(currentIteration)
  }

  /** Collect feedback at the iteration-end seam and cache it for the next nextIteration(). */
  fun collectFeedback(bugFound: Throwable?) {
    val aggregator = feedbackAggregator ?: return
    val result = IterationResult(currentIteration, bugFound, report)
    lastFeedback = aggregator.aggregate(result)
    if (feedbackDebug) {
      val perSource =
          lastFeedback.signals.joinToString(", ") { "${it.sourceName}=${it.features.size}" }
      frayLogger.info(
          "[feedback] iter=$currentIteration objectives=${lastFeedback.objectives} " +
              "fitness=${lastFeedback.fitness} novel=${lastFeedback.isNovel} " +
              "newFeatures=${lastFeedback.newFeatures.size} thisRunBySource=[$perSource] " +
              "globalDistinct=${aggregator.totalFeaturesSeen()} " +
              "globalBySource=${aggregator.featuresSeenBySource()}",
          true,
      )
    }
  }

  /**
   * Fan a program-reported state value out to any registered [StateFeedbackSource]. Never throws --
   * feedback is best-effort and must not disturb the program under test.
   */
  fun observeState(value: Long) {
    // Publish the latest observed value for the Q-learning observedState abstraction before the
    // reward-side fan-out.
    lastObservedState.value = value
    feedbackSources.forEach { if (it is StateFeedbackSource) runCatching { it.observe(value) } }
  }

  fun saveToReportFolder(index: Int): Path {
    val path =
        if (exploreMode) {
          report / "recording_$index"
        } else {
          report / "recording"
        }
    path.createDirectories()
    (path / "scheduler").writeText(scheduler::class.java.name)
    (path / "random.json").writeText(Json.encodeToString<Randomness>(scheduler.rand))
    testStatusObservers.forEach { it.saveToReportFolder(path) }
    return path
  }

  val frayLogger = FrayLogger("$report/fray.log")

  init {
    if (!isReplay || !report.exists()) {
      prepareReportPath(report)
    }
    RacingOperation.resolveRacingOperationStackTraceHash = resolveRacingOperationStackTraceHash
    if (timelineCoverage != null) {
      scheduleObservers.add(timelineCoverage)
      testStatusObservers.add(timelineCoverage)
    }
    // Wire Fest to its coverage feedback source; it is carried forward across iterations.
    (scheduler as? FestScheduler)?.coverage = timelineCoverage
    if (System.getProperty("fray.recordSchedule", "false").toBoolean()) {
      val scheduleRecorder = ScheduleRecorder()
      testStatusObservers.add(scheduleRecorder)
      scheduleObservers.add(scheduleRecorder)
    }
    System.getProperty("fray.threadIdLog")?.let { logPath ->
      val threadIdentityLogger = ThreadIdentityLogger(Path.of(logPath))
      scheduleObservers.add(threadIdentityLogger)
      testStatusObservers.add(threadIdentityLogger)
    }
    System.getProperty("fray.resourceIdLog")?.let { logPath ->
      val resourceIdentityLogger = ResourceIdentityLogger(Path.of(logPath))
      scheduleObservers.add(resourceIdentityLogger)
      testStatusObservers.add(resourceIdentityLogger)
    }
    if (System.getProperty("fray.logPausingTime", "false").toBoolean()) {
      scheduleObservers.add(ThreadPausingTimeLogger(frayLogger))
    }
    val recordingPath = System.getProperty("fray.verifySchedule")
    if (recordingPath != null && isReplay) {
      val recordings =
          Json.decodeFromString<List<ScheduleRecording>>(File(recordingPath).readText())
      val verifier = ScheduleVerifier(recordings)
      scheduleObservers.add(verifier)
      testStatusObservers.add(verifier)
    }

    val debuggerProperty = System.getProperty(FRAY_DEBUGGER_PROPERTY_KEY, FRAY_DEBUGGER_DISABLED)
    if (debuggerProperty != FRAY_DEBUGGER_DISABLED) {
      if (debuggerProperty == FRAY_DEBUGGER_DEBUG || debuggerProperty == FRAY_DEBUGGER_MCP) {
        scheduler = FrayIdeaPluginScheduler(null)
      } else {
        assert(debuggerProperty == FRAY_DEBUGGER_REPLAY) {
          "Invalid value for $FRAY_DEBUGGER_PROPERTY_KEY: $debuggerProperty"
        }
        scheduler = FrayIdeaPluginScheduler(scheduler)
      }
      testStatusObservers.add(DebuggerRegistry.getRemoteScheduleObserver())
      iter =
          if (debuggerProperty == FRAY_DEBUGGER_MCP) {
            -1
          } else {
            1
          }
    }

    // `fray.feedback` (set by --feedback, or directly under JUnit) is the complete list of sources
    // driving a coverage-guided scheduler. Unset means no sources registered.
    val feedbackSpec = System.getProperty("fray.feedback")
    if (feedbackSpec != null) {
      registerFeedbackSpec(feedbackSpec)
    }
    // `--measure` / `fray.measure`: same grammar, but the newly-added sources are marked
    // measurement-only. Registered AFTER --feedback so a source named in both stays a search
    // objective (registerFeedbackSpec dedups, so it isn't re-added or marked here).
    val measureSpec = System.getProperty("fray.measure")
    if (measureSpec != null) {
      val before = feedbackSources.map { it.name }.toSet()
      registerFeedbackSpec(measureSpec)
      feedbackSources.forEach { if (it.name !in before) measurementOnlySources.add(it.name) }
    }
    if (feedbackSources.isNotEmpty()) {
      initFeedback()
    }

    injectObservedStateHolder()
  }

  /**
   * Parse a `fray.feedback` spec ("source[:k=v[,k=v]][,source...]") and register the named sources.
   * Unknown kinds are ignored; an explicit "none" registers nothing. Observer-sources are also added
   * to the observer lists.
   */
  private fun registerFeedbackSpec(spec: String) {
    if (spec.isBlank()) return
    spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
      val kind = entry.substringBefore(":").trim()
      val params =
          entry
              .substringAfter(":", "")
              .split(";")
              .mapNotNull { p ->
                val kv = p.split("=", limit = 2)
                if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
              }
              .toMap()
      when (kind) {
        "none" -> {}
        "state" -> {
          if (feedbackSources.none { it is StateFeedbackSource }) {
            feedbackSources.add(
                StateFeedbackSource(
                    target = params["target"]?.toLongOrNull(),
                    countHits = parseHits(params),
                ))
            recordValuation("state", params)
          }
        }
        "artifact" -> {
          val path = params["path"] ?: entry.substringAfter(":", "").substringBefore(";").trim()
          if (path.isNotEmpty()) {
            val artifact = ArtifactFeedbackSource(artifactPath = Path.of(path))
            feedbackSources.add(artifact)
            recordValuation(artifact.name, params)
          }
        }
        "handoff" -> {
          // Streaming-observer source: must be registered as a ScheduleObserver too, else it never
          // gets callbacks and collect()s an empty signal.
          if (feedbackSources.none { it is HandoffFeedbackSource }) {
            // The handoff bucket keys on stackTraceHash (schedule-stable code location, not the
            // unstable identityHashCode-based resource id), which is 0 unless resolution is forced on.
            // Cost: every RacingOperation walks its stack (StackWalker, no caching).
            RacingOperation.resolveRacingOperationStackTraceHash = true
            val handoff = HandoffFeedbackSource(countHits = parseHits(params))
            feedbackSources.add(handoff)
            scheduleObservers.add(handoff)
            testStatusObservers.add(handoff)
            recordValuation(handoff.name, params)
          }
        }
        "location" -> {
          // Streaming-observer source like handoff. Reads stackTraceHash, 0 unless resolution is
          // forced on; safe here since init{} runs before any iteration.
          if (feedbackSources.none { it is CodeLocationFeedbackSource }) {
            RacingOperation.resolveRacingOperationStackTraceHash = true
            val location = CodeLocationFeedbackSource(countHits = parseHits(params))
            feedbackSources.add(location)
            scheduleObservers.add(location)
            testStatusObservers.add(location)
            recordValuation(location.name, params)
          }
        }
        else -> {} // unknown kind: ignore, never fail the run
      }
    }
  }

  /**
   * Record the novelty valuation for a just-registered source from its `weight`/`decay`/`k` params.
   * No-op if none is present, so the source stays absent from [feedbackValuations] and uses
   * [NoveltyValuation.IDENTITY]. Lenient -- a bad value falls back to its default, never fails the
   * run. Once any knob is given the default curve is LOG; pass `decay=linear` to keep flat-linear.
   */
  private fun recordValuation(name: String, params: Map<String, String>) {
    val hasKnob = params.keys.any { it == "weight" || it == "decay" || it == "k" }
    if (!hasKnob) return
    val weight = params["weight"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
    val decay =
        when (params["decay"]?.lowercase()) {
          "linear" -> NoveltyValuation.Decay.LINEAR
          "log" -> NoveltyValuation.Decay.LOG
          "sqrt" -> NoveltyValuation.Decay.SQRT
          "satexp" -> NoveltyValuation.Decay.SATEXP
          null -> NoveltyValuation.Decay.LOG
          else -> NoveltyValuation.Decay.LOG
        }
    val k = params["k"]?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: NoveltyValuation.DEFAULT_K
    feedbackValuations[name] = NoveltyValuation(weight = weight, decay = decay, k = k)
  }

  /**
   * Parse the AFL-style hit-count flag. Defaults TRUE; only explicit `hits=false`/`0`/`no` opt out
   * to set-membership, so a typo degrades toward the richer signal rather than failing the run.
   */
  private fun parseHits(params: Map<String, String>): Boolean =
      when (params["hits"]?.lowercase()) {
        "false",
        "0",
        "no" -> false
        else -> true
      }

  fun elapsedTime(): Long {
    return (TimeSource.Monotonic.markNow() - startTime).inWholeMilliseconds
  }

  fun shouldRun(): Boolean {
    return elapsedTime() / 1000 <= timeout && currentIteration != iter
  }

  fun nextIteration() {
    currentIteration++
    scheduler = scheduler.nextIteration(randomnessProvider.getRandomness(), lastFeedback)
    // Scheduler instance is replaced each iteration, so re-inject the stable observeState bridge.
    injectObservedStateHolder()
  }

  /**
   * Point a live [QLearningScheduler] at this run's [lastObservedState] holder. Configuration-side
   * because it alone holds both the holder and the live scheduler. No-op for non-QL schedulers.
   */
  private fun injectObservedStateHolder() {
    (scheduler as? QLearningScheduler)?.let {
      it.observedStateHolder = lastObservedState
      it.refreshAbstraction()
    }
  }

  @OptIn(ExperimentalPathApi::class)
  fun prepareReportPath(reportPath: Path) {
    reportPath.deleteRecursively()
    reportPath.createDirectories()
  }
}
