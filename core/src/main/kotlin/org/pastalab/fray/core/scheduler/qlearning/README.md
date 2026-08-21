# Q-Learning Scheduling for Fray (design plan)

> **Status: IMPLEMENTED (Phase 1 MVP + SPI) / living document.** The code now exists:
> `scheduler/QLearningScheduler.kt` + `scheduler/qlearning/{QLearningConfig,QTable,StateAbstraction,
> RewardModel,ExplorationPolicy,LastObservedStateHolder}.kt`, wired into `command/Configuration.kt`,
> with a 7-file unit suite under `core/src/test/.../scheduler/qlearning/` and a `calculatorQlearning`
> `@ConcurrencyTest`. This file records the design rationale, written in the same spirit as
> `feedback/README.md`; where it makes a choice it also records the alternative it rejected and why.
>
> **2026-07 design-review + implementation update (this revision).** A pass driven by the requirement
> *"make QL work across a variety of software by letting users model their own state-abstraction
> functions"* added three load-bearing pieces, all folded in below: (1) a **pluggable
> `StateAbstraction` SPI** (§3.1), so the state projection is chosen by the user, not hard-coded;
> (2) an **`observedState` mode + a decision-time `observeState` bridge** (§3.3), which makes the
> OOPSLA-2020 calculator reproduce the paper (a shared-counter coverage test); (3) a
> **`custom` reflective mode** mirroring the existing `Dynamic` scheduler loader. Scope for this first
> cut is **Phase 1 MVP + the SPI** (candidateSet + observedState + custom); `location`/dense-reward/UCB
> stay in later phases. **Implementation surfaced one blocker** — the action must be the positional
> index, not `stackTraceHash` (decision §14.10, §3.4) — caught by the calculator regression and fixed.
> All `RunContext.kt`/`Configuration.kt` line references below were verified against the current source.

---

## 0. TL;DR

A new opt-in scheduler (`--scheduler qlearning`) that *learns* which thread to run next, instead of
evolving a genome (genetic) or sampling (random/POS/PCT). It mirrors the genetic scheduler's proven
architecture exactly:

- **It learns at `nextIteration`, acts at `scheduleNextOperation`** — same two-method `Scheduler`
  interface, no signature change.
- **Only a realized positional decision `trace` is `@Serializable`** (the genome analog), so a
  bug-triggering iteration replays bit-identically from `schedule.json` **without** the learned table.
- **It reuses the `feedback/` layer untouched** for its reward — `--feedback` is the single source of
  the coverage/novelty signal, exactly as the genetic scheduler consumes it.
- **Its state abstraction is user-pluggable** — the projection "program state → abstract `StateKey`" is
  a `StateAbstraction` SPI (§3.1), so QL works across *different* software: a zero-config default that
  needs no annotation, an `observedState` mode that reads the app's own `Runtime.observeState(...)`
  values, and a reflective `custom` class for full control.
- **It is purely additive** — existing schedulers, tests, and the `random` default are unchanged. The
  only touch outside `scheduler/` is a one-volatile-write `observeState` bridge on the long-lived
  `Configuration` (§3.3), inert when QL is not selected.

The one honest departure from the genetic scheduler: Q-learning **must** do per-decision work (a policy
lookup) on the hot path, which the GA deliberately avoided (`feedback/README.md` decision 3a). This
plan bounds that cost with a cheap default state abstraction and the same forced-step short-circuit the
GA uses.

---

## 1. Why Q-learning, and how it relates to the genetic scheduler

Fray runs the program under test many times ("iterations"), each time forcing a thread interleaving,
hunting for bugs. A `Scheduler` decides, at each scheduling point, which runnable thread runs next.

The genetic scheduler treats a run as a **genome** (`List<Int>`) decoded positionally
(`genome[i] % enabledCount`) and evolves the genome population across iterations using coverage
feedback. Its caveat **C1** (`feedback/README.md` §7) is that positional decoding *drifts*: a change
upstream shifts the meaning of every later gene. The planned C1 fix is a **choice-point-keyed**
encoding — a gene that means "at this stable program location, prefer this thread".

Q-learning is the natural home for that idea. A learned value table `Q(state, action)` is only useful
if `state` and `action` mean the same thing across episodes — i.e. they must be **keyed by stable
program location**, not by drifting positions. So Q-learning *motivates* solving C1 on the learning
side, while still recording a positional trace for faithful replay (see §6/§7).

| | Genetic | Q-learning |
| --- | --- | --- |
| Cross-iteration artifact | Bounded NSGA-II population (`@Transient`) | Bounded Q-table (`@Transient`) |
| Per-iteration update | select + crossover + mutate | batch Bellman backup over the episode trajectory |
| Per-decision work | none (just `genome[i] % size`) | **policy lookup** (state hash + argmax + 1 RNG draw) |
| Serialized artifact | `genome: MutableList<Int>` | `trace: MutableList<Int>` (positional, identical shape) |
| Reward / signal | `--feedback` → `IterationFeedback` | `--feedback` → `IterationFeedback` (same, unchanged) |

---

## 2. The MDP formulation (and the honest POMDP caveat)

