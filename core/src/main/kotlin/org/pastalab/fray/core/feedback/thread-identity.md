# Thread Identity & Cross-Schedule Stability in Fray

> Design note. How Fray names threads, why those names are **stable across a replay** of one
> schedule but **not across the different schedules** that the feedback layer compares, what that
> means for each scheduler, and which stable-identity schemes do and don't work.
>
> Audience: anyone touching the schedulers (`core/.../scheduler/`) or the grey-box feedback layer
> (`core/.../feedback/`). Companion to [`README.md`](README.md) in this folder.

## Scope & revision (2026-06-26): the consumer is a thread-scheduler, and there are no thread pools

> This revision **challenges the recommendation the rest of this doc reaches** (rung 1 / EI, §7–§8).
> That analysis is internally sound *for the objective it implicitly adopted* — cross-schedule
> *naming stability* for race **classification**. That is the **wrong objective** for the actual
> consumer here. Read §7–§8 through this lens; where they say "rung 1 is the fix," substitute the
> conclusion below.

**Two assumptions this revision is written under:**

1. **No thread pools.** The program under test never recycles a carrier across tasks. This deletes
   the whole pool-collapse family (F1/F2/F4) from consideration — which removes rung 1's *headline*
   advantage (pool-immunity) **and** spawn-path's *fatal* weakness (worker birth at a JDK-internal
   site). With pools gone, threads are born at the program's own `new Thread()` sites, so a thread's
   **lineage is a real, program-determined fact**, not a pool artifact.
2. **The feedback consumer is a scheduler whose only action is "pick a runnable thread."** It does not
   classify races; it is *steered*. Its action space is threads, so a useful reward must be in the
   **same currency**: it must distinguish *the orderings the scheduler can actually produce*.

