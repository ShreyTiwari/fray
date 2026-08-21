# Grey-box Genetic Scheduling (Fray as a fuzzing platform)

This document covers the **coverage-guided genetic-algorithm (GA) scheduler** and the
**feedback layer** it is built on. It is the design record for an in-progress effort to
use Fray not just as a systematic concurrency tester, but as a grey-box fuzzer that
*learns* which interleavings are interesting and evolves toward them.

> **Status (first cut):** the end-to-end loop is implemented and was verified running via
> the CLI path (30 iterations against a deadlock workload — population grew, features
> accumulated, bug found). Feedback is **opt-in**: a coverage-guided scheduler is driven by
> whatever sources are registered via `--feedback` (e.g. value/state novelty, resource handoffs
> between threads, external file state); with no flag it runs with no novelty gradient. The
> architecture is deliberately built so that additional feedback forms (code coverage, true
> happens-before edges, external file state) drop in as new classes with no change to the
> scheduler. See
> [Caveats](#caveats--review-later) for everything that is intentionally simple or unproven.

---

## 1. Motivation & the grey-box model

Fray's existing schedulers (`Random`, `PCT`, `POS`, `SURW`) are **black-box** explorers:
they sample the interleaving space according to a fixed policy and never look at what the
execution *did*. A grey-box fuzzer instead closes a feedback loop — it observes some
signal from each run (coverage, novelty, …) and biases future runs toward inputs that grew
that signal. This is the AFL model, applied to **thread schedules** instead of byte inputs.

The mapping to a genetic algorithm:

| GA concept    | Here                                                                    |
| ------------- | ----------------------------------------------------------------------- |
| Genome (input)| A `List<Int>` — one gene per scheduling **choice point**                |
| Phenotype     | The concrete interleaving the genome decodes into during a run          |
| Fitness       | A per-source **objective vector** from feedback, Pareto-ranked (§11); summed to a scalar for reporting |
| Population    | A bounded archive of evaluated genomes (Pareto rank + crowding)         |
| Evolution     | selection + crossover + mutation, run at the **iteration boundary**     |

A key early realization shaped the whole design: **the GA feedback loop lives mostly at the
iteration boundary, not on the per-decision hot path.** During a run the scheduler is just
*decoding* an already-decided genome. The learning happens *between* runs in
`nextIteration`. That is why the interface change below touches `nextIteration` and not
`scheduleNextOperation`.

---

## 2. Where everything lives

```
core/src/main/kotlin/org/pastalab/fray/core/
  scheduler/
    Scheduler.kt                 # sealed interface — nextIteration() now takes feedback
    GeneticScheduler.kt          # the GA scheduler (sealed impl — must live here)
    genetic/
      Population.kt              # Individual (+ objective vector / rank / crowding) + bounded archive
      Operators.kt               # selection (crowded-comparison) / crossover / mutation / random genome
      Nsga.kt                    # NSGA-II-lite: Pareto dominance + non-dominated sort + crowding
      GeneticConfig.kt           # GA tuning knobs (caveat C2) — DEFAULT reproduces the old constants
  feedback/                      # the generic, reusable feedback layer (this dir)
    CoverageSignal.kt            # the common currency: Set<Long> features + optional score
    HitBucket.kt                 # AFL-style hit-count bucketing + (bucket, feature) packing (C3)
    NoveltyValuation.kt          # per-source novelty valuation: weight + saturation decay (C5)
    IterationResult.kt          # raw end-of-iteration facts (bug, index, report dir)
    IterationFeedback.kt        # aggregated, search-ready view (novelty + fitness)
    FeedbackSource.kt           # one feedback form, normalized to CoverageSignal
    FeedbackAggregator.kt       # owns sources + global feature union; computes novelty
    sources/
      StateFeedbackSource.kt          # value/state novelty (push-during-run, opt-in via --feedback)
      ArtifactFeedbackSource.kt       # external file state (pull-at-end, opt-in via --feedback)
      HandoffFeedbackSource.kt        # resource handoffs between threads (streaming, opt-in via --feedback)
      CodeLocationFeedbackSource.kt   # concurrency code-site coverage (streaming, opt-in via --feedback)
    README.md                    # ← you are here
```

Wiring (not new files, but edited):

- `command/Configuration.kt` — owns `feedbackSources`, `feedbackAggregator`,
  `lastFeedback`; registers the feedback sources named by `--feedback`; adds the
  `--scheduler genetic` CLI option (`class Genetic`).
- `TestRunner.kt` — CLI driver: collects feedback at the iteration-end seam.
- `junit/.../ConcurrencyTestRunExtension.kt` — JUnit driver: collects feedback in
  `afterEach`, delivers it on the next iteration.

> For a per-file breakdown — what each `.kt` is, its size, the role it plays in the
> pipeline, and its non-obvious gotchas — see [Appendix A: File-by-file reference](#appendix-a-file-by-file-reference).
> Appendix A also covers **why the folder has this many files** and whether any can be merged.

---

## 3. The two architectural decisions (read this first)

Almost everything else follows from two choices that were debated up front.

### 3a. Change `nextIteration`, **not** `scheduleNextOperation`

```kotlin
sealed interface Scheduler {
  fun scheduleNextOperation(
      threads: List<ThreadContext>,
      allThreads: Collection<ThreadContext>,
  ): ThreadContext                                              // UNCHANGED

  fun nextIteration(
      randomness: Randomness,
      feedback: IterationFeedback = IterationFeedback.EMPTY,    // NEW (defaulted)
  ): Scheduler
}
```

- `scheduleNextOperation` is on the **hot path** (~20 call sites in `RunContext`, fired at
  every scheduling decision). A GA needs nothing extra there per-decision — it just decodes
  its genome. Touching that signature would force edits to all 8 schedulers and every call
  site for no benefit.
- `nextIteration` is the **evolution hook**. Feedback belongs there. The other 7 schedulers
  ignore the new parameter and behave exactly as before. The **default value**
  (`IterationFeedback.EMPTY`) is what keeps every existing call site and test compiling
  untouched.
- Because `Scheduler` is a `@Serializable sealed interface`, the new
  `GeneticScheduler` is picked up by kotlinx polymorphism automatically — no serializer
  registration needed.

### 3b. The feedback layer is **separate** from both the scheduler and the observers

This was the single most important call. Two temptations were rejected:

1. **"Put the feedback logic inside the GA scheduler."** Rejected: the scheduler is
   `@Serializable` and the feedback machinery is pure runtime state. Embedding it would
   force scattered `@Transient` annotations and risk bloating / breaking `schedule.json`
   replay. The scheduler is a *consumer* of feedback; `Configuration` is the *producer*.
2. **"Reuse the existing observer interfaces"** (`ScheduleObserver` / `TestStatusObserver`).
   Rejected as the *primary* abstraction: those are RMI `Remote` interfaces that fire **per
   scheduling decision** and return `void`. That is the wrong shape and granularity for
   "read one artifact at the end of an iteration and return a value." (A streaming source —
   e.g. schedule novelty or happens-before edges — *can* still implement an observer to
   accumulate during the run, but that would be an implementation detail of that source, not
   the contract.)

So feedback is its own package with a one-way dependency:

```
scheduler/  ──imports──>  feedback/        (Scheduler.nextIteration takes IterationFeedback)
feedback/   ──must NOT──> scheduler/        (would create a cycle)
```

`feedback/` depends only on `core` (`ThreadContext`) and `rmi` (the observer interfaces, so
a streaming source can also be an observer). It never imports `scheduler/`.

---

## 4. The common currency: normalize everything to `Set<Long>`

The property that makes "add more feedback forms later" cheap is that **every source emits
the same thing**, so the GA never branches on where a signal came from.

```kotlin
data class CoverageSignal(
    val sourceName: String,
    val features: Set<Long>,   // hashed feature IDs → novelty detection
    val score: Double = 0.0,   // optional scalar → fitness ranking
)
```

Every conceivable source collapses into a feature set (the first four ship today; the rest
illustrate how new forms drop in unchanged):

| Source (lifecycle)              | A "feature" is…                                  |
| ------------------------------- | ------------------------------------------------ |
| Value/state *(push-during-run)* | a hashed program value reported via `observeState` |
| External file *(pull-at-end)*   | a hashed key from the file's contents            |
| Resource handoff *(streaming)*  | a `(resource, fromThread:op → toThread:op)` thread handoff |
| Code location *(streaming)*     | a `RacingOperation.stackTraceHash` (a concurrency-op `Class.method:line`) |
| Code coverage *(pull-at-end)*   | a basic-block / edge ID (true JaCoCo coverage — not yet shipped) |
| Happens-before *(streaming)*    | a `(write-loc, read-loc)` race edge              |

**Novelty** = "did the global union of features grow this iteration?" That is
source-agnostic, which is exactly why interleaving, code-coverage, and happens-before can be
added later as new `FeedbackSource` classes with **zero** changes to the aggregator or the
scheduler.

### The lifecycle split (why `FeedbackSource` looks the way it does)

Feedback forms divide cleanly by *when* they are available:

- **Streaming, in-process** (e.g. a future interleaving or happens-before source): produced
  incrementally *during* the run via observer callbacks. By iteration end they are already
  computed.
- **Push-during-run, in-process** (value/state): the program-under-test pushes values into the
  loop during the run via a side-channel (see `StateFeedbackSource`, §10).
- **Pull-at-end, out-of-process** (external file, code coverage dump): can only be read
  *after* the worker thread finishes; may be missing, partial, or corrupt; have I/O cost.

`FeedbackSource` is shaped to serve all of these:

```kotlin
interface FeedbackSource {
  val name: String
  fun beforeIteration(iteration: Int) {}                 // pull sources: clear stale artifact
  fun collect(result: IterationResult): CoverageSignal   // called once at iteration end
}
```

- A **streaming** source *also* implements `ScheduleObserver`/`TestStatusObserver`,
  accumulates during the run, and returns the snapshot from `collect()`.
- A **push-during-run** source (`StateFeedbackSource`) accumulates values handed to it via a
  side-channel during the run, resets in `beforeIteration()`, and snapshots in `collect()`.
- A **pull** source does its work in `collect()` and uses `beforeIteration()` to delete the
  previous iteration's artifact (so a target that fails to write isn't scored on stale data)
  and to hand the target an output path.

---

## 5. The flow, end to end

```
                      ┌─────────────────────────── one iteration ───────────────────────────┐

 GeneticScheduler.scheduleNextOperation()   ……decodes genome → concrete interleaving (the run)
        │                                                     │
        │                          configured FeedbackSource(s) accumulate features during the run
        ▼                                                     ▼
   run finishes  ──►  Configuration.collectFeedback(bug)  ──►  FeedbackAggregator.aggregate()
                                                                 │  computes newFeatures, per-source objectives
                                                                 ▼
                                                        Configuration.lastFeedback
                                                                 │
 GeneticScheduler.nextIteration(rand, lastFeedback)  ◄──────────┘
        │   record(genome, objectives) → rankAndCrowd → select → crossover → mutate
        ▼
   next GeneticScheduler (carries population forward)            └──────────── repeat ─────────┘
```

### Driver seams — and the gotcha they solve

The CLI and JUnit drivers **evolve the scheduler at different moments**, which is a real
trap. The design routes both through `Configuration.lastFeedback` so they cannot drift:

- **CLI** (`TestRunner.run`): iteration end is clean. After the worker joins:
  `endIteration()` → `collectFeedback(bug)` → `nextIteration()` (reads `lastFeedback`) →
  `beforeIterationFeedback()`.
- **JUnit** (`ConcurrencyTestRunExtension`): `nextIteration` is called at the **start of the
  next** iteration (in `interceptTestClassConstructor`), while iteration end is in
  `afterEach`. So `afterEach` calls `collectFeedback(bug)` and *caches*; the next
  iteration's constructor hand-off reads `config.lastFeedback`.

`collectFeedback` / `beforeIterationFeedback` are **no-ops** unless a feedback-driven
scheduler registered sources, so non-GA schedulers pay nothing.

---

## 6. Running it

The scheduler *selection* is a one-liner on either path; the non-obvious requirement is that
**both paths must run under Fray's instrumented JDK + agent** (the generated
`build/.../bin/fray/bin/java`), never a plain `java`.