| MDP element | Mapping in Fray |
| --- | --- |
| **Episode** | One Fray iteration. Starts at iteration start; ends at the `collectFeedback → nextIteration` seam where reward becomes available. |
| **Transition** | Each genuine choice point reaching `config.scheduler.scheduleNextOperation(...)` (`RunContext.kt`, ~line 1519). Forced single-candidate steps (`threads.size == 1`) are **not** transitions. |
| **State `s`** | A deterministic abstraction of the current choice point (see §3). |
| **Action `a`** | Which enabled thread runs next (learned by stable key; recorded by positional index — see §3). |
| **Reward `r`** | A single scalar at episode end, from `IterationFeedback` (see §5). |

**Rejected episode boundaries:** *per-context-switch* episodes (no per-step reward exists in Fray) and
*whole-campaign* episode (collapses the per-iteration novelty boundary the feedback layer already
provides).

**Honest framing — this is a POMDP, not a clean MDP.** The scheduler sees only the pending operations
of currently-enabled threads, not full program state, so genuinely distinct states alias to one
`StateKey`. Q-learning's convergence guarantees do **not** hold here; the table is a *heuristic policy*,
not a provably optimal one. The policy and the environment also co-evolve (non-stationarity). Both are
scoped-out limitations, stated openly in the same voice as C1–C6 — not bugs.

---

## 3. State and action representation

### 3.1 State `s` — a pluggable abstraction (the SPI)

A **state** is a single `Long` `StateKey` computed at a genuine choice point. It is *not* full program
state — it is a **user-chosen projection**, because what "the same state" means is domain-specific and
QL only works if the projection reflects what the user cares about. So the projection is behind an SPI,
not hard-coded:

```kotlin
// scheduler/qlearning/StateAbstraction.kt
fun interface StateAbstraction {
  /**
   * Pure, deterministic map from THIS choice point to an abstract state key.
   *
   * @param candidates the enabled threads, ALREADY sorted by thread.id
   *   (RunContext.getEnabledOperations, RunContext.kt ~1383) — so ordering is canonical/reproducible.
   *   Implementations may read each candidate's `pendingOperation` (and, for a `custom` abstraction,
   *   their own program singletons on the instrumented classpath).
   *
   * CONTRACT: deterministic given the schedule; MUST NOT read `allThreads`
   * (`registeredThreads.values`, a ConcurrentHashMap with nondeterministic iteration order),
   * MUST NOT block, schedule, or mutate program state, and MUST NOT throw (a throw degrades to a
   * fixed key, never fails the run).
   */
  fun stateKey(candidates: List<ThreadContext>): Long
}
```

The `candidates`-only signature (not `allThreads`) is deliberate: it is exactly the argument
`scheduleNextOperation` already receives, and it is the only order-stable view (§7 rule 4).

### 3.2 The four built-in modes (chosen by `stateMode`, clamped by `sanitized()`)

| Mode | `StateKey` hashes… | Works on | Cost | Reproduces the paper? |
| --- | --- | --- | --- | --- |
| `candidateSet` | multiset of candidates' `(op class, MemoryOpType, resource)` | **any** software, zero config | cheap, no StackWalker | **no** (schedule-*shape* only) |
| `observedState` | the latest `Runtime.observeState(v)` value (§3.3) | apps that annotate state | one volatile read | **yes** — value *is* the state |
| `location` | `candidateSet` + each candidate's `RacingOperation.stackTraceHash` | any software | `StackWalker.walk` per `RacingOperation` (opt-in) | partial |
| `custom` | whatever a user `StateAbstraction` class returns | any software | user-defined | user-defined |

- **`candidateSet`** — hash of the multiset of candidates' `(MemoryOpType, resource)` plus a coarse
  op-class tag. **No StackWalker cost** — never touches `stackTraceHash`. Hot-path safe. This is the
  safe general fallback but it is a schedule-*shape* abstraction: it cannot tell two program states
  apart if their pending operations look identical (see the calculator caveat in §13).
- **`observedState`** — reads the value the program pushed via `Runtime.observeState` (§3.3). This makes
  the abstract state *be* the program value the user cares about, which is what the OOPSLA-2020 paper's
  calculator needs.
- **`location` (opt-in, principled):** also folds in each candidate's `RacingOperation.stackTraceHash`
  for program-location awareness (the C1-clean signal). This **forces
  `RacingOperation.resolveRacingOperationStackTraceHash = true`**, which costs a `StackWalker.walk` per
  `RacingOperation` *construction* across the whole run — the same cost `--feedback location` already
  pays. Flip that global flag **only** when the user explicitly selects this mode; never silently.
- **`custom` (reflective):** `--qlearning-state-class com.foo.MyAbstraction` (or
  `fray.qlearning.stateClass`) is loaded via `classLoader.loadClass(name).getConstructor().newInstance()`,
  **exactly mirroring the existing `Dynamic` scheduler loader** (`Configuration.kt` 273–282). The user's
  class is on the instrumented classpath alongside the app, so it can read the app's own statics.

**Default is auto-selected, overridable.** With `stateMode` unset: if a `state` feedback source is
active (`--feedback state`) the default is **`observedState`** (so the calculator reproduces the paper
out-of-the-box); otherwise it is **`candidateSet`** (safe, zero-cost, general). An explicit
`--qlearning-state-mode` always wins. Rationale: the two most common QL entry points — "I annotated my
app with `observeState`" and "I just want to try QL on my code" — each get the right default without a
flag, while power users retain full control. (Rejected: *always* `candidateSet` per the original plan —
predictable, but silently fails to reproduce the paper on the calculator, the single most likely
"why isn't it like the paper" trap.)