**The objective inversion (why §7–§8's recommendation is wrong here).** Execution indexing's flagship
use (PLDI 2008) is race **classification** — deciding whether two reported races are the *same bug* —
so it deliberately **merges** equivalent events (collapsing two symmetric workers to one name is the
*desired* behaviour). Steering a scheduler is the **opposite**: it must **distinguish** the decisions
it can make, to get a gradient to climb. So the property §7 sells as a virtue — "rung 1 / EI goes
silent on symmetric workers, and that silence is correct" — is correct for *classification* and
**disqualifying for steering**. There must be an observation **per scheduling decision**. A reward
that goes blind exactly when two threads contend one resource at one line — the textbook race, *the
thing we are hunting* — is not an acceptable cost. This is the load-bearing error in the original
recommendation.

**Revised bottom line (handoff feedback, under the two assumptions):**

- **Key the handoff on a *thread* identity, not a code location.** Reject rung 1 (`op.stackTraceHash`)
  *as the handoff key*: the scheduler acts on threads, and rung 1's same-line **self-access-guard
  suppression** (§7) *deletes* the observation precisely at contended resources — the decisions the
  search most needs to see.
- **Use a spawn-path** (`"r.0.1"`: parent prefix + per-parent sibling ordinal) as that thread id, not
  the global birth-order `index`. Under no-pools it is schedule-stable wherever a thread is nameable,
  and it **fixes the W1 cousin false-merge** (different parents ⇒ different prefixes ⇒ no aliasing,
  ever). Its only residual instability is C3 (the parent's *creation control-flow itself* races) —
  where the thread genuinely does not recur, **no** scheme can name it, and the creation race is
  itself captured at the `ThreadStartOperation` anyway.
- **`location` coverage already exists as its own source — so rung 1 is redundant, not additive.**
  `CodeLocationFeedbackSource` (`--feedback location`) already emits code-site novelty and composes
  with handoff (`--feedback handoff,location`; the aggregator namespaces them). Rung 1 would turn the
  *handoff* source into a second, slightly-richer code-location source while **deleting the only
  signal handoff uniquely contributes** — thread ordering. Keep them split: `location` answers "did we
  reach a new concurrency code-site?"; thread-keyed `handoff` answers "did we realize a new thread
  *ordering* on a resource?" — the steering signal.

**Cost — far lower than §6/§8 imply, because SURW already does the hard part.** §6 rates spawn-path
"medium (new per-thread state)." That is wrong: it needs **no** change to `ThreadContext`,
`RunContext`, or the creation hook. `SURWScheduler.checkNewThreads` already reconstructs the spawn
tree *observationally* from `(index, parentId)` data already on the wire — `parentId` rides on
`ThreadStartOperation`. The handoff source receives the same information (it already gets `allThreads`
and is invoked when a `ThreadStartOperation` is scheduled); it just records the spawn edge there
instead of early-returning. **The one thing not to copy from SURW:** it stores children in an
unordered `Set<Int>` and never needs a sibling order — a spawn-path does. Derive the sibling ordinal
from **per-parent observation order** (the order a parent's children's `ThreadStartOperation`s are
scheduled = that parent's own program order ⇒ schedule-stable), **never** from the child's `index`
magnitude (the global birth-order counter whose reshuffling is the entire bug).

**One honest caveat.** If the program creates threads in a fully deterministic order (no two
concurrently-live parents racing to spawn), plain `index` is *already* schedule-stable and spawn-path
is insurance, not a fix. Spawn-path's concrete payoff is exactly the W1 regime — concurrently-born
cousins / racy birth order — so adopt it when that regime is reachable (it usually is), not as a
reflex.

## TL;DR

> **Superseded for the thread-scheduler / no-pools case — see "Scope & revision" above.** The bullets
> below state the doc's *original* recommendation (rung 1 / operation-EI), which optimizes for naming
> stability under race *classification*. For the actual consumer here — a scheduler that is *steered*
> and acts only on threads — the conclusion flips to **thread-keyed handoff via a spawn-path**, for
> the reasons in the revision. The two ids, the replay/schedule distinction (§2–§3), and the §4/§5
> mechanics below remain accurate; only the **recommendation** (which identity to key feedback on)
> changes.

- A thread has **two** ids: the JVM `thread.id` (OS-assigned, never stable) and Fray's
  `ThreadContext.index` (`= registeredThreads.size` at registration — i.e. **birth order**).
- `index` is **deterministic across a *replay* of one fixed schedule** (this is what
  `ReplayScheduler` keys on, and why replay works). It is **not guaranteed stable across *different*
  schedules** of the same program, because birth order is itself a scheduler-controlled quantity.
- This matters for exactly one subsystem: the **feedback layer**, which compares features *across
  distinct interleavings* (`HandoffFeedbackSource` keys handoffs on `scheduled.index`). There, an
  unstable identity makes the "same" feature mean different things run-to-run — noise.
- The existing **black-box schedulers (Random, POS, PCT, FIFO, SURW)** are *not* corrupted by this,
  because none of them compares anything across schedules — each iteration is an independent sample.
  They do, however, all depend on a JVM-`thread.id` *ordering* in subtle ways (see §5).
- **The principled answer is execution indexing** (EI, §7): name the *operation* by the structural
  control-flow context that reached it. It is the information-theoretically optimal cross-schedule
  identity (prior art for exactly this — race classification on thread pools, PLDI 2008) and strictly
  dominates both `stackTraceHash` and every birth-order scheme.
- **Recommended fixes (the §7 ladder):** for feedback *now*, key on the **operation's code location**
  (`op.stackTraceHash`) — a ~1-line change that is the cheapest rung of EI and removes the actual
  bug; add a per-`(thread, location)` occurrence counter (cheap-EI) if collisions hurt. Reserve
  **full EI** for the C1 scheduler. A "creation-site + counter" thread id (§4) is *not* sound — it
  inherits birth-order fragility and collapses under thread pools.

---

## 1. The two thread ids

| | `thread.id` (JVM) | `ThreadContext.index` (Fray) |
| --- | --- | --- |
| Source | `java.lang.Thread.threadId()` | `registeredThreads.size` at registration (`RunContext.kt:261` main = 0; `:461` children) |
| Meaning | OS/JVM handle | "the N-th thread Fray registered" = **birth order** |
| Assigned where | by the JVM at thread construction | in `threadCreateDone(t)` (`RunContext.kt:431`), a **scheduler-controlled** Fray hook |
| Stable across replay of one schedule? | relative order: yes; absolute value: no | **yes** (the replay key) |
| Stable across *different* schedules? | no | **not guaranteed** (see §3) |
| Used as | `registeredThreads` map key; candidate-list sort key (`RunContext.kt:1383`) | the logical thread id: recorded/replayed, and used by `HandoffFeedbackSource` / `SURWScheduler` |

`ThreadContext` also carries `parentId` (the parent's `index`, root/main = `-1`), threaded into the
initial `ThreadStartOperation`. That is the only structural ancestry Fray records; it is *not*
combined into a composite path today (see §6).

## 2. Two different meanings of "stable"

```
   stable across REPLAYS      = re-run the SAME recorded schedule      <- index gives us this
   stable across SCHEDULES    = the DIFFERENT interleavings Fray        <- feedback needs this,
                                explores during a search                  index does NOT guarantee it
```

Replay re-executes one fixed schedule. A feedback-guided search (and the genetic scheduler) runs a
*different* interleaving every iteration and compares what it observed. These need different
guarantees, and `index` only provides the first.

## 3. Why `index` is replay-stable but not schedule-stable

`index` is assigned inside `threadCreateDone` (`RunContext.kt:461`):

```kotlin
val ctx = ThreadContext(t, registeredThreads.size, this, parentContext.index)
```

`threadCreateDone` is a Fray-instrumented hook that runs **on the parent thread, only when the
scheduler advances that parent past its thread-creation call**. So the order in which threads grab
`registeredThreads.size` — and thus their `index` — is a **sequence of scheduling decisions**, not a
property of the program text.

- **Same schedule (replay):** the scheduler makes the same decisions in the same order, so threads
  are created in the same order and get the same `index`. This is exactly why `ReplayScheduler` can
  match recorded steps by `index` (`it.index == rec.scheduled`) and replay faithfully.
- **Different schedules:** if the interleaving lets threads be *born in a different order* — common
  when creation is data-dependent, racy, or pooled — the same logical thread gets a different
  `index`. Birth order is the thing the search *varies*, so any birth-order id varies with it.

**Verdict:** `index` is a correct id for replay and for within-one-iteration bookkeeping. It is *not*
a correct cross-schedule logical identity, and `thread.id` is not stable at all.

## 4. Why "creation-site + counter" does **not** fix it

A natural proposal: identify a thread by `(locationHash, counter)` — hash the *code location where
the thread was created*, and disambiguate multiple threads born at the same site with a per-site
counter. The `locationHash` half is genuinely sound (a source line does not move when you
reinterleave). The **counter half is not**, and it is load-bearing.

**The core problem:** the counter is bumped in the same scheduler-controlled `threadCreateDone` hook
as `index` — so "how many threads have been created at this site so far" is *again* a scheduler
decision, not a program property.

**When it holds (a narrow regime):** exactly one producer ever hits the site, with no preemptible
operation between consecutive creations, unconditional fixed-count creation, and one logical role
per dedicated source line. But that is precisely the regime where each site makes ~one thread, so
the counter is redundant and `locationHash` alone would do — the scheme buys nothing where it works.

**When it breaks (the cases real programs hit):**

| # | Scenario | Why it breaks | Severity |
| --- | --- | --- | --- |
| F1 | **Thread pools** (`ExecutorService`, `ForkJoinPool`) | A pool worker is born deep in `java.util.concurrent` (`ThreadPoolExecutor.addWorker` / `DefaultThreadFactory`), **not** at your `submit()` line. Every pool worker in the program shares **one** JDK-internal `locationHash`; the counter degenerates to a single global worker number. (Fray's own `SURWScheduler` already treats any `java.util.concurrent` frame as identifying nothing user-meaningful.) | fatal |
| F2 | **Lazy worker creation** | Pools spin up workers on demand; *how many* and *in what order* is schedule-dependent. `counter == 3` may name a worker that does not exist in another schedule. | fatal |
| F3 | **Concurrent producers at one site** | The bump happens on whichever parent the scheduler runs first. Schedule A → `{X:0, Y:1}`; schedule B → `{Y:0, X:1}`. `(site, 0)` names a different logical thread run-to-run — a false merge. | fatal |
| F4 | **Worker reuse ≠ task identity** | A handoff cares about the *task* that touched a resource; `(locationHash, counter)` names the recycled *worker*. 2 workers running 100 tasks → only 2 ids. | fatal (wrong unit) |
| C1 | **Shared `spawn()` helper** | A wrapper makes `new Thread` the same `Class.method:line` for every caller; the 2-frame hash sees the helper, not the distinguishing caller. Distinct roles collapse to one `locationHash`; only the counter discriminates → pure birth order. | common |
| C2 | **Loop-spawned workers** | One site; counters assigned in scheduler-chosen creation order, not loop-index order. | common |
| C3 | **Conditional creation** | The counter counts only creations that happen; a racy guard makes a different subset spawn across schedules, shifting every later counter. | common |

**The deep reason (and the link to spawn-path):** *any* counter / index / birth-order id is assigned
in a scheduler-controlled hook, so "which thread" is a quantity Fray **varies**, while "which code
location" is not. This is the **same fragility** as a spawn-path's sibling-birth-order tiebreak —
both increment a counter in the same hook. They differ only in blast radius: a spawn-path scopes its
counter **per parent** (small race surface, never merges two live threads), whereas a global
per-site counter scopes it **across all parents** and **collapses to one bucket under pooling** — the
worst case. (A per-`(site, parent)` counter doesn't help: the id becomes `(locationHash, parentId,
counter)`, `parentId` is itself birth-order, and you've just rebuilt a spawn-path edge with a
location tag.)

## 5. How the instability affects the existing schedulers

Short version: **the black-box schedulers are not corrupted by cross-schedule instability, because
none of them compares across schedules.** Each iteration is an independent sample; they only need
*per-run* determinism (for replay), which they get from the seeded `ControlledRandom`. But several of
them are quietly coupled to a JVM-`thread.id` *ordering*, which is worth understanding.

**The shared mechanic.** Before any scheduler runs, `RunContext.getEnabledOperations` builds the
candidate list and sorts it by JVM id:

```kotlin
enabledOperationBuffer.sortBy { it.thread.id }   // RunContext.kt:1383
```

The *absolute* `thread.id` values are not stable across runs, but their *relative order* tracks
thread-construction order, which (like `index`) is scheduler-controlled and therefore reproduced
under a fixed schedule. So everything below is deterministic for replay; the coupling only becomes a
latent hazard if construction order ever stops being deterministic under a fixed schedule.

| Scheduler | How it picks | Dependence on thread identity | Affected by cross-schedule instability? |
| --- | --- | --- | --- |
| **Random** (`RandomScheduler`) | `threads[rand.nextInt() % size]` | Picks by **position** in the `thread.id`-sorted list. The seeded RNG makes the draw deterministic; the list order makes *which thread that lands on* depend on the `thread.id` sort. | No — it's an independent sample each iteration; nothing is compared across schedules. Replay is faithful (same seed + same construction order ⇒ same list ⇒ same pick). |
| **FIFO** (`FifoScheduler`) | `threads.first()` | Returns the **lowest-JVM-id** runnable thread. Fully determined by the `thread.id` sort order. | No (deterministic). But note it is *defined by* JVM-id order, an implementation detail — semantically it means "lowest construction-order runnable thread". |
| **POS** (`POSScheduler`) | min by a random per-thread priority | Keys `threadPriority` on **`ThreadContext` object identity** (stable within a run); assigns priorities in the order threads appear in the candidate list. | No — object identity is fine within an iteration; priorities reset each iteration. List order only affects RNG-draw assignment, which replays identically. |
| **PCT** (`PCTScheduler`) | priority queue, random insert position + switch points | Inserts new threads at `rand % (size+1)`; the order they're *encountered* follows the `thread.id`-sorted list. Keys on object identity. | No — same reasoning as POS. |
| **SURW** (`SURWScheduler`) | weight-propagating walk over the spawn tree | **Consumes `index` and `parentId` semantically** — builds `childThreads[parentId] += child.index` and propagates scheduling weight parent→child. | No for correctness (within-run), but it is the scheduler **most coupled to the meaning of `index`**: it relies on the spawn-tree structure that `index`/`parentId` encode. |
| **Replay** (`ReplayScheduler`) | replays recorded `index` steps | **Load-bearing on `index` being replay-stable** — matches `threads.firstOrNull { it.index == rec.scheduled }`. | This is the one place a stable-*per-replay* identity is essential, and it holds (§3). It does **not** need cross-schedule stability — it replays one fixed schedule. |
| **Genetic** (`GeneticScheduler`) | positional `genome[i] % size` decode | Decodes genes **positionally** against the candidate list; separately, its feedback (`HandoffFeedbackSource`) keys handoffs on `index`. | **Yes — this is the one that's actually hurt.** (a) Positional decoding is the schedule-drift problem (caveat **C1**). (b) Its handoff feedback compares `index`-keyed features across schedules, so an unstable `index` makes those features noisy. |

**The takeaway:** thread-id instability is *latent* for the black-box samplers (they'd only break if
construction order became non-deterministic under a fixed schedule — it isn't today) and *active*
only where Fray (a) compares across schedules — the **feedback layer** — or (b) decodes positionally
against the candidate list — the **genetic scheduler** (C1). Replay depends on per-replay stability,
which `index` provides.

## 6. Candidate stable identities

| Scheme | Stable across schedules? | Survives thread pools? | Thread-semantic? | Complexity |
| --- | --- | --- | --- | --- |
| **`thread.id`** (JVM) | No | No | yes (a thread) | trivial (status quo for the sort) |
| **`index`** (birth order) | No (only across replays) | No | yes | trivial |
| **creation-site + counter** | No — counter = scheduler birth order (only the `locationHash` half is invariant) | **No** — all pool workers collapse to one JDK site | partly (names worker, not task) | medium |
| **spawn-path** (`"0.1"`) | **Under no-pools + deterministic creation: yes** (sibling ordinal = parent's own program order; only C3 racy-creation breaks it, and then nothing can). In the general/pooled case: No — sibling tiebreak is birth order, but scoped **per parent** (smaller blast radius; **never merges two live threads** — fixes W1) | No (pooled); N/A under the no-pools assumption | yes (family-tree position) | **low–medium** — `SURWScheduler.checkNewThreads` already reconstructs the tree observationally from `(index, parentId)`; reuse it in the source, add only a per-parent *ordinal* (don't reuse its unordered `Set`). No `ThreadContext`/`RunContext` change. |
| **operation code-location** (`op.stackTraceHash`) | **Yes** — a source line is schedule-invariant | **Yes** — keys "where in code", immune to worker reuse | no (names the op site, not a thread) | **low** (already first-class; ~1-line `accessId` flip) |
| **operation execution index** (EI, §7) | **Yes — optimally** (stable iff the control flow is data-deterministic; see §7) | **Yes** — distinguishes tasks sharing a pooled worker, which `stackTraceHash` cannot | no (names the *access*, by the control context reaching it) | high (full EI) / **low–medium** for the cheap approximation |

## 7. Execution indexing: the principled fix

The candidates above are all *heuristics* — each fails somewhere. There is a named technique that is
the **information-theoretically optimal** answer to "name a point so it aligns across different runs":
**execution indexing (EI)** — Xin, Sumner & Zhang, *Efficient Program Execution Indexing*, PLDI 2008.
Its flagship application is *data-race classification on a thread-pool/work-stealing benchmark*, i.e.
almost exactly this problem, so this is **prior art, not a new idea**.

**What it is.** EI gives a dynamic execution point a *structural name* built from the program's
**dynamic nesting structure** that reached it: the enclosing chain of (a) **method calls** (the call
stack), (b) **loop iteration counts** (per-loop counters), and (c) **taken branch / region nesting**.
It is maintained incrementally on a per-thread "indexing stack" (push on entering a region/call, pop
at its post-dominator/return). Two points in two runs **correspond iff their indices are identical**.
It is strictly stronger than `stackTraceHash` (a static 2-frame call-site fingerprint with no
iteration count): the same line in loop iteration 1 vs 2 gets distinct indices (`[2,3,9]` vs
`[2,2,3,9]`), and the same code via a different call path gets another (`[5,9]`).

**Why it is schedule-stable — the one insight.** A thread executes *its own* instructions in program
order regardless of interleaving. Scheduling changes *when* a thread runs relative to others, not the
intra-thread order of its own calls/branches/loop back-edges. Since an EI is computed entirely from
**one thread's local control-flow history**, it is invariant to interleaving. It changes across
schedules **only** when that thread's own control flow takes a different path — i.e. it branches or
loops differently on a value that is itself schedule-dependent (a racy read). EI thus replaces the
vague "what is this thread's identity under scheduling?" with the precise "**is this thread's control
flow racy?**".

**Use it as an OPERATION identity, not a THREAD identity.** Two ways to apply EI; only one works:
- *(A) recursive EI as a thread id* (compose the EI of the creation call with the parent's id) —
  **does not work.** Pool workers are born at a JDK-internal site fired by whichever submitter wins a
  demand race, so the parent's context at birth is itself schedule-dependent. This just relocates the
  birth-order fragility — the same reason creation-site+counter (§4) fails.
- *(B) EI of the operation* (the racing access point), dropped in where `accessId` returns today —
  **this is the answer.** It names the access by the control context of the *task* reaching it, a
  property of the work being done, not of the recycled carrier thread or the scheduler-assigned birth
  order.

How operation-EI maps to the §4 failure modes:

| Mode | operation-EI | Why |
| --- | --- | --- |
| **F1** pools collapse the creation-site hash | **fixed** | EI never names a thread by birth site; it names the *access*. |
| **F4** worker reuse ≠ task identity | **fixed — the headline win** | One worker running 100 tasks → **100 distinct, run-stable ids, not 2**: each task's dequeue-loop iteration + per-task call context differ. `stackTraceHash` collapses them (same `run()` line); EI separates them. |
| **F2 / C2** lazy / loop-spawned workers | **fixed** | The k-th work unit is named by the access's enclosing-loop counter, not a scheduler-bumped tally — provided the trip count is data-determined. |
| **F3** concurrent indistinguishable producers | **partial** | Separates X's child from Y's child iff X and Y already have distinct contexts; if they are truly symmetric it cannot — and should not — separate them. |
| **C3** conditional / racy creation | **fixed (no false merges)** | EI never invents a shared name for an access that exists in one schedule and not another. Complementary to `HitBucket` (which fixes *frequency*); EI fixes *identity*. |

**The fundamental boundary (why this is optimal, not just good).** EI is schedule-stable *exactly to
the degree the relevant control flow is data-deterministic*. Where the control flow that selects the
accessor itself races on the schedule, **no naming scheme can be stable — the entity genuinely does
not recur**, and any scheme that emitted a stable shared name there would be *false-merging two
different events*. EI's refusal to name those cases is correct, not a defect: it aligns everything
alignable and separates everything that genuinely differs. The birth-order family (`index`,
creation-site+counter, spawn-path) is *strictly worse* — it breaks even on **nameable**,
data-deterministic cases (pools, loops); EI's only failures are the genuinely unnameable ones.
(Caveat: EI assumes the program is deterministic modulo the schedule — true under Fray's fixed-input
assumption. And EI fixes endpoint *identity*, not happens-before *causality* — the temporal-adjacency
gap in the handoff source is a separate, larger effort.)

**Cost in Fray, and the upgrade ladder.** Full EI needs instrumentation Fray does **not** have: it
hooks concurrency ops + method enter/exit, but has no loop-back-edge or branch instrumentation. Full
EI would instrument every loop header and conditional on the **hot path of all user code** — a change
of cost *class* (today Fray pays only at concurrency ops), overkill for a search heuristic. But a
cheap approximation captures most of the value on **existing hooks**:

```
0. TODAY:   accessId = scheduled.index                  birth-order, schedule-fragile (the bug)
1. RUNG 1:  accessId = op.stackTraceHash                the one-line fix; kills thread renumbering
2. RUNG 2:  + per-(thread, location) occurrence counter cheap-EI: recovers loop-iteration coord, NO
                                                        new instrumentation (F4/C2 win)
3. RUNG 3:  deepen stackTraceHash -> bounded call-context hash   (method enter/exit already tracked)
4. RUNG 4:  full EI (loop/branch instrumentation + semantic anchors)
```

Rung 2 is the knee: a `Map<(thread, stackTraceHash), Int>` bumped in `onNewSchedule` (single-threaded
in the scheduler critical section, so no locking) names "the k-th time *this thread* hit *this
location*". Because that counter advances in the thread's own program order, the k-th hit is the same
dynamic event regardless of interleaving — exactly EI's loop-iteration coordinate. It misses only
branch nesting and deep call context. (The PLDI paper's "semantic anchor" — hash the work item at the
dequeue site — is the sanctioned fix for *symmetric* pooled workers, relevant at rung 4.)

### What rung 1 does when several threads run the *same* code

Rung 1's cost is sharper than "can't tell two threads on the same line apart", because of the
handoff source's **self-access guard**. `HandoffFeedbackSource.onNewSchedule` records a handoff only
when consecutive accessors of a resource *differ*:

```kotlin
val current  = Access(accessId(op, scheduled), op.type)
val previous = lastAccess.put(op.resource, current)
if (previous != null && previous.id != current.id) {   // skip a thread re-touching its own resource
    handoffHits.merge(featureId(op.resource, Handoff(previous, current)), 1, Int::plus)
}
```

Under thread-keying, `id` is the thread, so the guard only skips genuine self-accesses. Under rung 1,
`id` is the *code line* — so when two **different** threads touch one resource at the **same** line,
`previous.id == current.id`, the guard reads it as a self-access, and **the handoff is suppressed
entirely** (not just collapsed). The feature goes *silent*. Three cases, and they behave very
differently — this is where the knee between rungs lives:

| Case | Example | Rung 1 | Fix |
| --- | --- | --- | --- |
| **Different lines** — producer/consumer (the common, useful case) | producer writes at `Lp`, consumer reads at `Lc`, `Lp ≠ Lc` | **Records `Lp→Lc`, and it is schedule-stable.** Strictly *better* than thread-keying — and "where the data flowed" is arguably what a handoff *is*. | none needed — rung 1 wins |
| **Same line, same thread, different iterations** (F4: one pooled worker, many tasks at one `run()` site) | worker loops, hitting line `L` each task | Collapses all the worker's hits to one id (loses per-task distinction) | **rung 2** — `(hash(L), k)` makes the k-th hit distinct, stable in that worker's own program order |
| **Same line, *different* threads, same iteration** (F3: genuinely symmetric concurrent workers) | N identical workers all at line `L` on one lock | **Handoff suppressed** (self-access guard) — silent | only a **rung-4 semantic anchor** (hash the work item / data each worker carries), and only if that data actually differs |

The third case is the §4 boundary made concrete: truly symmetric workers are **unnameable across
schedules** — there is no run-independent fact distinguishing "worker A" from "worker B", so any id
that separated them would be re-inventing the scheduler's birth-order noise. EI's silence there is
*correct*. And note the handoffs rung 1 drops in that case were **already noise** under thread-keying
(the `1→2` pair meant a different logical pair every run because `index` reshuffles) — so rung 1
trades misleading signal for honest silence, with rungs 2/4 as the upgrade path when the silence
actually costs coverage.

## 8. Recommendations

> **Superseded for the thread-scheduler / no-pools case — see "Scope & revision" at the top.** This
> section recommends rung 1 because it optimizes for *naming stability* (a classification objective)
> and for *pool-immunity*. With pools assumed absent and the consumer being a *steered scheduler that
> acts on threads*, the recommendation flips: **keep handoff thread-keyed and upgrade its key from
> `index` to a spawn-path**, because rung 1's same-line self-access suppression deletes the
> per-decision observation a scheduler needs, and `location` coverage already exists as its own
> source (so rung 1 is redundant, not additive). The §7 *mechanics* (what rung 1 does, the rungs, EI)
> remain accurate; the *choice* below does not apply to this consumer.

**For stable handoff feedback (now): climb the §7 ladder, starting at rung 1.**
1. **Ship rung 1** — change `HandoffFeedbackSource.accessId()` from `scheduled.index` to
   `op.stackTraceHash` (the seam its own doc names). ~One line, zero birth-order dependence,
   schedule-invariant, immune to the fatal pool/reuse cases (the resource is touched at a stable user
   line regardless of which recycled worker runs it). Its cost — several threads at the *same* line
   collapse to one id, and the handoff source's self-access guard then *suppresses* the handoff
   between them (see "What rung 1 does when several threads run the same code", §7) — is far smaller
   than the false merges/splits a birth-order counter produces under pooling, and the dropped pairs
   were already noise under thread-keying. (Forces `RacingOperation.resolveRacingOperationStackTraceHash
   = true`, the same flag the `location` source already enables.)
2. **Hold rung 2 (the per-`(thread, location)` occurrence counter) as a triggered upgrade** — add it
   the moment `stackTraceHash` collisions are observed hurting the signal (most likely from F4: one
   worker, many tasks, same `run()` line). Cheap, no new instrumentation, and where the operation-EI
   win actually lands within Fray's current hooks.
3. **Do not build full EI for feedback.** It is a search heuristic; full control-flow instrumentation
   changes Fray's cost class and is overkill. Rungs 1–2 capture the bulk of the available alignment.
4. Do **not** pursue EI-as-thread-identity (form A, §7) at all.

**For the C1 choice-point-keyed scheduler (later): this is where full EI earns its cost.** A
grey-box scheduler keying decisions on choice points needs a *finer* structural name than feedback
does (recursion, shared helpers, many iterations of one line). That is the case for **rung 3**
(deepened call-context hash) and, if proven insufficient, **rung 4** (full EI + semantic anchors).
Sequence it: prove the cheap keys (rungs 1–2) too coarse *for the scheduler specifically* before
paying for control-flow instrumentation. If a thread-*relative* id is wanted instead, prefer a
**spawn-path** over creation-site+counter (it localizes birth-order fragility to one subtree and
preserves parent structure under pooling) — but the stronger position is to key on **operation
identity** (EI), because *which thread* is scheduler-determined in Fray while *which execution point*
is not.

## Appendix A: worked examples — where each scheme breaks

The tables in §3, §4 and §7 are abstract. Here is each failure as a small program you can hold
in your head — plain JVM threading (the *program under test*), nothing Fray-internal. In each,
"schedule A" and "schedule B" are two interleavings a search explores in successive iterations,
and the bug is that an identity scheme gives **the same name to different events** (a false merge)
or **different names to the same event** (a false split) between them. Every example assumes — as
*any* birth-order scheme must — that an id/counter is stamped at a scheduler-controlled moment;
that assumption is the whole reason the family is fragile.

### W1 — `index` / birth order: the false merge (§3)

Two independent subsystems boot concurrently; each, on its own thread, spawns one worker, and both
workers later touch one shared resource `R`:

```java
new Thread(() -> new Thread(cacheWarmer).start(),    "boot-cache").start();
new Thread(() -> new Thread(metricsFlusher).start(), "boot-metrics").start();
```

Which worker is *born* (registered) first is decided by which boot thread the scheduler advances
to its `start()` first — a scheduling decision, not a fact about the program text. Say the two
workers take consecutive indices `{3, 4}`, and (for this example) each touches `R` once right after
starting, so the order `R` is accessed follows birth order — the born-first worker touches it first:

| | boot-cache wins the race | boot-metrics wins the race |
| --- | --- | --- |
| `cacheWarmer` index | 3 | 4 |
| `metricsFlusher` index | 4 | 3 |

A handoff feature keyed on the index — `R: 3→4` — therefore means **`cacheWarmer→metricsFlusher`**
in schedule A and **`metricsFlusher→cacheWarmer`** in schedule B. Same feature id, opposite event.
The search sees `3→4` again and concludes "already explored", when B is exactly the *mirror*
interleaving it was trying to reach. Birth order is the quantity the search varies, so any id built
on it aliases the variations onto each other.

### W2 — creation-site + counter: thread pools (F1, F4, F2)

```java
ExecutorService pool = new ThreadPoolExecutor(   // grows workers on demand, 0..2
    0, 2, 60, SECONDS, new SynchronousQueue<>());
int[] shared = new int[100];                     // the resource the tasks pass around
for (int i = 0; i < 100; i++) {
    int row = i;
    pool.submit(() -> shared[row] = compute(shared, row));  // your line — NOT where the carrier is born
}
```

Three failures stack:

- **F1 — collapse.** The carrier threads are constructed deep inside the pool's thread factory,
  at *one* JDK-internal site — never at your `submit` line. Every worker the program will ever run
  shares a single creation-site hash, so the per-site counter degenerates to a flat global
  "worker 0 / worker 1".
- **F4 — wrong unit.** The 100 tasks pass `shared` around, but only two ids exist: the recycled
  carriers. The handoff you actually want — "the task computing row 57 handed `shared` to the task
  computing row 58" — is unrepresentable; `(site, counter)` can only say "worker 0 → worker 1", the
  same pair for all 100 tasks. The id names the *carrier*, not the *work*.
- **F2 — lazy creation.** With an on-demand pool (above), whether the second worker is spun up at
  all — and when — is schedule-dependent: if the first worker dequeues fast enough to serve the next
  task before the pool grows, only one carrier is ever created, and `counter == 1` names a worker
  some schedules never produce.

This is the regime §4 calls *fatal*: the only sound half of the scheme (the location hash) is
exactly the half a pool destroys.

### W3 — creation-site + counter: the counter races even at a fixed site (F3, C3)

The counter is sound only if "how many threads have been created here so far" is a program fact.
Two ways it isn't, both at a single fixed site:

**Concurrent producers (F3).** Two handlers run concurrently and spawn a background task at the
*same* line:

```java
Runnable handler = () -> new Thread(this::flush).start();   // site S, run by both X and Y
```

`(S, 0)` is X's flush-task when the scheduler runs X's `start()` first, and Y's flush-task when it
runs Y's first — a different logical thread run-to-run, i.e. a false merge at one fixed site.

**Conditional creation in a loop (C3).** The subset that spawns depends on a racy guard:

```java
for (Item it : items) {
    if (it.isDirty())                        // isDirty() reads concurrently-mutated state
        new Thread(() -> flush(it)).start(); // site S
}
```

If a different subset is dirty across schedules, the counter — which counts only the creations that
*happen* — shifts for every item after the first divergence. The worker for item #7 is `(S, 2)`
when only `{0, 3, 7}` were dirty and `(S, 4)` when `{0, 1, 2, 5, 7}` were. The id moved without the
program's text moving.

### W4 — rung 1 (`op.stackTraceHash`): where it wins, goes silent, and collapses (§7)

Rung 1 keys a handoff endpoint on the access's *code line*, not the thread. Three outcomes, matching
the §7 "what rung 1 does when several threads run the same code" table:

**Wins — different lines (the common, useful case).** Producer and consumer touch the buffer at
distinct lines:

```java
buffer[head++] = item;   // producer, line Lp  (write)
x = buffer[tail++];      // consumer, line Lc  (read)
```

The handoff `Lp→Lc` is schedule-invariant (a source line doesn't move when you reinterleave) and
reads as "data flowed from the produce site to the consume site" — arguably *what a handoff is*.
Strictly better than thread-keying here, with zero birth-order dependence.

**Goes silent — same line, *different* but symmetric threads (F3).** N identical workers each
acquire one shared lock at one line:

```java
ticketLock.lock();   // line L — every worker acquires the same lock here
try { serve(); } finally { ticketLock.unlock(); }
```

Both endpoints of any worker→worker pass hash to the *same* id `hash(L)`, so the "two **different**
accessors" condition a handoff requires is never met — the handoff is **suppressed**, not merely
collapsed, and the feature goes silent. This is correct: symmetric workers are genuinely unnameable
across schedules (the §4 / §7 boundary), and the pairs rung 1 drops here were already noise under
thread-keying (the `1→2` index pair named a different logical pair every run). Rung 1 trades
misleading signal for honest silence.

**Collapses — same line, *same* worker, many tasks (F4).** One pooled worker loops over tasks,
recording each at one line:

```java
while ((task = queue.poll()) != null)
    results.add(task.run());   // line L, hit once per task by the one carrier
```

Rung 1 gives all of this worker's accesses one id `hash(L)`, losing the per-task distinction. This
is the case **rung 2** repairs: a per-`(thread, location)` occurrence counter names "the k-th time
this worker hit L", stable in the worker's own program order, recovering one id per task with no new
instrumentation — exactly the operation-EI loop-iteration coordinate, and the headline win in the §7
table.

## Key code references

- `core/.../ThreadContext.kt:12` — `ThreadContext(thread, index, context, parentId)`; `:28`
  `parentId` → `ThreadStartOperation`.
- `core/.../RunContext.kt:261` — main registered with `index = 0`, `parentId = -1`; `:431-473`
  `threadCreateDone` (child `index = registeredThreads.size`, `parentId = parentContext.index`);
  `:1383` candidate list `sortBy { it.thread.id }`.
- `core/.../scheduler/ReplayScheduler.kt` — matches `it.index == rec.scheduled` (replay key).
- `core/.../scheduler/SURWScheduler.kt` — builds the `parentId → index` spawn tree.
- `core/.../feedback/sources/HandoffFeedbackSource.kt` — `accessId()` seam (`scheduled.index` →
  `op.stackTraceHash`).
- `core/.../concurrency/operations/RacingOperation.kt` — `stackTraceHash` and
  `resolveRacingOperationStackTraceHash`.

## References

- Bin Xin, William N. Sumner, Xiangyu Zhang. **Efficient Program Execution Indexing.** PLDI 2008.
  (The execution-indexing technique in §7; its flagship application is data-race classification on a
  thread-pool / work-stealing benchmark — the same cross-schedule operation-identity problem.)