- **CLI** — runs a *target program* (not a JUnit method):
  `--scheduler genetic` (plus `--iter`, `-o`, and a `--run-config` describing the target).
  **This is the path verified working.**
- **JUnit** — runs a specific test method:
  `@ConcurrencyTest(scheduler = GeneticScheduler::class, iterations = 1000)`.
  The wiring is in place (and the explicit no-arg `constructor()` on `GeneticScheduler`
  exists precisely so the reflective instantiation works), **but this path has not yet been
  executed under instrumentation** — see caveats.

---

## 7. Caveats — REVIEW LATER

These are all marked `CAVEAT (review later)` in the code. They are intentional shortcuts to
get a working loop; each is a place the design expects to grow. **None of them are bugs** —
they are scoped-out decisions.

### C1. Positional genome encoding → schedule drift
*(`GeneticScheduler.kt`, `Operators.kt`)*
A genome is a `List<Int>`; choice point *i* picks `genome[i] % enabledCount`. This is the
simplest thing that works, but the chosen thread depends on **how many threads happen to be
enabled** at that point, so a mutation upstream that changes the enabled set **shifts the
meaning of every gene after the first divergence**. Crossover/mutation are therefore noisier
than they look. *Planned fix:* a **choice-point-keyed** genome (`Map<choicePointId,
preference>` keyed by a stable program location) so a gene means "at this program state,
prefer this thread" and survives reordering. This was discussed as the right long-term
encoding; positional was chosen to validate the loop first.

### C2. Magic numbers everywhere — *addressed*
*(`GeneticConfig.kt`, `GeneticScheduler.kt`, `Operators.kt`, `Population.kt`)*
The six GA tuning constants — tournament size (3), mutation rate (0.1), genome length (256),
`geneBound` (`1<<16`), `seedPopulation` (5), and population cap (50) — were scattered across the
scheduler, operators, and population. They are now one `GeneticConfig` bundle whose `DEFAULT`
reproduces the original constants exactly, so an unconfigured run is byte-for-byte unchanged.
(The seventh item that used to live here, "novelty weight (1.0)", moved to **C5** and is configured
via `--feedback`.)

`GeneticConfig` is held on a **`@Transient`** field of `GeneticScheduler` (like `population` and
`rand`): it is tuning/search state, never serialised, so `schedule.json` stays just the genome and
**replay is unaffected** — a recorded genome replays the same interleaving regardless of how the
search was tuned. Pinned by `GeneticSchedulerSerializationTest`.

Two delivery channels (mirroring `--feedback` / `fray.feedback`):
- **CLI:** `--genetic-tournament-size`, `--genetic-mutation-rate`, `--genetic-genome-length`,
  `--genetic-gene-bound`, `--genetic-seed-population`, `--genetic-population-cap` (each nullable;
  absent ⇒ fall back to the system-property base ⇒ `DEFAULT`).