**Two ways for a user of *different* software to model their abstraction:**
1. **Annotate the app (no new class).** Add `Runtime.observeState(myAbstractValue)` at the points where
   your interesting state changes, run with `--feedback state`. `observedState` mode turns that value
   into the QL state. This is what the calculator does (`Runtime.observeState(counter)`).
2. **Write a `StateAbstraction` class.** For structural abstractions (read the candidate set, or your
   own singletons), implement the SPI and pass `--qlearning-state-class`. No app annotation needed.

Hashing reuses the FNV-1a + `PAYLOAD_MASK` (`0x00FF_FFFF_FFFF_FFFFL`) discipline from
`HandoffFeedbackSource.featureId` (and the SplitMix64 `mix` from `StateFeedbackSource`). An optional
bounded sliding window of the last *k* decisions (knob, default `k = 0`) can add Markov context in a
later phase. State explosion is bounded by a capped table (§4).

### 3.3 The `observeState` decision-time bridge (the load-bearing new piece)

`Runtime.observeState(long)` already exists as a **pure side-channel** feeding the end-of-iteration
reward: `Runtime.observeState` → `RuntimeDelegate.observeState` (`delegates/RuntimeDelegate.kt` 272–282,
which explicitly "does NOT enter the scheduling path, never becomes a choice point, never perturbs the
interleaving or replay") → `Configuration.observeState` (`Configuration.kt` 659–661) → fans out to every
`StateFeedbackSource`. But that value is *only* consumed at episode end; `observedState` mode needs the
**latest observed value at `scheduleNextOperation` time**.

The obstacle (already a settled blocker, §14.2): the scheduler instance is **replaced every iteration**
and is **not** a `ScheduleObserver`, so it cannot itself subscribe to `observeState`. Resolution — hang
a tiny holder on the **long-lived `Configuration`**, mirroring how `var lastFeedback`
(`Configuration.kt` 603) already bridges run→scheduler:

1. `class LastObservedStateHolder { @Volatile var value: Long = 0L }`, held as
   `val lastObservedState = LastObservedStateHolder()` on `Configuration` (beside `lastFeedback`).
2. `Configuration.observeState(value)` writes `lastObservedState.value = value` **before** the existing
   `StateFeedbackSource` fan-out. One volatile write on an already best-effort side-channel; **zero
   cost when QL is inactive** (the field is just never read).
3. `Configuration` is the only place holding *both* the holder and the live scheduler, so it injects the
   pointer: once after the scheduler is built, and again in `nextIteration()` right after
   `scheduler = scheduler.nextIteration(...)` (`Configuration.kt` 917–921), via
   `(scheduler as? QLearningScheduler)?.observedStateHolder = lastObservedState`. The holder is stable
   across iterations; only the pointer from each fresh scheduler instance is refreshed. This works
   identically on the CLI and reflective JUnit paths (the cast is Configuration-side, independent of how
   the scheduler was constructed).

**Determinism (why this is sound).** Fray serializes execution — exactly one scheduled thread runs
between scheduling points (`currentThreadId` set at `RunContext.kt` ~1528) — and `observeState` never
becomes a choice point. So "latest observed value at decision *i*" is a deterministic function of the
schedule. A `@Volatile` read suffices; no lock. **Replay is unaffected**: replay is positional
(`trace[i] % threads.size`) and never consults the holder. We choose a **global** last-observed value
(not per-thread): correct for the calculator's single shared counter; a program that partitions state
per thread should express that in a `custom` abstraction instead. Pinned by `QLearningDeterminismTest`
plus an inline comment, so a future change to Fray's execution model can't silently break it.

Elegant symmetry on the calculator: the *same* `Runtime.observeState(counter)` call feeds **both** the
reward (episode-end, via `StateFeedbackSource`) **and** the abstraction (decision-time, via the holder)
— two independent reads, neither perturbing the other.

### 3.4 Action `a` — the positional index (learning key == replay index)

The action is the chosen thread's **position in the sorted candidate list** — the *same* value used
for both learning and replay. The Q-table is indexed by `(stateHash, positionalIndex)`; the policy
picks a positional index against that table, and exactly that index is appended to `trace`, decoded on
replay as `trace[i] % threads.size` — identical in shape and decode to the genetic genome. Replay
therefore needs **neither** the Q-table **nor** `stackTraceHash` resolution.

This is the genetic scheduler's proven action space: the positional index is **always available and
always learnable**, and it is exactly what we serialize, so learning key and replay index never
diverge.

> **Design correction (2026-07, caught in implementation — decision §14.10).** An earlier draft split
> the action into a *learning key* = `op.stackTraceHash` and a *replay index* = positional. That was
> **wrong on two counts**, both proven empirically on the calculator:
> 1. `stackTraceHash` is `0` unless `resolveRacingOperationStackTraceHash` is forced on — and it is
>    forced on only in `location` state mode. Under the default `candidateSet`/`observedState` modes
>    every step had a `0` location key, so **every** step fell into the "excluded from learning" path,
>    the trajectory stayed empty, and Q-learning silently degenerated to epsilon-greedy random
>    (`trajectory=0 qStates=0` in the feedback-debug trace; ~101 distinct states, no better than
>    random).
> 2. Even *with* resolution on, an action key must distinguish the *candidates at one choice point*.
>    In the calculator every sender blocks on the **same** `lock.lock()` line, so all candidates share
>    one `stackTraceHash` — it cannot tell "run the ADD sender" from "run the RESET sender". The thing
>    that distinguishes them is precisely their position in the sorted set.
>
> The fix: the action is the positional index; `stackTraceHash` is a **state** ingredient (`location`
> mode) only, never the action. With this, the same run learns a real table (`qStates` grows into the
> thousands) and reaches ~1788 distinct states vs ~101 before — the paper's mechanism.

**`stackTraceHash == 0` is now irrelevant to the action** — since actions are positional, every genuine
choice point (including `ThreadStartOperation` and other non-`RacingOperation` steps) is uniformly
learnable. `stackTraceHash == 0` only makes `location`-mode *state* keys coarser (a state caveat, §13),
never a learning-exclusion. Q-rows are **sparse maps** keyed by index, so variable candidate counts are
fine.

> **`thread.id` vs `index`:** replay depends only on (a) the recorded positional trace and (b) the
> deterministic re-derivation of the `thread.id`-sorted candidate list. The positional action index is
> stable *within* a choice point by construction; its cross-episode meaning can drift with the enabled
> set (the genetic C1 caveat), which degrades *learning quality* but can **never** break replay.

---

## 4. Learning algorithm

- **Update timing — episode-end batch backup in `nextIteration`, NOT online TD on the hot path.** Fray
  delivers reward only once per iteration, so there is nothing to bootstrap from mid-run. The hot path
  stays cheap (state hash + argmax + one RNG draw). A `@Transient` trajectory buffer
  `List<(StateKey, actionIndex)>` is appended in `scheduleNextOperation` and drained at episode end.
- **Backup rule (MVP = Monte-Carlo, uniform terminal credit):** with terminal return `R`, for each
  `(s_t, a_t)` on the trajectory:
  `Q[s_t][a_t] += alpha * (G_t - Q[s_t][a_t])`, with `G_t = R * gamma^(n-1-t)`.
  `gamma` near 1 (long trajectories, single terminal reward). An `n`-step variant is a config knob,
  defaulting to MC.
- **Q-table:** sparse `Map<StateKey, Map<actionIndex, Double>>` with **optimistic init** (unseen action
  → `optimisticInit`, which drives systematic exploration before epsilon even fires) and a **bounded**
  capacity `tableCap` (the `populationCap` analog) with **row-level LRU eviction**. Eviction discards a
  rarely-seen state's learned values; on re-encounter the row falls back to optimistic init — safe,
  exploration-friendly degradation. Eviction touches **only** `@Transient` learned values and **never**
  participates in action selection within an iteration.
- **Exploration — epsilon-greedy with per-episode geometric decay:**
  `epsilon = max(epsMin, eps0 * decay^episode)`, recomputed in `nextIteration` and carried forward.
  UCB / Boltzmann are deferred behind an `ExplorationPolicy` interface.
- **Tabular, not deep.** Tabular is the MVP: no DL/native dependencies, fully deterministic.
  Linear function approximation and deep-Q are explicit future work.

---

## 5. Reward model (ZERO changes to `feedback/`)

`RewardModel.reward(feedback): Double` is a **pure consumer** of fields already on `IterationFeedback`
(`result.bugFound`, `isNovel`, `newFeatures`, `fitness`, `objectiveVector()`):

```
R = w_bug * 1[result.bugFound != null]
  + w_nov * noveltyTerm(feedback)        // newFeatures.size, OR the C5-saturated objective vector (bounded)
  + w_fit * normalize(feedback.fitness)
  clipped to [0, R_max]
```

- Defaults: `bug >> novelty >> fitness`.
- **Clip / scale** so Bellman targets stay well-conditioned. Prefer the already-saturating
  `NoveltyValuation` objective (free diminishing returns); raw `newFeatures.size` is unbounded and
  front-loaded.
- `EMPTY` feedback (iteration 0, or no `--feedback`) yields a baseline reward — still credit
  `bugFound`, but **document that Q-learning needs a `--feedback` source to learn coverage**, exactly
  as the genetic scheduler does.

**Dependency rule:** `RewardModel` imports `feedback/`; `feedback/` never imports `scheduler/`
(one-way, preserved). No changes to the feedback layer at all.

**Dense per-step shaping is DEFERRED to Phase 2** and must **not** be implemented by making the
scheduler a `ScheduleObserver` — see the resolved blocker in §11. If added later, it goes through a
long-lived helper held on `Configuration`, and any `onNewSchedule` accumulation reads **only**
`scheduled` / its `pendingOperation`, never `allThreads` (which is `registeredThreads.values`, a
`ConcurrentHashMap` with nondeterministic iteration order).

---

## 6. Serialization & replay (the load-bearing contract)

```kotlin
@Serializable
class QLearningScheduler(val trace: MutableList<Int> = mutableListOf()) : Scheduler {
  constructor() : this(mutableListOf()) { config = QLearningConfig.fromSystemProperties(); /* ... */ }

  @Transient var rand: Randomness = ControlledRandom()
  @Transient var config: QLearningConfig = QLearningConfig.DEFAULT
  @Transient var qTable: QTable = QTable(config)
  @Transient var trajectory = mutableListOf<Pair<Long, Long>>()   // (StateKey, actionIndex)
  @Transient var epsilon: Double = config.eps0
  @Transient var episode: Int = 0

  // The pluggable state projection (§3.1). Set by getScheduler from stateMode/state-class; carried
  // forward by hand in nextIteration like every other @Transient field. On deserialize (replay) it
  // resets to the default and is NEVER consulted — replay is positional.
  @Transient var abstraction: StateAbstraction = StateAbstraction.candidateSet()

  // The observeState decision-time bridge (§3.3). A pointer to the holder on the long-lived
  // Configuration; injected Configuration-side (init + after nextIteration), NOT captured at
  // construction (the scheduler is built before Configuration exists). Read only by `observedState`
  // mode. Null under replay / plain unit construction — the abstraction must tolerate that.
  @Transient var observedStateHolder: LastObservedStateHolder? = null
  // ...
}
```

- **Only `trace` is serialized** → `schedule.json`. Everything learned is `@Transient`, carried
  forward by hand in `nextIteration` (exactly like `GeneticScheduler` carries `population`). A recorded
  failure replays from `trace` alone — table absent, config reset to `DEFAULT` on deserialize. Because
  `Scheduler` is a `@Serializable sealed interface`, kotlinx polymorphism picks up the new subclass
  with no registration.
- **Replay decode = `trace[i] % threads.size`** — RNG-free, table-free, `stackTraceHash`-free.
- **Forced-step short-circuit must be byte-identical** in record and replay:
  `if (threads.size == 1) return threads[0]` **before** any trace read/write — otherwise the trace
  index desyncs (off-by-one). Mid-run trace extension copies the GA's append-in-place idiom
  (`rand.nextInt() % threads.size`) drawing from the scheduler-private `rand`.

---

## 7. Determinism rules (non-negotiable)

1. **Scheduler-private RNG.** Epsilon / index / tie-break draws use the scheduler's own `@Transient
   rand` (seeded via `it.rand = randomness` in `getScheduler`, carried in `nextIteration`), **never
   `config.randomness`** (which drives the program under test and is the only stream saved to
   `random.json`). This is how the GA keeps its genome-extension draws from perturbing the recorded
   stream.
2. **Fixed, branch-independent draw count per genuine choice point.** Draw exactly one `nextDouble()`
   (epsilon coin) **and** one `nextInt()` (explore index) unconditionally; ignore the index on the
   exploit branch. Conditional draw counts would desync the positional `ControlledRandom` stream — the
   daemon-include flag (`RunContext.kt` ~1418–1431) uses this same draw-once-before-branching
   discipline.
3. **Draw-free, order-independent argmax tie-break.** Pick the smallest positional index among the
   max-Q set by **explicit** comparison — never HashMap iteration order, never a random draw.
4. **Pure state keys**, built only from the sorted `threads` parameter and each candidate's
   `pendingOperation` (or the `observedState` holder); actions are the positional indices of that same
   sorted list.

---

## 8. Integration & wiring (additive only)

- New `class QLearning : ScheduleAlgorithm("qlearning", false)` in `command/Configuration.kt`,
  mirroring `Genetic` (lines 236–271) verbatim: nullable `--qlearning-*` flags overlaid on
  `QLearningConfig.fromSystemProperties()` then `.sanitized()`; `getScheduler` sets `it.rand`,
  `it.config`, `it.qTable`, and **`it.abstraction`**. It:
  - **resolves the auto-select default** — if `stateMode` is unset and the `fray.feedback` spec (set at
    `Configuration.kt` 472) names `state`, use `observedState`, else `candidateSet`;
  - **forces `resolveRacingOperationStackTraceHash = true` only if the resolved `stateMode == location`**;
  - for `stateMode == custom`, **reflectively loads `--qlearning-state-class`** via
    `classLoader.loadClass(name).getConstructor().newInstance() as StateAbstraction`, exactly like
    `Dynamic.getScheduler` (`Configuration.kt` 277–279).
- Add `"qlearning" to QLearning()` to `MainCommand.scheduler.groupChoice` (lines 306–316). Default
  stays `random`.
- **The `observeState` bridge** (§3.3): add `val lastObservedState = LastObservedStateHolder()` beside
  `lastFeedback` (line 603); write it in `observeState(value)` (lines 659–661) before the fan-out;
  inject the pointer into the live scheduler in `init` and in `nextIteration()` (lines 917–921) after
  the reassignment. This is the **only** edit outside `scheduler/` + `qlearning/`; it touches neither
  `Scheduler.kt`, `RunContext.kt`, `RuntimeDelegate.kt`, nor anything in `feedback/`.
- **Reward path needs nothing new:** `Configuration.nextIteration()` already passes `lastFeedback` to
  `scheduler.nextIteration(...)`. `--feedback` is reused unchanged.

---

## 9. Package layout & config knobs

```
scheduler/QLearningScheduler.kt              @Serializable; the ONLY serialized field is `trace`
scheduler/qlearning/QLearningConfig.kt       DEFAULT / fromSystemProperties("fray.qlearning.*") / sanitized()
scheduler/qlearning/QTable.kt                sparse bounded table; optimistic init; batch update; LRU eviction
scheduler/qlearning/StateAbstraction.kt      the SPI (fun interface) + built-ins candidateSet | observedState | location; factory keyed by stateMode/state-class
scheduler/qlearning/RewardModel.kt           pure IterationFeedback -> scalar; imports feedback/, never reverse
scheduler/qlearning/ExplorationPolicy.kt     EpsilonGreedy MVP; UCB / Boltzmann deferred
scheduler/qlearning/LastObservedStateHolder.kt  1-field @Volatile holder for the observeState decision-time bridge (§3.3)
```

**Config knobs** (all clamped by `sanitized()` — a bad value degrades, never fails the run):

| Knob | Meaning | Notes |
| --- | --- | --- |
| `alpha` | learning rate | clamp [0, 1] |
| `gamma` | discount | clamp [0, 1]; default near 1 |
| `eps0`, `epsDecay`, `epsMin` | epsilon-greedy schedule | per-episode decay |
| `optimisticInit` | initial Q for unseen (s, a) | drives exploration |
| `tableCap` | bounded Q-table capacity | >= 1; `populationCap` analog |
| `nStep` | backup horizon | `0` = Monte-Carlo (default) |
| `stateMode` | `candidateSet` \| `observedState` \| `location` \| `custom` | **unset → auto** (`observedState` when `--feedback state`, else `candidateSet`); unknown → `candidateSet` |
| `stateClass` | FQN of a user `StateAbstraction` (for `stateMode=custom`) | reflective no-arg load, like `--scheduler-name` on `Dynamic` |
| `windowK` | recent-decision context length | default 0 |
| `rewardBug`, `rewardNovelty`, `rewardFitness` | reward weights | `bug >> novelty >> fitness` |

Two delivery channels (mirroring `--genetic-*` / `fray.genetic.*`): nullable `--qlearning-*` CLI flags
(e.g. `--qlearning-state-mode observedState`, `--qlearning-state-class com.foo.MyAbstraction`,
`--qlearning-alpha`), and `fray.qlearning.*` system properties (e.g. `fray.qlearning.stateMode`,
`fray.qlearning.stateClass`) for the reflective JUnit path. Nothing activates unless
`--scheduler qlearning`.

### Running it on the calculator

Requires Fray's instrumented JDK — run `./gradlew build` first, then invoke the `fray` runner
with a test class that exercises a shared counter under `Runtime.observeState`.

```bash
bin/fray \
  --scheduler qlearning \
  --class org.pastalab.fray.example.CalculatorCoverageTest \
  --method runOnce \
  --iter 100 \
  --feedback state \
  --feedback-debug \
  --output build/fray-report-qlearning
```

`--feedback state` is present, so the auto-select default resolves `stateMode` to `observedState`: the
calculator's `Runtime.observeState(counter)` value becomes the abstract state, reproducing the paper's
"which thread = which operation" learning. Compare the printed "distinct calculator states" against
`-PfrayScheduler=genetic|random|pos` at the same `-PfrayIter`. Force a mode explicitly with
`-Dfray.qlearning.stateMode=observedState|candidateSet`. A `@ConcurrencyTest(scheduler =
QLearningScheduler.class, iterations = 10)` method mirroring `calculatorGenetic` can be added, but is
declared **unproven** (the C6 analog in §10).

---

## 10. Testing strategy (mirror the genetic suite; run under `:core:test`)

Pure-JVM unit tests under `core/src/test/.../scheduler/qlearning/`:

- **`QLearningConfigTest`** — `DEFAULT` constants, `sanitized()` clamps, `fromSystemProperties()`
  fallbacks (mirrors `GeneticConfigTest`).
- **`QTableTest`** — Bellman update to epsilon-tolerance (closed form), deterministic argmax +
  tie-break, optimistic init, bounded eviction with the correct victim (mirrors `PopulationTest`).
- **`StateAbstractionTest`** — each built-in mode is pure/deterministic given the sorted candidate
  list; explicitly pin the `stackTraceHash == 0` case; `observedState` reads the injected
  `LastObservedStateHolder` (and tolerates a null holder → fixed key); `custom` loads a test class by
  FQN and rejects a bad/missing class by degrading to `candidateSet`, never throwing; assert the
  auto-select default resolves `observedState` under a `state` feedback spec and `candidateSet`
  otherwise.
- **`ExplorationPolicyTest`** — epsilon coin and explore index drawn from a preseeded
  `ControlledRandom(integers, doubles)` (the `OperatorsTest` idiom); `eps = 0` always greedy, `eps = 1`
  always random; **pin the exact fixed draw count/order per step**.
- **`RewardModelTest`** — reward purely from `IterationFeedback`; `EMPTY` → baseline; never reads
  scheduler state.
- **`QLearningSchedulerSerializationTest`** — *strengthened beyond a substring denylist*: round-trip a
  scheduler with a **non-default** table/epsilon/config, deserialize, and assert learned fields are
  back at `DEFAULT`/empty **and** `trace` round-trips exactly (catches accidentally-serialized fields
  by behavior, not by name).
- **`QLearningDeterminismTest`** (no GA analog) — record a trajectory through `scheduleNextOperation`
  with a preseeded RNG, rebuild a replay scheduler from `trace` alone (no table), assert identical
  interleaving and identical `schedule.json`. Cover: forced steps interleaved with genuine ones
  (trace alignment), trace extension beyond the seed, record-with-`location` / replay-without, **and
  the `observedState` bridge** — drive a scripted sequence of holder writes between decisions and
  assert the recorded trace still replays bit-identically with the holder absent (proves replay never
  consults live observed state).

**C6-analog (unproven JUnit path):** `@ConcurrencyTest(scheduler = QLearningScheduler::class)` is
logically complete but needs the instrumented `bin/fray/bin/java` launcher (runs 0 under plain
`:junit:test`). Verify end-to-end via the **CLI** path only and declare the JUnit path unproven,
exactly as C6 does for the GA.

---

## 11. Phasing (validate the loop first)

- **Phase 1 (MVP + SPI):** tabular Q; positional replay trace; episode-end Monte-Carlo batch update;
  epsilon-greedy from the scheduler-private RNG; terminal reward (bug + novelty + fitness, clipped);
  the **pluggable `StateAbstraction` SPI** with `candidateSet` + `observedState` (+ the `observeState`
  bridge) + `custom` reflective loading; auto-select default; `--feedback` reused. Enough to run and
  reproduce the calculator and be reusable across software. *No observer, no dense reward.*
- **Phase 2:** `location` state mode (choice-point keying — the C1 fix); dense per-step shaping via a
  `Configuration`-held helper (not the scheduler-as-observer).
- **Phase 3:** UCB / count-based exploration, epsilon schedules, table-bounding tuning.
- **Future (out of scope):** function approximation / deep-Q.

---

## 12. Evaluation methodology

Compare `qlearning` against `random`, `pct`, and `genetic` on the ~70
`integration-test/.../core/fail/*` targets (deadlocks: monitor, wait/notify, rwlock, park, cdl,
network). Metrics: **iterations-to-first-bug**, bug-find rate over a fixed
budget, and distinct-feature growth (Fray already prints a per-source feedback summary).

**Fairness rule:** hold `--feedback` **identical** across all arms so the only variable is the policy —
otherwise the result conflates the learner with the novelty signal. Use multiple seeds per arm for a
meaningful iterations-to-first-bug distribution.

**First measured result (2026-07, calculator, `--feedback state`, 50 iterations, single seed):**

| Scheduler | distinct counter states explored |
| --- | --- |
| `random` | 224 |
| `qlearning` (`observedState`, auto-selected) | **1788** |

QL explores ~8× more of the value space — the paper's qualitative result. This also served as the
regression that caught decision §14.10 (the pre-fix, `stackTraceHash`-action build sat at ~101 states,
i.e. *below* random, because it never learned). Treat the number as illustrative (one seed, first cut),
not a guarantee; the standing methodology above still applies for a real evaluation.

---

## 13. Honest caveats (the C-voice — scoped-out, not bugs)

- **Non-stationarity** — policy and environment co-evolve, so it is only approximately an MDP.
- **Partial observability (POMDP)** — the state key is a heuristic projection; distinct true states
  alias.
- **State explosion + eviction discards learned value** — the eviction policy is a heuristic
  (analogous to C4's "eviction discards stale members").
- **Sparse-reward credit assignment** — Phase 1 is terminal-only; that's the motivation for Phase 2
  dense reward.
- **Hot-path cost** — the genuinely new risk vs the GA's decision 3a. Bounded by the cheap
  `candidateSet` default and the `threads.size == 1` short-circuit, but real and to be measured.
- **Reward scaling** — a mis-scaled bug bonus can swamp or be swamped by novelty; configurable +
  sanitized.
- **`stackTraceHash == 0` degeneracy** — under `candidateSet`/`observedState` mode (or with resolution
  off) `location` state keys collapse to the location-less `candidateSet` key. This coarsens the STATE
  only; it never excludes a step from learning (actions are positional — §3.4). Replay is unaffected.
- **No reward without `--feedback`** — a feedback-less run learns nothing beyond bug reinforcement;
  degenerates to epsilon-greedy random.
- **`candidateSet` will NOT reproduce the paper on the calculator** — its pending lock-acquire ops look
  identical across counter values, so the schedule-shape state can't distinguish them. Paper repro
  *requires* `observedState`; the auto-select default (§3.2) picks it under `--feedback state` precisely
  to avoid this trap, but a user who forces `candidateSet` should expect random-like coverage there.
- **`observedState` determinism rests entirely on Fray's serialized execution** (§3.3) — true today
  (one thread runs between scheduling points, `observeState` is never a choice point), but a heuristic
  dependency, not a proof. Pinned by `QLearningDeterminismTest` + an inline comment so a future change
  to the execution model surfaces loudly. The chosen **global** (not per-thread) last-observed value
  also aliases when a program keeps independent per-thread state — express that with a `custom`
  abstraction instead.
- **Untrusted `custom` abstraction code** — a reflectively-loaded `StateAbstraction` runs inside the
  scheduling critical section; a slow, blocking, or nondeterministic implementation degrades or desyncs
  the run. The SPI contract (pure, non-blocking, order-stable, never-throws) is documented and the
  factory guards construction, but the body is the user's responsibility — same trust model as the
  existing `--scheduler dynamic --scheduler-name` path.

---

## 14. Decisions settled during design review

These were genuine contradictions between independently-designed facets, caught by an adversarial pass
against the hard invariants. Recorded here so we don't relitigate them:

1. **Serialized artifact — positional (blocker).** A stable-key serialized log would force the replay
   path to reproduce state-key hashing + `stackTraceHash` resolution (which resets to default on
   deserialize), coupling replay to learned state — a direct replay-invariant violation. The `trace`
   is purely positional indices (see also decision 10: the action itself is the positional index).
2. **No scheduler-as-`ScheduleObserver` (blocker).** `config.scheduleObservers` is built once, but the
   scheduler instance is replaced every iteration (`nextIteration` returns a new instance). A
   self-registered scheduler becomes a dead object from iteration 1, silently no-op'ing dense reward
   *and* trajectory recording. All per-decision work lives in `scheduleNextOperation` on the live
   instance.
3. **Scheduler-private RNG (blocker).** Policy draws use the scheduler's own `rand`, never
   `config.randomness`, so they never perturb the program's recorded `random.json` stream.
4. **Fixed, branch-independent draw count + deterministic draw-free tie-break (blocker).** The "greedy
   steps need no draw / extra draw only on tie" optimization was deleted — it makes the recorded stream
   table-dependent and desyncs replay.
5. **~~`stackTraceHash == 0` → exclude from learning~~ — SUPERSEDED by decision 10.** The original plan
   keyed the *action* on `stackTraceHash` and excluded location-less steps from learning. Decision 10
   makes the action positional, so nothing is excluded; this item is retained only as a pointer to that
   correction.
6. **Forcing `stackTraceHash` always-on is a `stateMode` knob (major).** Default to the cheap
   `candidateSet` mode (no StackWalker); `location` mode is opt-in and the only thing that flips the
   global flag. (Still true: `stackTraceHash` is now a `location`-mode STATE ingredient.)
7. **State abstraction is a pluggable SPI, not hard-coded (2026-07).** The requirement is to run QL
   across *different* software, where "the same state" is domain-specific. A single fixed projection
   can't satisfy that, so §3.1 introduces `StateAbstraction` with three built-ins + a reflective
   `custom` mode. (Rejected: hard-coding `candidateSet` — general but paper-blind on the calculator.)
8. **`observeState` bridge lives on `Configuration`, not the scheduler (blocker, 2026-07).** The
   scheduler is replaced every iteration and is not an observer (decision 2), so it cannot subscribe to
   `observeState`. The `@Volatile` holder therefore lives on the long-lived `Configuration` (beside
   `lastFeedback`) and its pointer is injected Configuration-side each iteration (§3.3). (Rejected:
   scheduler self-registers as an observer — the exact dead-object bug of decision 2.)
9. **Auto-select the default state mode (2026-07).** `stateMode` unset → `observedState` when
   `--feedback state` is active, else `candidateSet`. Gives the calculator a paper-faithful default with
   no flag while keeping the safe general default elsewhere. (Rejected: *always* `candidateSet` — the
   original plan; predictable but silently non-paper on the calculator.)
10. **Action = positional index, not `stackTraceHash` (blocker, 2026-07 — caught in implementation).**
    See §3.4. The `stackTraceHash`-as-action design left `trajectory=0 qStates=0` under the default
    modes (hash is 0 without forced resolution) and couldn't distinguish same-lock-line candidates even
    with resolution on — QL silently degenerated to random (~101 states). Positional actions are always
    learnable and unify learning key with replay index; the fix lifted the calculator to ~1788 states
    (vs 224 for random). `stackTraceHash` is a `location`-mode STATE ingredient only.

---

## 15. Pointers (the existing code this plan leans on)

- `scheduler/Scheduler.kt` — the two-method interface (unchanged).
- `scheduler/GeneticScheduler.kt` + `scheduler/genetic/` — the architecture we mirror (serializable
  artifact + `@Transient` carried-by-hand state; `GeneticConfig` DEFAULT/fromSystemProperties/sanitized;
  bounded `Population`).
- `feedback/` + `feedback/README.md` — the reward source (consumed, never modified); the design-bible
  voice; caveats C1–C6 (C1 is the choice-point-keying motivation).
- `command/Configuration.kt` — `ScheduleAlgorithm` + `Genetic` option (236–271) + `Dynamic` reflective
  loader (273–282, the model for `custom` state-class loading) + `groupChoice` (306–316) +
  `nextIteration` (917–921) / `collectFeedback` seams; the `resolveRacingOperationStackTraceHash` force
  points; `observeState` fan-out (659–661); `var lastFeedback` (603, the bridge-holder precedent).
- `RunContext.kt` — `getEnabledOperations` (candidate sort by `thread.id`, ~1383), the
  `scheduleNextOperation` call site (1517–1519), `currentThreadId` assignment (~1528, the serialized-
  execution fact `observedState` determinism rests on), the daemon-include draw discipline.
- `delegates/RuntimeDelegate.kt` (272–282) + `runtime/.../Runtime.java` (`observeState`, 214–220) —
  the pure side-channel that `observedState` mode taps at decision time.
- `feedback/sources/StateFeedbackSource.kt` — the *reward-side* consumer of the same `observeState`
  value; the `mix` (SplitMix64) + `payloadMask` hashing to reuse.
- `randomness/ControlledRandom.kt` — records draws to `random.json`; the determinism substrate.
- A shared-counter calculator coverage test — the acceptance target; it calls
  `Runtime.observeState(counter)` and runs under `--feedback state`.
- `core/src/test/.../scheduler/genetic/` — the test suite we mirror one-for-one (`GeneticConfigTest`,
  `PopulationTest`, `OperatorsTest`, `NsgaTest`, `GeneticSchedulerSerializationTest`,
  `GeneticSchedulerRestartTest`).