- **JUnit** (reflective no-arg construction, so flags can't reach it): the matching `fray.genetic.*`
  system properties, read by `GeneticConfig.fromSystemProperties()`.

All inputs pass through `GeneticConfig.sanitized()` (clamps to usable ranges), so a bad value
degrades instead of producing an empty population or a divide-by-zero. `geneBound` only needs to
comfortably exceed the max number of concurrently-enabled threads (genes are reduced mod
`enabledCount`).

*Still future work:* the values are configurable but not yet **adaptive** (e.g. self-tuning mutation
rate as the population converges); that remains open.

### C3. `Set<Long>` currency has no hit-count sensitivity — *addressed*
*(`HitBucket.kt`, `sources/StateFeedbackSource.kt`, `sources/HandoffFeedbackSource.kt`,
`sources/CodeLocationFeedbackSource.kt`)*
A feature used to be binary — seen or not, no "this edge was hit 7 times vs once" — discarding the
frequency signal AFL gets so much mileage from (a lock site hammered 200 times can expose races a
once-touched site never will). Fixed exactly the way the caveat sketched: **the currency stays
`Set<Long>` and the aggregator / `NoveltyValuation` / scheduler don't change at all.**

The during-run sources now *count* occurrences per raw feature during a run, then at `collect()`
fold the count into a coarse AFL-style **bucket** and pack `(bucket, rawFeature)` into the feature
`Long` (`HitBucket`). So "site X in bucket 2" and "site X in bucket 6" are two **distinct** features —
the global union grows when a feature is hit in a *new frequency bucket*, which is precisely AFL's
behaviour. The genetic search just sees more distinct `Long`s (pinned end-to-end by
`FeedbackAggregatorTest.hitCountBucketsCountAsNoveltyAcrossIterations`).

- **Bucketing is logarithmic** (`bucket(n) = floor(log2 n) + 1`, capped at 15): coarse on purpose, so
  47 vs 48 hits is noise (same bucket) but 2 vs 200 is signal (several buckets apart).
- **Bit layout:** the aggregator reserves the high 8 bits per source, leaving a 56-bit payload;
  hit-count mode carves it into `[ bucket : bits 52–55 | rawHash : bits 0–51 ]`. `location` only uses
  32 hash bits (unaffected); `handoff`/`state` drop from 56 to 52 raw bits — a negligible collision
  bump, documented in `HitBucket`.
- **On by default** (opt out with `--feedback <kind>:hits=false`). Bucketing is the default because
  the frequency signal is the point of C3; `hits=false` falls back to the exact pre-C3
  set-membership encoding, which a regression test still pins for debugging / A-B comparison. This
  means a fresh search no longer reproduces the pre-C3 encoding *by default* — a deliberate product
  call — but the old behaviour stays one flag away, and **replay of a recorded `schedule.json` is
  unaffected regardless** (replay decodes a fixed genome, never re-runs the sources). Scope is the
  three **during-run** sources (state / handoff / location); **artifact** is excluded — its
  pull-at-end "hash a file once" lifecycle has no meaningful per-feature hit count.
- **Determinism:** counting is a pure function of the schedule, bucketing/packing are pure, nothing
  touches the genome — replay is unaffected. `StateFeedbackSource` counts on worker threads, so it
  uses an atomic `ConcurrentHashMap.merge`.

*Relation to C5:* orthogonal, as promised — C5's valuation reads only the *count of new features*,
never their identity, so hit-count features flow through `U`/weight unchanged. The two compose
(`--feedback location:decay=log` already gets bucketing for free).

### C4. Minimal population model — *addressed*
*(`Population.kt`, `GeneticScheduler.kt`, `GeneticConfig.kt`, `Nsga.kt`)*
Two layers. First (earlier work): the archive ranks by **Pareto dominance + crowding distance**
(NSGA-II-lite, §11), so selection and eviction preserve a novelty-vs-fitness front and its trade-off
diversity — the "no Pareto front / no crowding" gap. Second (this change): **stagnation-triggered
restart with elitism**, which closes the "no restart / no age-out" half.

The mechanism, in `GeneticScheduler.nextIteration` (which already receives `feedback.isNovel`):
- A `@Transient stagnationCounter` (carried across iterations like `population`) counts **consecutive
  non-novel iterations**; any novel iteration resets it.
- At `config.restartStagnation` consecutive non-novel iterations the search has likely converged, so
  it **restarts**: `Population.restartKeepingFront()` keeps the current Pareto front (rank 0) as
  **elite** and flushes the rest, then a short **reseed burst** (`reseedRemaining`) injects fresh
  random genomes via the existing seeding path.

This gives the search a real **escape from premature convergence** (the one diversity source was
otherwise the initial seeding), and the flush is the **implicit age-out**: it clears stale members
whose novelty objectives were *frozen at evaluation time* against then-sparse coverage (see §11 /
the C5 verification) and would otherwise sit on the front forever, re-bred but unproductive. Elitism
means a restart never loses the best discoveries.

- **On by default**, threshold `GeneticConfig.DEFAULT_RESTART_STAGNATION = 50` (a heuristic ≈ the
  population cap — enough room to breed from the archive before declaring stagnation, but
  **unvalidated**; configurable so it can be tuned once measured). `--genetic-restart-stagnation=N`
  / `fray.genetic.restartStagnation`; **`0` disables** it and reproduces the pre-C4 single-flat-archive
  behaviour exactly (pinned by a test). So a fresh search now restarts by default — a deliberate
  product call — while the old loop is one flag away.
- **Replay-safe / deterministic:** all restart state is `@Transient` (never serialised, carried by
  hand); the genome and `schedule.json` are untouched, so replay of a recorded failure is unaffected.
  Restart only changes which genomes a *fresh search* explores.
- **No-feedback degeneration (benign):** with no `--feedback` source, `isNovel` is always false, so a
  restart-enabled run trips every `restartStagnation` iterations. Harmless — objectives are 0-dim, so
  keeping the "front" retains the whole (cap-bounded) archive and the only effect is a periodic
  fresh-random reseed, which if anything helps a feedback-less genetic run.

*Still deferred (honestly):* continuous **random immigrants** (replace a fraction every generation)
and direct **per-individual age-decay** of the frozen novelty objective. The latter would fix the
stale-objective problem at its source but is invasive — it mutates the vector `Nsga` ranks on and
risks the determinism/Pareto guarantees — so the restart-with-elitism flush was chosen as the
high-value, low-risk move instead.

### C5. Flat novelty weighting — *addressed*
*(`FeedbackAggregator.kt`, `NoveltyValuation.kt`)*
The old objective was `source.score + |newFeatures_for_source| * 1.0` — flat, linear, no decay.
It is now a per-source **marginal-utility** valuation (`NoveltyValuation`, configured per source via
`--feedback`):

```
objective_s = score_s + weight_s * ( U(C_after) − U(C_before) )
```

where `C_before` is the source's cumulative distinct-feature count before this iteration's fold,
`C_after = C_before + newForSource`, and `U` is a concave, non-decreasing utility with `U(0)=0`
(default `U = ln(1+C)`; `U(C)=C` recovers the old flat-linear objective exactly). One concave `U`
delivers saturation-over-time, within-iteration diminishing returns, and exact back-compat at once.
See §11 for the full treatment.

**The honest part — what actually steers the search:**
- **Saturation decay (keyed on cumulative coverage) is the real lever.** Because it is
  history-dependent and non-linear, it re-orders Pareto fronts (`Nsga.dominates`) and reshapes
  crowding, biasing the GA toward still-productive sources.
- **`weight` mostly does *not* steer.** The GA ranks on the objective *vector* via Pareto dominance
  + crowding, both invariant under a positive per-axis scale (dominance is sign-only; crowding
  divides by each axis's own range). So for a pure-novelty source (`score=0`) `weight` changes only
  the reported scalar `fitness`, not selection/eviction. It becomes selection-relevant *only* when a
  source emits a nonzero `score` (e.g. `state:target=N`), where it multiplies the novelty term but
  not the score — an affine, non-uniform transform that can flip dominance. `weight` is therefore a
  reporting / score-interaction knob, **not** the multi-objective steering mechanism.

*Relation to other caveats:* orthogonal to **C3** (the valuation reads only counts, never feature
identity, so hit-count buckets drop in unchanged); a partial, incidental softener for **C4** (a
saturated source loses objective advantage organically) but **not** a substitute for archive
age-out/restart. The `Nsga` math is deliberately untouched — steering comes from the valuation, not
the ranker.

### C6. JUnit path is wired but **unproven**
*(`GeneticScheduler.kt` no-arg ctor, `ConcurrencyTestRunExtension.kt`)*
The CLI loop was verified end-to-end; the JUnit `@ConcurrencyTest` path compiles and is
logically complete, but was **not** executed under the instrumented JDK. Note the
`@ConcurrencyTest` tests do **not** run via the plain `:junit:test` task (it runs 0 of them —
they need the instrumented `bin/fray/bin/java` launcher).

---

## 8. Invariants to preserve when extending

- **Serialization / replay:** keep the **genome** `@Serializable` (a bug-triggering schedule
  must save to `schedule.json` and replay). Keep the **population, aggregator, sources, and
  `globalCoverage`** `@Transient` / runtime-only. `IterationFeedback` / `CoverageSignal` are
  transient runtime params and need no serializer.
- **`feedback/` must not import `scheduler/`** — `Scheduler` imports `IterationFeedback`;
  importing back creates a cycle.
- **A `FeedbackSource.collect()` must never throw** — degrade to an empty signal. The
  aggregator guards each source with `runCatching`, but sources shouldn't rely on it.
- **Determinism:** any randomness in the GA must be drawn from Fray's `Randomness`, like the
  other schedulers, or replays diverge. The only exception is `GeneticScheduler.rand` used to
  *extend* the genome mid-run — and even that is safe because the extended genes are persisted
  into the (serialized) genome itself.

---

## 9. Adding a new feedback source (the payoff)

To add, say, code coverage:

1. Create `feedback/sources/CodeCoverageFeedbackSource.kt implementing FeedbackSource`.
   - `beforeIteration()`: set the JaCoCo output path / clear last iteration's `.exec`.
   - `collect()`: read + parse the dump after the run, emit covered edge IDs as
     `CoverageSignal(name, edgeIds)`. Return an empty signal on any failure.
2. Register it in `Configuration.registerFeedbackSpec` as a new `--feedback` kind, next to
   `state` and `artifact`.

That's it — no change to `FeedbackAggregator`, `IterationFeedback`, `GeneticScheduler`, or
the `Scheduler` interface. Novelty and fitness automatically incorporate the new features.

---

## 10. Built-in feedback sources, and the `--feedback` flag

Four sources ship today — value/state, artifact, resource-handoff, and code-location — plus an
opt-in registration flag so a program can enable them **without editing `Configuration`**.

### Value/state feedback (`StateFeedbackSource`) — in-process, push-during-run

A third source lifecycle (besides streaming-observer and pull-at-end): the program-under-test
**pushes** state values into the feedback loop during the run, via
`org.pastalab.fray.runtime.Runtime.observeState(long)` (or the `String` overload). It scores the
*program state* the schedule produced rather than the schedule shape, so a coverage-guided
scheduler can be rewarded for driving the program into states it has not seen before (the OOPSLA
2020 "calculator coverage" example).

- `Runtime.observeState` is a **pure side-channel**: `RuntimeDelegate.observeState` deliberately does
  NOT enter the scheduling path (no `runInFrayStart*`, no context op), so reporting a value never
  becomes a choice point, never perturbs the interleaving, and never affects `schedule.json` replay.
- `StateFeedbackSource.observe` is called from arbitrary worker threads, so it uses a **concurrent**
  set (a streaming-observer source whose callback is single-threaded inside the scheduler could use
  a plain set; this one cannot).
- An optional `target` makes it also emit a proximity `score`, adding a fitness gradient toward a
  specific value (e.g. a bug-triggering one). Without a target it is a pure novelty source.

### Artifact feedback (`ArtifactFeedbackSource`) — pull-at-end

The canonical pull-at-end source for genuinely out-of-process state: `beforeIteration` hands the
target an output path (system property) and deletes the stale file; `collect` reads + chunk-hashes
the file into features, degrading to an empty signal on any failure. Prefer `StateFeedbackSource`
for in-process state (no I/O); this is for real out-of-process artifacts (e.g. a JaCoCo dump).

### Resource-handoff feedback (`HandoffFeedbackSource`) — streaming-observer

Rewards the scheduler for discovering new ways a shared resource is passed between threads. It is
the **feedback dual** of `observers/ResourceOrderingCoverage`: that observer folds a whole run into
ONE fingerprint and counts distinct fingerprints (a scalar *metric*); this source emits **each
handoff as its own feature** so the GA gets a per-edge novelty *gradient*. Same derivation, inverted
output.

- **What a handoff is.** On each scheduled `RacingOperation` (a shared-memory read/write or a lock
  acquire), it pairs the *previous distinct accessor* of that resource with the current one into a
  directed `Handoff(from, to)` — "the resource moved from A to B". The op type (`MEMORY_READ` /
  `MEMORY_WRITE`) of **both** endpoints is part of the handoff, so a write→read handoff (genuine
  communication — B reads what A wrote) is a different feature from read→read. The internal model is
  deliberately readable: `handoffsByResource: Map<resource, Set<Handoff>>`; the `Long` a feature
  ultimately needs is just an FNV-1a hash of a human-readable key (`r=123 2:MEMORY_WRITE ->
  3:MEMORY_READ`), masked to the low 56 bits — no hand-packed bitfields.
- **Streaming-observer lifecycle.** It implements `ScheduleObserver` (accumulating in
  `onNewSchedule` during the run) **and** `FeedbackSource` (snapshotting in `collect`). The
  accumulator is a plain (non-concurrent) set because `onNewSchedule` fires only on the single
  scheduled thread inside the scheduler's critical section — unlike `StateFeedbackSource.observe`,
  which is called from arbitrary worker threads. Per-iteration reset is in `beforeIteration`.
- **Thread-keyed, for now.** A handoff endpoint is identified by the acting thread's `index`. The
  single seam `accessId()` is the *only* thing that differs from a future **location-keyed** variant
  (key by `op.stackTraceHash` so a feature means "this code-location handoff pattern occurred",
  stable across thread renumbering — at the cost of forcing
  `RacingOperation.resolveRacingOperationStackTraceHash` on, the way `THREAD_ORDERING` timeline
  coverage does). Switching is a one-line change to `accessId()`. **`index` is not a stable
  cross-schedule identity** (it is birth order, which the scheduler varies), so this thread-keying
  is a known weakness for feedback that compares across interleavings — location-keying is the
  recommended fix. See [`thread-identity.md`](thread-identity.md) (this folder) for the full analysis
  and why a "creation-site + counter" thread id does *not* work.
- **The resource bucket had the *same* weakness — now fixed.** A handoff feature is
  `loc=<bucket> <from> -> <to>`. The endpoints (`from`/`to`) are thread spawn-paths (above); the
  *bucket* used to be the raw `op.resource` int, which is `System.identityHashCode`-derived and **not
  schedule-stable** (the same logical lock gets a fresh id every iteration), so the *same* feature named
  a *different* resource run-to-run — the resource-axis twin of the W1 false-merge. The bucket is now
  keyed on the access code location (`HandoffFeedbackSource.resourceBucket(op)` = `op.stackTraceHash`),
  which is schedule-stable, with a documented over-merge cost (two distinct resources touched at the
  same source line collapse). The endpoints stay thread-keyed, so the self-access guard still compares
  threads. See [`resource-identity.md`](resource-identity.md) (this folder) for the full analysis and
  why there is no clean "spawn-path of a resource".
- **Not true happens-before.** A handoff is inferred from access *sequence* — Fray exposes no
  release/unlock event to observers — so it is temporal adjacency on a resource, not a verified
  release→acquire / read-from causality edge. It captures the data-race-relevant pairs the scheduler
  should be steered toward at zero new-instrumentation cost; a precise vector-clock HB source remains
  separate future work. The name says "handoff", not "happens-before", on purpose.

### Code-location feedback (`CodeLocationFeedbackSource`) — streaming-observer

Rewards the scheduler for driving execution to **concurrency code-sites it has not reached before**.
At each scheduled `RacingOperation` it reads `op.stackTraceHash` — a hash of up to two user-code stack
frames (`Class.method:line`) — and accumulates the set of distinct hashes; each is a feature, so
novelty = "reached a new concurrency code location this run".

- **Not true basic-block coverage.** Fray only instruments concurrency operations, so this sees the
  source lines of locks / monitors / wait / notify / volatile (and, under `-m`/`--memory`, ordinary
  field & array) accesses — **not** every executed line/branch/block. It is an in-process
  *approximation* of code coverage at concurrency-operation granularity. Genuine basic-block/edge
  coverage would need a separate JaCoCo source; the name is `location` precisely to leave `coverage`
  free for that.
- **Prerequisite — it forces `stackTraceHash` on.** `RacingOperation.stackTraceHash` is `0` unless
  `RacingOperation.resolveRacingOperationStackTraceHash` is `true` (it has a per-op cost, so it's off
  by default — only SURW / thread-ordering enable it otherwise). The `location` registration arm sets
  that static to `true`; this is safe because the arm runs in `Configuration.init{}` *after* the
  static's normal assignment and *before* any iteration. Hashes of `0` are skipped defensively.
- **`-m`/`--memory` widens it.** By default only lock/volatile sites are concurrency operations; with
  `--memory`, ordinary field and array accesses are too, so far more code locations become visible and
  coverage gets much richer.
- **Different from `handoff`, despite shared machinery.** Handoff reads the *threads* (`from→to`) and
  is blind to the code line; this reads the *code line* and is blind to thread identity and order. The
  same program can make one rich and the other barren, so they measure different things and combine
  cleanly (`--feedback handoff,location`). Features are opaque ids — the readable `Class.method:line`
  string isn't recoverable here (the hash is precomputed in `RacingOperation`).

### Cross-source feature **namespacing** (correctness)

Once more than one source is active, their raw feature `Long`s could numerically collide in the
global union and mask each other's novelty. `FeedbackAggregator` now reserves the **high 8 bits** of
every feature in `globalCoverage` as a per-source tag (`namespaced(tag, feature)`), so sources are
disjoint by construction. The contract: **sources must keep their raw feature payload within the low
56 bits** (`StateFeedbackSource`, `ArtifactFeedbackSource`, and `HandoffFeedbackSource` mask to 56
bits; `CodeLocationFeedbackSource` masks its 32-bit hash to 32 bits, comfortably within 56). The
per-source `signals` on `IterationFeedback` stay raw, and per-source reporting
(`featuresSeenBySource()`) accumulates raw features (no cross-source mixing, so no namespacing
needed there).

### Enabling sources: the `--feedback` flag / `fray.feedback` property

`--feedback` (which sets the `fray.feedback` system property that `Configuration.init{}` parses —
one mechanism that works on both the CLI and in-JVM/JUnit paths) is the **single source of truth**
for which feedback forms drive a coverage-guided scheduler:

- **When set, it fully determines the source list.** So you can give a scheduler exactly the
  feedback you want and nothing else.
- **When omitted**, no feedback sources are registered, so the genetic scheduler runs with no
  novelty gradient (until you pass a `--feedback` spec).

```
--feedback state                 # value/state novelty only
--feedback state:target=4999     # value novelty + proximity score toward 4999, only
--feedback artifact:path=/tmp/x  # pull-at-end file hashing only
--feedback handoff               # resource handoffs between threads only
--feedback location              # concurrency code-site coverage only
--feedback state,handoff         # BOTH value/state and resource-handoff feedback
--feedback handoff,location      # BOTH resource-handoff and code-location feedback
--feedback state,artifact:path=/tmp/x  # BOTH value/state and artifact feedback
--feedback none                  # register no feedback sources
```

The CLI also prints a per-source coverage summary at end of run ("Distinct feedback features" + a
per-source breakdown), so you can compare how much of the state space each scheduler explored.

### Measure vs. feedback: the `--measure` flag / `fray.measure` property

`--feedback` sources do **two** jobs: they are *measured* (their distinct features are counted and
reported) **and** they are *optimized* (each becomes one NSGA-II objective; its novelty grows the
global union that flips `isNovel` and resets the C4 restart counter). Sometimes you want to measure
a coverage form **without** letting it steer the search — e.g. search on value/state coverage alone
but still plot handoff and code-location coverage for the same run.

`--measure` (system property `fray.measure`, same source grammar as `--feedback`) registers
**measurement-only** sources. They are collected every iteration and appear in the per-source report
and the `globalBySource={…}` feedback-debug trace (so they are plottable), but the aggregator keeps
them out of the objective vector, the global feature union, and `newFeatures`/`isNovel`. So a
coverage-guided scheduler ranks and restarts **exactly** as if they weren't registered.

```
--feedback state --measure handoff,location   # search on STATE; also plot handoff + location
--feedback state,handoff --measure location    # search on state+handoff; also plot location
```

Precedence: a source named in **both** stays a search objective (`--feedback` wins) — `--measure`
never demotes a feedback source. C5 valuation knobs (`weight`/`decay`/`k`) on a `--measure` entry are
ignored, since they only shape objectives. Implementation: `Configuration.init{}` registers
`--feedback` first, then diffs the source list around the `--measure` registration to mark exactly
the newly-added names as measurement-only, and hands that set to `FeedbackAggregator`.

---

## 11. Multi-objective feedback (Pareto / NSGA-II-lite)

When more than one source is active, the aggregator keeps the **per-source objective vector**
rather than collapsing it to one number, and the GA ranks genomes by **Pareto dominance plus
crowding distance** (NSGA-II-lite). This is what lets two genuinely incommensurable feedback
forms — say final file-system state AND concurrency code-location coverage — be optimized as
**independent objectives with trade-offs**, instead of being summed into a single scalar that
can't tell a specialist from a generalist.

### The objective vector

`FeedbackAggregator.aggregate` now emits, alongside the (still-computed) global novelty union, an
ordered `objectives: Map<String, Double>` carried on `IterationFeedback`:

```
objective[source] = source.score + weight_s * ( U(C_after) − U(C_before) )
```

(With the default per-source valuation `NoveltyValuation.IDENTITY` — `weight=1`, `U(C)=C` — this is
exactly the historical `source.score + |newFeatures_for_that_source| * 1.0`. See the C5 subsection
below.)

- One dimension **per registered source**, in registration order (so `IterationFeedback
  .objectiveVector()` is a stable dense `DoubleArray` for the whole run).
- Per-source novelty is each source's own feature growth (computed against the existing
  `perSourceCoverage` bookkeeping), so `--feedback location,handoff` yields a 2-vector
  `[newLocations, newHandoffs]` and the two never mask each other.
- The scalar `fitness` is retained as `Σ objective` — for disjoint (namespaced) sources this is
  exactly the historical `Σ score + |newFeatures| * noveltyWeight`, so it stays a faithful
  single-number summary for logs/reporting. **The GA ranks on the vector, not on `fitness`.**
- **Single source ⇒ 1-D vector ⇒ dominance is plain numeric `>`**, so single-source runs behave
  exactly as they did before this machinery existed. No regression.

### How the GA ranks it (`scheduler/genetic/Nsga.kt`)

`Nsga` is a pure, deterministic object (no randomness — so replays stay bit-identical):

1. **Non-dominated sorting → `Individual.rank`.** A dominates B iff A is ≥ B on every objective
   and > B on at least one. Front 0 = the Pareto front (dominated by nobody); front *k* =
   dominated only by fronts `< k`. Lower rank is better.
2. **Crowding distance → `Individual.crowding`.** Within a front everything is mutually
   non-dominated, so ties are broken by diversity: per objective, sort the front and sum the
   normalised neighbour gap; the extreme points get +∞ so the frontier's ends are always kept.
3. **Crowded-comparison order** (`crowdedBetter` / `crowdedComparator`): lower `rank` wins; on a
   tie, higher `crowding` wins. Used for **both** tournament selection (`Operators.select`) and
   population truncation (`Population.record`).

Pareto-aware **eviction** is the load-bearing half: a scalar-sum eviction would discard exactly
the strong-in-one-objective genomes the multi-objective search exists to keep, so `Population`
truncates by `crowdedComparator`, not by `fitness`.

### Novelty valuation: weight vs. decay (C5)

The flat `noveltyWeight = 1.0` is gone. Each source now carries a `NoveltyValuation`
(`feedback/NoveltyValuation.kt`), and the per-source novelty term is a **marginal utility**
`weight_s * ( U(C_after) − U(C_before) )`, where `C` is the source's cumulative distinct-feature
count and `U` is a concave curve selectable per source (`linear | log | sqrt | satexp`, default
`log`). `U(C)=C` with `weight=1` (`NoveltyValuation.IDENTITY`, the default for any source with no
C5 knobs) reproduces the old objective byte-for-byte.

The non-obvious bit — and a correction to the earlier note here that per-source weights are "future
work independent of the Pareto machinery" — is **which knob actually steers the multi-objective
search**:

- **`weight` is largely Pareto-inert.** Dominance (`Nsga.dominates`) is a per-axis *sign*
  comparison, and crowding (`Nsga.assignCrowding`) divides each axis's neighbour gaps by that axis's
  own *range* — so a constant positive per-axis scale cancels in both. For a pure-novelty source
  (`score = 0`), changing `weight` therefore changes **only** the reported scalar `fitness = Σ
  objectives`, never selection or eviction. The single exception: when a source emits a nonzero
  `score` (e.g. `--feedback state:target=N`), `weight` multiplies the novelty term but **not** the
  score, making the objective an affine *non-uniform* transform that **can** flip dominance. So
  `weight` is honestly a reporting / score-interaction knob.
- **Saturation `decay` is the real steering lever.** It is *history-dependent* (the same
  `newForSource` is worth more early than late, because it is read against cumulative coverage) and
  *non-linear*. History-dependence re-orders Pareto fronts (changes dominance); non-linearity
  reshapes crowding. A *fixed* per-axis monotone remap (e.g. `ln(1+newForSource)` of just this
  iteration's count) would change crowding tie-breaks but **not** dominance — which is exactly why
  the valuation keys `U` on cumulative `C`, not on the per-iteration count.

These properties are pinned by `scheduler/genetic/NsgaTest.kt` so a future refactor can't silently
break the reasoning. The `Nsga` math itself is **unchanged** — the steering comes from the
valuation, not the ranker.

**Determinism / replay.** The valuation is a pure function; the aggregator stays
deterministic-but-side-effecting (it mutates the coverage sets) and entirely transient — none of it
is serialised. **Replay of a recorded genome is unaffected** (decoding reads only the `@Serializable`
genome, never feedback). What C5 *does* change is which genomes a *fresh search run* produces — that
is the point of the feature, and run-to-run search reproducibility was never in scope.

**Still future work:** per-source weighting that genuinely steers a pure-novelty axis would require
scalarizing into a dimension the ranker consumes (out of scope); adaptive/auto-tuned `decay`; and
hit-count sensitivity (**C3**) remains orthogonal.

---

## Appendix A: File-by-file reference

This appendix is the maintainer's map of the `feedback/` package: what each file is, how big it
is, where it sits in the pipeline, and the non-obvious facts that bite you when you change it. The
sections above explain the *design*; this one explains the *files*. Every claim here was checked
against the source (line numbers omitted on purpose — they rot; the surrounding facts don't).

### Why are there this many files?

Ten files (this README + nine `.kt`) feels like a lot for one feature, so it's worth being
explicit about what's load-bearing and what's just fragmentation.

The package separates concepts along **one axis: position in the data flow**. Reading top to bottom
is reading one iteration's feedback from production to consumption:

```
  a source emits ──►  CoverageSignal      (per-source output: features + optional score)
  the run produces ─► IterationResult     (aggregator input:  iteration, bug, report dir)
  the engine runs ──► FeedbackAggregator  (engine: unions features, computes novelty + per-source objectives)
  the scheduler gets ►IterationFeedback   (scheduler input:   result + signals + newFeatures + objectives + fitness)
  the contract ─────► FeedbackSource      (interface every source implements)
  the sources ──────► sources/State…, Artifact…, Handoff…, CodeLocation…  (the four concrete forms that ship)
```

That gives **three genuinely distinct responsibilities** that each earn their own file:

- **`FeedbackSource.kt`** — the *contract*. The whole "add a feedback form without touching the
  scheduler" payoff (§9) rests on this staying a small, stable interface.
- **`FeedbackAggregator.kt`** — the *engine*. ~85 lines of real logic (namespacing, novelty diff,
  per-source objective vector, fitness, per-source bookkeeping, `runCatching` guards). This is the
  only file here with behaviour worth a unit test.
- **`sources/`** — the *implementations*. Kept in a subfolder precisely so "the layer" (contract +
  engine + value types) reads separately from "the plug-ins."

The remaining **three files are one-screen value types** — `CoverageSignal` (~27 lines),
`IterationResult` (~19), `IterationFeedback` (~38). That *is* over-fragmentation: each is a single
`data class` with no behaviour. They are split one-per-file by convention, not necessity. See
[Can any of these be merged?](#can-any-of-these-be-merged) below — the honest answer is "these three
could share a file; the two you'd most expect to merge should not."

### The files

| File | ~Lines | Pipeline role | One-liner |
| --- | --- | --- | --- |
| `CoverageSignal.kt` | 27 | per-source **output** | One source's result for one iteration: `sourceName`, `features: Set<Long>`, optional `score`. |
| `IterationResult.kt` | 19 | aggregator **input** | End-of-iteration facts a source can't compute itself: `iteration`, `bugFound`, `reportFolder`. |
| `IterationFeedback.kt` | 55 | scheduler **input** | Aggregated, search-ready view: wraps `result` + `signals` + `newFeatures` + per-source `objectives` (the GA's Pareto vector) + scalar `fitness`; derives `isNovel`. |
| `FeedbackSource.kt` | 38 | the **contract** | Interface: `name`, `beforeIteration()`, `collect(result) → CoverageSignal`. |
| `FeedbackAggregator.kt` | 75 | the **engine** | Owns sources + global feature union; `aggregate()` produces one `IterationFeedback` per iteration. |
| `sources/StateFeedbackSource.kt` | 87 | source (**push-during-run**) | Scores *program state*: values pushed via `Runtime.observeState` during the run. |
| `sources/ArtifactFeedbackSource.kt` | 68 | source (**pull-at-end**) | Scores an on-disk artifact the (possibly out-of-process) target wrote during the run. |
| `sources/HandoffFeedbackSource.kt` | 156 | source (**streaming-observer**) | Scores *resource handoffs*: directed `from→to` thread passings of a shared resource, with op types. |
| `sources/CodeLocationFeedbackSource.kt` | 88 | source (**streaming-observer**) | Scores *code-site reach*: distinct `RacingOperation.stackTraceHash` concurrency locations seen. |

#### `CoverageSignal.kt` — the common currency
The data class every source reduces to, so search code never branches on where a signal came from
(§4). `features: Set<Long>` are hashed identifiers; novelty is growth of the **global union** of
these across iterations (the AFL coverage-map idea). `score: Double = 0.0` is the optional scalar
for sources that *rank* rather than *discover*.
- **Hit-count buckets (caveat C3, addressed):** `Set<Long>` is still binary by default, but the
  during-run sources can opt into AFL-style hit counts (`--feedback <kind>:hits=true`) by packing
  `(bucket, featureId)` into the `Long` via `HitBucket` — so "hit 7× vs once" becomes distinct
  features, with no aggregator/scheduler change. See §7 C3.
- **Gotcha:** only `score` is defaulted; `sourceName` and `features` are required.

#### `IterationResult.kt` — what's known at iteration end
The input to every `collect()`. Carries the one datum a source cannot compute for itself
(`bugFound`) plus the context a pull-at-end source needs to find its data (`reportFolder`). Both
nullable; `reportFolder` is a `java.nio.file.Path?`.
- **Gotcha:** purely transient — **no `@Serializable`**, never on the replay path.

#### `IterationFeedback.kt` — the search-ready view
What `FeedbackAggregator.aggregate()` produces and what `Scheduler.nextIteration` consumes. It
**wraps an `IterationResult`** in its `result` field (so it is a strict superset of `IterationResult`)
and adds the per-source `signals`, the `newFeatures` this iteration contributed, the per-source
`objectives` vector, and the scalar `fitness`.
- **Gotcha:** the GA ranks on the **`objectives` vector** (Pareto/NSGA-II, §11), *not* on the
  scalar `fitness`. `fitness = Σ objectives` is kept only as a summary/log value. `objectiveVector()`
  returns the values as a dense `DoubleArray` in source order.
- **Gotcha:** `isNovel` is a **derived** property (`newFeatures.isNotEmpty()`), not a stored field.
- **Gotcha:** `newFeatures` holds **namespaced** feature `Long`s (post-tagging, see the aggregator),
  not raw per-source ids. `objectives`, by contrast, is keyed by source name and each value is from
  that source's **own** (un-namespaced) novelty count.
- `EMPTY` companion seeds iteration 0 before any run completes (`fitness = 0.0`, empty everything,
  0-dim objective vector). This is the default value of the `nextIteration` parameter, which is what
  keeps the other seven schedulers compiling untouched (§3a). The 0-dim vector is safe because it is
  only used when no feedback source is registered, so the GA never compares it against an N-dim one.

#### `FeedbackSource.kt` — the contract
A plain local interface — deliberately **not** an RMI `Remote` observer (`ScheduleObserver` /
`TestStatusObserver`), because those fire *per scheduling decision* and return `void`, the wrong
shape for "read one thing at the end of an iteration and return a value" (§3b). `beforeIteration()`
has a default empty body so pull sources can opt in and others ignore it.
- **Gotcha — hard contract:** `collect()` **must never throw**; degrade to an empty signal on
  missing/partial/corrupt data. The aggregator *also* guards with `runCatching`, but sources must
  not rely on that belt-and-suspenders.
- **Gotcha — the dependency rule:** this package **must not import `scheduler/`**. `Scheduler`
  imports `IterationFeedback`; importing back creates a cycle. (Verified: nothing under `feedback/`
  imports `scheduler/`; the only mentions are in prose.)

#### `FeedbackAggregator.kt` — the engine
The only file here with non-trivial logic. `aggregate(result)` runs in a fixed order, and the order
matters: **(1)** call every `source.collect(result)` to get the signals → **(2)** for each source,
count its **own** novelty (`features not yet in perSourceCoverage[source]`) *before* folding its
raw features in, and record `objectives[source] = score + newForSource · noveltyWeight`; in the same
pass, namespace those features and union them into `all` → **(3)** `newFeatures = all −
globalCoverage` → **(4)** fold `all` into `globalCoverage` → **(5)** `fitness = Σ objectives`.
Because signals are produced in step 1 and everything else is derived from them, **the signals exist
before objectives/novelty/fitness do** — the fact that makes the merge below impossible (see
[Can any of these be merged?](#can-any-of-these-be-merged)).
- **Per-source objectives:** the `objectives` map (insertion-ordered by source) is the
  multi-objective vector the GA Pareto-ranks on (§11). Per-source novelty is counted against
  `perSourceCoverage` **before** the fold in step 2 — counting after would make every feature look
  already-seen and zero out the novelty term.
- **Namespacing:** each source gets an 8-bit tag = **its index in the `sources` list**, packed into
  the high 8 bits via `(tag << 56) | (feature & 0x00FF_FFFF_FFFF_FFFF)`. This is why sources must
  keep their raw payload in the **low 56 bits** — bits ≥56 are silently dropped. Two sources emitting
  the same raw hash can't mask each other's novelty. (It also makes `Σ newForSource == |newFeatures|`,
  so `fitness` is unchanged from the pre-vector formula.)
- **Gotcha:** a `CoverageSignal` whose `sourceName` isn't the source's own name falls back to tag 0,
  colliding with whatever source has index 0. Sources should return signals named after themselves.
- **`perSourceCoverage`** keeps the **raw, un-namespaced** per-source union. It now does double duty:
  the end-of-run report (`featuresSeenBySource()`) **and** the per-source novelty count that feeds
  the `objectives` vector — i.e. it is part of the search signal, not just reporting (§11).
- **Per-source valuation (C5):** the objective is now `score + weight_s·(U(C_after) − U(C_before))`
  via a per-source `NoveltyValuation` (default `IDENTITY` = flat linear, weight 1.0 = the old
  behaviour). `priorSize` (`C_before`) is captured here, pre-fold, for the saturating `U`. See §11.

#### `sources/StateFeedbackSource.kt` — value/state, push-during-run
A third lifecycle beyond streaming-observer and pull-at-end (§10): the program-under-test **pushes**
values in during the run via `Runtime.observeState`, and this source scores the *program state* the
schedule produced rather than the schedule shape (the OOPSLA-2020 "calculator coverage" idea).
- **Wiring:** `Runtime.observeState` → `Delegate` (no-op default) → `RuntimeDelegate.observeState`
  → `Configuration.observeState` → fans out `it.observe(value)` to every `StateFeedbackSource`.
- **Gotcha — thread-safety is mandatory:** `observe()` runs on **arbitrary worker threads** (the
  side-channel deliberately does *not* serialize through the scheduler's critical section), so the
  accumulator is a `ConcurrentHashMap.newKeySet` and `bestProximity` is `@Volatile`. A
  streaming-observer source could use a plain set; this one cannot.
- **Reset/snapshot:** `beforeIteration()` clears the set and resets proximity; `collect()` snapshots
  via `features.toSet()` (a copy, safe against in-flight `observe()` calls — though a value still
  racing in at snapshot time may be missed; best-effort by design).
- **Target:** with an optional `target`, `collect()` also emits a proximity `score =
  1/(1+bestProximity)`; with no target the score is `0.0` (pure novelty source).
- **Encoding:** SplitMix64 `mix()` then `& payloadMask` (low 56 bits).

#### `sources/ArtifactFeedbackSource.kt` — external file, pull-at-end
The canonical pull-at-end source (§4/§9), for genuinely out-of-process state (e.g. a JaCoCo dump).
For in-process state prefer `StateFeedbackSource` (no file I/O).
- **`beforeIteration()`** does two things: exports the output path to the target via a system
  property, then `Files.deleteIfExists` the stale artifact (wrapped in `runCatching`) so a target
  that fails to write isn't scored on last iteration's data.
- **`collect()`** wraps the **entire** read+hash in `runCatching{…}.getOrDefault(empty signal)` — it
  never throws, honouring the contract. Hashing is position-tagged FNV-1a per fixed-size chunk
  (default 64 bytes), so reordered identical chunks stay distinct; each chunk hash is `& payloadMask`.
- **Gotcha:** uses `Files.readAllBytes` — the whole artifact is buffered into memory.

#### `sources/HandoffFeedbackSource.kt` — resource handoff, streaming-observer
The shipping streaming-observer source (§10): it implements `ScheduleObserver` to accumulate during
the run *and* `FeedbackSource` to report at iteration end. Emits one feature per distinct directed
thread handoff of a shared resource — the feedback dual of `observers/ResourceOrderingCoverage`
(per-edge novelty *gradient* vs. that observer's single-fingerprint *metric*).
- **What it reads:** at `onNewSchedule`, if `scheduled.pendingOperation is RacingOperation`, it
  pairs the previous distinct accessor of `op.resource` with the current one into a
  `Handoff(from, to)`, where each endpoint carries the acting thread id **and** its `MemoryOpType`.
  The model is a readable `Map<resource, Set<Handoff>>`; the feature `Long` is an FNV-1a hash of a
  legible key (`r=… from:op → to:op`) `& payloadMask` — no bit-packing.
- **Gotcha — plain set is correct here:** `onNewSchedule` fires only on the single scheduled thread
  inside the scheduler's critical section, so unlike `StateFeedbackSource` it needs **no** concurrent
  collection. Reset is in `beforeIteration()`.
- **The `accessId()` seam:** thread id today; return `op.stackTraceHash` to go **location-keyed**
  (stable across thread renumbering) — the one-line switch discussed in §10. That variant requires
  `RacingOperation.resolveRacingOperationStackTraceHash` to be forced on.
- **Honest scope:** this is *inferred* handoff (access sequence), **not** verified happens-before —
  Fray surfaces no release event. Named "handoff", not "happens-before", deliberately.
- **Wiring:** registered by the `"handoff"` arm of `Configuration.registerFeedbackSpec`, which — being
  a streaming source — adds the instance to `feedbackSources` **and** `scheduleObservers` **and**
  `testStatusObservers`. The observer registration is the load-bearing part: without it `onNewSchedule`
  never fires and `collect()` returns empty.

#### `sources/CodeLocationFeedbackSource.kt` — code location, streaming-observer
The other shipping streaming-observer source (§10), and the simplest one — no pairing/`lastAccess`
map. At `onNewSchedule`, if `scheduled.pendingOperation is RacingOperation`, it adds
`op.stackTraceHash` (a `Class.method:line` fingerprint of the concurrency site) to a plain set; each
distinct hash is a feature. Reset in `beforeIteration()`.
- **Gotcha — it forces `stackTraceHash` resolution on:** the `"location"` arm sets
  `RacingOperation.resolveRacingOperationStackTraceHash = true`, because the hash is `0` otherwise (the
  flag is off by default outside SURW / thread-ordering). Safe ordering: the arm runs in `init{}`
  after the static's normal assignment and before any iteration. The source also skips `hash == 0`
  defensively.
- **Granularity is concurrency-op sites, not basic blocks** — broader under `-m`/`--memory` (ordinary
  field/array accesses become visible). For true basic-block coverage you'd add a JaCoCo source under a
  `coverage` kind.
- **Encoding:** widen the 32-bit `Int` hash to a positive `Long` (`& 0xFFFFFFFF`), well within the low
  56 bits the aggregator reserves for the namespace tag. Features are opaque ids (no readable string).
- **Vs. handoff:** shares the streaming-observer machinery but reads `stackTraceHash` (code line),
  never thread index — measures *code reach*, not *interleaving diversity*. Combine via
  `--feedback handoff,location`.
- **Wiring:** the `"location"` arm registers it in all three lists, same as handoff.

#### Not here anymore: `InterleavingFeedbackSource`
The original "new scheduler" commit shipped an `InterleavingFeedbackSource` (schedule-novelty,
streaming-observer). It was **removed** in the current working tree in favour of the value/state and
artifact sources, and nothing under `Fray/` references it any longer. Its streaming-observer
*lifecycle* now has a shipping successor in `HandoffFeedbackSource`.

### Can any of these be merged?

The question that prompted this appendix was *"can we merge `IterationResult` and `IterationFeedback`
into one?"* Short answer: **no — not those two.** They sit at opposite ends of the pipeline, and the
data flow makes the merge impossible without making the code worse:

- `FeedbackSource.collect(result)` takes **`IterationResult`** and runs **first**, to *produce* the
  signals.
- `signals`, `newFeatures`, and `fitness` are computed **from** those signals, **after** every
  `collect()` returns.
- So a single merged type would have to be handed to `collect()` **half-initialized** (empty signals,
  `fitness = 0`) and back-filled afterward — forcing mutability or a throwaway copy, and giving the
  type a name that lies about which lifecycle stage it's in. The current split actively *prevents* a
  `collect()` implementation from depending on aggregated state it shouldn't see. Keep them separate.

**The merge that *is* safe** (if you want fewer files): collapse the three one-screen value types —
`CoverageSignal`, `IterationResult`, `IterationFeedback` — into a single `Feedback.kt`. They share the
`org.pastalab.fray.core.feedback` package, and **Kotlin resolves imports by package + class, not by
filename**, so moving the declarations changes **zero** import statements anywhere in the codebase and
zero behaviour. It also matches existing convention — `scheduler/genetic/Population.kt` already holds
both `Individual` and `Population`. That takes the package from 7 `.kt` files to 5 (`Feedback.kt`,
`FeedbackSource.kt`, `FeedbackAggregator.kt`, plus the two sources) while keeping the contract and the
engine — the two files a maintainer actually reasons about — on their own.

> This appendix documents the current state and the trade-off; it does **not** apply the value-type
> merge. Doing so is a safe, self-contained follow-up if the file count is bothering you.
