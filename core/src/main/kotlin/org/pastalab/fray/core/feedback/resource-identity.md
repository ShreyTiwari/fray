# Resource Identity & Cross-Schedule Stability in Fray

> Design note. How Fray names a **shared resource** (the `Int` a `RacingOperation` carries), why that
> name is **stable within one iteration** but **not across the different schedules** the feedback
> layer compares, which subsystems that does and does not hurt, and which stable-naming schemes do
> and don't work for resources.
>
> Audience: anyone touching the schedulers (`core/.../scheduler/`) or the grey-box feedback layer
> (`core/.../feedback/`). This is the **resource-axis companion** to
> [`thread-identity.md`](thread-identity.md), which does the same analysis for *thread* identity.
> Read that one first — this note reuses its vocabulary (replay-stable vs schedule-stable;
> program-determined vs scheduler-determined) without re-deriving it.
>
> **Status: the §5 recommendation is now implemented.** `HandoffFeedbackSource` keys its resource
> bucket on the access code location (`op.stackTraceHash`), not the raw `op.resource`. The analysis
> below stands as the *why*; passages written as "the unaddressed hole" / "proposed" are kept for the
> argument but are marked where the code has moved past them. Demonstrated by
> a resource-identity stability test (flips STABLE↔UNSTABLE with the fix).

## TL;DR

- A "resource id" in Fray is `RacingOperation.resource: Int`. It is overwhelmingly
  **`System.identityHashCode(obj)`** (with run-stable arithmetic for arrays/unsafe/fields layered on
  top). That makes it the resource-side counterpart of the JVM **`thread.id`** — a JVM-assigned
  handle — **not** of `ThreadContext.index`. If anything the resource axis is *less* structured than
  the thread axis: `index` is at least program-order-adjacent birth order; `identityHashCode` is
  purely JVM-internal.
- **Within one iteration**, for a fixed live object, the id is **stable** (the JVM caches
  `identityHashCode` in the object header; it survives GC moves). So resource ids correctly answer
  "which lock/field is this" *within a run*.
- **Across iterations / across schedules** the id is **not stable**: each iteration re-runs the test
  body and **re-allocates** the user's objects, and `RunContext.done()` clears every identity-keyed
  manager map. The same logical lock gets a fresh `identityHashCode` every iteration.
- **Across JVM runs** the `Object.hashCode` contract gives **no guarantee** of stability — Fray must
  not rely on it (even where a given HotSpot mode happens to reproduce sequences).
- **Blast radius is one consumer.** Only **`HandoffFeedbackSource`** is corrupted by cross-schedule
  resource-id instability: it folds the raw `op.resource` int into a feature compared across the
  iterations the genetic search explores. *Every* other consumer is immune — all black-box schedulers
  (Random/POS/PCT/FIFO/SURW), replay/recording, deadlock detection, and even `ResourceOrderingCoverage`
  (it re-maps the raw id to a dense within-run id before any cross-iteration comparison).
- **This was the same file you'd already half-fixed; it is now fully fixed.** The handoff feature is
  `<resource-bucket> <from-thread> -> <to-thread>`. `thread-identity.md`'s spawn-path work made the
  *thread endpoints* schedule-stable; the *resource bucket* was left on the raw, unstable int — the
  hole this note identified. It is now keyed on the access code location (`op.stackTraceHash`), so the
  feature reads `loc=<stackTraceHash> <from> -> <to>` and is schedule-stable on both axes.
- **There is no clean "spawn-path of a resource."** A thread has an immutable, program-determined
  parent lineage (a tree); a heap object does not. So the schedule-stable options for resources are
  all either access/allocation-**site proxies** (cheap, already available, but over-merge) or full
  allocation-context **execution indexing** (an identity, but needs allocation instrumentation Fray
  deliberately lacks). The realistic fix is a schedule-stable *proxy* with a documented over-merge
  boundary, scoped to `HandoffFeedbackSource` — not a universal stable resource identity.

---

## 1. The resource id and what it is made of

The base class is:

```kotlin
abstract class RacingOperation(val resource: Int, val type: MemoryOpType) : Operation()   // RacingOperation.kt:7
```

`MemoryOperation` (shared-memory r/w), `LockLockOperation` (any lock/monitor/semaphore acquire),
`ObjectWaitOperation` (wait/await/latch), and the NIO/reactive ops all extend it. Contention is decided
purely on the int: `MemoryOperation.isRacing` is `op.resource == resource` plus a write on either side
(`MemoryOperation.kt:8`); `LockLockOperation.isRacing` is `op.resource == resource`.

There is also a **second, separate "resource" channel**: `ResourceInfo(resourceId, type)` carried by
`Acquirable` contexts and `BlockedOperation`s, surfaced to *remote* schedulers as
`ThreadInfo.waitingFor` / `acquired` (`ThreadContext.kt:70,78`). It is a deadlock-report descriptor, also
`identityHashCode`-derived, and — like the racing channel — never compared across schedules. The rest
of this note is about channel (1), `RacingOperation.resource`, which is what the feedback layer reads.

### Construction map (every primitive)

All line numbers are in `core/src/main/kotlin/org/pastalab/fray/core/` unless noted.

| Primitive | `resource` expression | Unstable term | Run/schedule-stable sub-term |
| --- | --- | --- | --- |
| **Instance field** r/w | `31*(31 + identityHashCode(obj)) + name.hashCode()` — `RunContext.kt:1112-1114` | `identityHashCode(obj)` | field `name.hashCode()` |
| **Static field** r/w | same, but `obj==null` ⇒ `owner.hashCode()` replaces the identity hash | — | **fully stable** (pure `String` hashes) ✅ |
| **Atomic** (`j.u.c.atomic.*`) | `identityHashCode(obj)` — `RunContext.kt:1118` | all of it | — |
| **Array element** | `31*identityHashCode(obj) + index` — `RunContext.kt:1125-1126` | `identityHashCode(obj)` | element `index` |
| **Unsafe volatile** | `31*identityHashCode(obj) + offset.toInt()` — `RunContext.kt:1131-1132` | `identityHashCode(obj)` | field byte `offset` |
| **ReentrantLock / monitor / Read/Write/Stamped lock / Semaphore acquire** | `LockLockOperation(identityHashCode(obj))` — `LockLockOperation.kt:6`; lock-blocked state via `LockContext.kt:11` `Acquirable(ResourceInfo(identityHashCode(lock), LOCK))` | all of it | — |
| **CountDownLatch await** | `ObjectWaitOperation(identityHashCode(latch))` — `RunContext.kt:1147` | all of it | — |
| **Object.wait / Condition.await** | `ObjectWaitOperation(identityHashCode(waitingObject))` — `RunContext.kt:616` | all of it | — |
| **LockSupport.park** | `ResourceInfo(thread.id.toInt(), PARK)` — `ParkBlocked.kt` | JVM `thread.id` | — (names the *parked thread*, not a heap object) |
| **Thread.sleep** | constant `0` — `SleepBlockedOperation.kt` | — | stable but non-distinguishing |
| **NIO select / socket-read / accept (racing)** | hardcoded `0` — `SelectorSelectOperation.kt:6` et al. | — | **`// FIXME(aoli): figure out resource id for network ops`** — all NIO racing ops alias to one feature |
| **Reactive blocking** | constant `-1` (`REACTIVE_BLOCKING_RESOURCE_ID`) | — | stable but non-distinguishing |

All four memory variants funnel through `memoryOperation(obj: Int, type)` (`RunContext.kt:1135`), which sets
`pendingOperation = MemoryOperation(obj, type)`. Field accesses are gated: `fieldOperation` early-returns
unless `interleaveMemoryOps` is on **or** the field is `volatile` (`RunContext.kt:1110`).

### Interning vs. the exposed id — a load-bearing distinction

The per-primitive **contexts** (`lockManager`, `signalManager`, `latchManager`, `semaphoreManager`,
`stampedLockManager`) are all `ReferencedContextManager<T>` over a **`WeakIdentityHashMap`**
(`ReferencedContextManager.kt:6`). That map keys by **reference `==`** (`WeakIdentityHashMap.java:171-177`),
using `identityHashCode` only as a bucket hint. So *context lookup* is correct even under a hash
collision — two distinct live objects never share a context.

The fragility is entirely in the **exposed `Int`**: it arithmetic-combines `identityHashCode` and is then
used as a *direct map key* by the int-keyed consumers. Those can alias two distinct resources to one key.
`SynchronizationManager.synchronizationPoints` is `Map<Int, Sync>` keyed on `identityHashCode` and even
asserts non-collision (`SynchronizationManager.kt:6,15`); `HandoffFeedbackSource.lastAccess` is keyed on
`op.resource` (`HandoffFeedbackSource.kt:119`).

---

## 2. The three notions of "stable", applied to resources

```
   stable WITHIN one iteration   = a fixed live object keeps its id        <- identityHashCode gives this
   stable ACROSS iterations      = the same logical object across re-runs  <- NOT given (object reallocated)
   stable ACROSS schedules       = the different interleavings the search  <- feedback needs this; NOT given
                                   compares
```

| Scope | Stable? | Mechanism / evidence |
| --- | --- | --- |
| **Within one iteration**, fixed live object | **Yes** | `Object.hashCode` contract: consistent for one object during one execution. HotSpot caches it in the object header (survives GC moves). Field/array/unsafe ids are deterministic functions of it + run-stable terms (`RunContext.kt:1112-1132`). |
| **Across iterations** (one Fray process) | **No** | Each iteration re-executes the test body and **re-allocates** the user's objects (CLI: fresh `RunContext` + worker per iteration, `TestRunner.kt`; JUnit: reused `RunContext` but `done()` each iteration). `RunContext.done()` clears `lockManager/signalManager/stampedLockManager/semaphoreManager/latchManager/nioContextManager/hashCodeMapper` (`RunContext.kt:275-297`). A reused logical lock ⇒ new object ⇒ new `identityHashCode`. |
| **Across JVM runs** | **Not guaranteed** | `java.lang.Object#hashCode` Javadoc: the value "need not remain consistent from one execution of an application to another." Fray must not rely on cross-run stability even where a given HotSpot default mode happens to reproduce sequences. |

**The right analogy.** `identityHashCode` is the resource counterpart of the JVM **`thread.id`**
(JVM-assigned, never a program fact), **not** of `ThreadContext.index` (scheduler-assigned birth order, but
at least program-order-adjacent). The thread axis at least had a tree of births to exploit; the resource
axis has only an opaque address-derived hash.

---

## 3. How the instability affects each consumer

Short version: **only `HandoffFeedbackSource` is corrupted.** Everything else uses the resource id only
*within* one iteration (to answer "which lock/object is this"), or doesn't read it at all — so, exactly
like the black-box schedulers are immune to thread-id instability, they are immune here.

| Consumer | How it uses `op.resource` | Cross-schedule comparison? | Affected? |
| --- | --- | --- | --- |
| **`HandoffFeedbackSource`** | `lastAccess.put(op.resource, …)` (`:119`); `featureId(op.resource, …)` (`:123`); `describe` formats `"r=$resource …"` (`:185`) into the FNV-hashed feature that `collect()` returns and the aggregator unions across iterations | **Yes** | **YES — the one that's hurt** |
| `ResourceOrderingCoverage` | `recordAccess(op.resource, scheduled.index)` (`:20`) then **remaps**: `stableId = resourceIndex.getOrPut(resource){ size }` (`:67`); the cross-iteration `fingerprint()` (`:91-102`) contains only `stableId` + thread-index bitsets/transitions | No (absolute int washed out) | **No** — residual fragility is contention-order / thread-index (a thread-axis issue), not resource-id |
| `SURWScheduler` | `interestingObjectMap.getOrPut(op.resource){…}` — within-trial grouping only; what crosses iterations via `nextIteration` is a `Set<Int>` of **`stackTraceHash`** (`:182-187`), gated on `stackTraceHash` (`:102`) | No | No |
| `POSScheduler` | only via `isRacing` (`op.resource == resource`) within one scheduling decision | No | No |
| `RandomScheduler` / `FifoScheduler` / `PCTScheduler` | never read `op.resource` (pick by position/priority) | No | No |
| `GeneticScheduler` | never decodes resource (decodes genome positionally); consumes the *handoff feedback* | indirectly | only **through** `HandoffFeedbackSource` |
| `ReplayScheduler` / `ScheduleRecorder` / `ScheduleVerifier` | **never touch resource** — key on `thread.index`, the enabled-index list, and the operation **class name** (`ScheduleRecording(scheduled, enabled, operation)`) | No | No |
| Deadlock detection (`checkDeadlock`) | does **not** consult any resource id — decides from the thread-schedulability graph (`registeredThreads.values.none { it.schedulable() }`) | No | No (a common misattribution) |
| `CodeLocation` / `State` / `Artifact` / `Interleaving` sources | key on `stackTraceHash` / program values / file bytes / index+class — no resource concept | No | No |

### The handoff corruption, concretely (the W1 false-merge, resource axis)

A handoff feature is `r=<resource> <from>:<type> -> <to>:<type>`. You already made `<from>`/`<to>`
schedule-stable (spawn-path via `SpawnPathTracker`, see `thread-identity.md`). But `<resource>` is still the
raw `identityHashCode`-derived int. So across two iterations the **same** logical lock has **different**
`<resource>` values, and the "same" handoff feature names a *different resource* run-to-run — the resource
twin of the **W1 false-merge** the thread doc describes. The genetic search reads a feature as "already
explored" when the underlying (resource, ordering) is actually new: noise on exactly the gradient it climbs.

### Replay is unaffected — it never records a resource id

Independently confirmed: `ScheduleRecording(val scheduled: Int, val enabled: List<Int>, val operation: String)`
(`ScheduleRecording.kt:6`) stores **thread indices** and the operation **class name**
(`scheduled.pendingOperation.javaClass.name` — *not* `toString()`, which would have leaked the resource via
`LockLockOperation.toString()`). `ReplayScheduler` keys solely on `it.index == rec.scheduled`
(`ReplayScheduler.kt:20`). No persisted artifact (`recording.json`, `schedule.json` = positional genome,
`random.json` = RNG draws) carries a resource id. So resource-id instability **cannot break replay** — the
resource analog of "`index` is replay-stable": replay is keyed entirely on the thread axis + step.

---

## 4. Candidate stable namings — evaluated

The governing principle is the thread doc's: **schedule-stability ⇔ the coordinate is
program-determined**, not assigned in a scheduler-controlled hook. Here is the honest landscape.

| Scheme | Schedule-stable? | New instrumentation? | Names the resource, or a proxy? | Verdict |
| --- | --- | --- | --- | --- |
| **`identityHashCode`** (status quo) | No | — | the object (within a run only) | the bug |
| **Dense within-iteration remap** (`resourceIndex.getOrPut`) | Partial — strips address entropy, but first-contention **order** is schedule-dependent | None | the object, by first-contention rank | cheap mitigation, not a full fix |
| **`stackTraceHash`** (access code-location) | **Yes** — a source line doesn't move under reinterleaving | None (already on every `RacingOperation`, behind `resolveRacingOperationStackTraceHash`) | the **access site**, not the resource (over-merges `arr[i]`/`arr[j]` on one line; splits one resource across lines) | best ready-made proxy |
| **Access-path** (type+field, drop `objHash`) | **Yes** for fields | None | the **field**, merged across all instances | cheap; lossy where many objects own one field. *Static fields already do exactly this and are already stable.* |
| **Allocation-context EI** (execution index of the `new`) | **Yes**, to the degree allocation control-flow is data-deterministic | **Yes — Fray has no allocation hook** (only concurrency ops + method enter/exit are instrumented) | the **object identity** | the principled answer; cost-class change, overkill for a search heuristic |
| **`hashCodeMapper` reuse** | **No** (see §4a) | — | — | not reusable as built |

### 4a. Why the existing `hashCodeMapper` is *not* the answer

`RunContext.hashCodeMapper = ReferencedContextManager<Int>({ config.randomness.nextInt() })`
(`RunContext.kt:62`) virtualizes default `Object.hashCode()` (`RunContext.kt:1559-1566`). Tempting to reuse
for resource ids — but it reproduces the exact `index`-style fragility:

- Its producer is `config.randomness.nextInt()` — a **random** draw (replay-deterministic only, not derived
  from the object).
- It mints on **first touch**, and first-touch order is **scheduler-controlled** (the same structural
  problem as `threadCreateDone` for `index`).
- The draw comes from the **same `ControlledRandom` stream** that drives scheduling decisions (daemon
  scheduling `RunContext.kt:1428`, spurious wakeups `:514`/`:651`, signals `:795`/`:805`), so *when* in the
  interleaving an object is first hashed changes its id.
- It is cleared every iteration (`hashCodeMapper.done()`, `RunContext.kt:293`), so not cross-iteration
  comparable.
- And the resource path doesn't even use it — it uses raw `identityHashCode`.

To become an analog of spawn-path it would need a **program-order** producer (not first-touch, not random) —
which §4 of the thread doc shows a scheduler-bumped counter cannot provide for racy/conditional/pooled
allocation.

### 4b. `stackTraceHash` — the "rung 1" analog, already on every op

`RacingOperation.stackTraceHash` (`RacingOperation.kt:12`) hashes the top-2 **user** frames
(`Class.method:line`, after filtering out `org.pastalab.fray.*`), gated by `resolveRacingOperationStackTraceHash`
(default off; force-enabled by the `location` feedback source, `Configuration.kt:837-841`). It **is**
schedule-stable. But it names the **access site, not the resource**: two distinct resources touched at one
line collapse; one resource touched at many lines splits; and for a *handoff* the producer and consumer
touch the shared resource at *different* lines, so a single access's hash doesn't cleanly name the shared
thing. A schedule-stable **proxy, not an identity** — the direct resource-side mirror of rung 1.

### 4c. The fundamental obstacle — there is no resource "tree"

The thread fix worked because **a thread has a single immutable program-determined parent lineage**, and
`SpawnPathTracker` reconstructs it for free from `(index, parentId)` already on the wire. **A heap object has
no analogous tree.** The nearest candidate — allocation-context EI — is precisely the "form-A" construction
the thread doc warns relocates birth-order fragility under pooling/factories, *and* needs the allocation
instrumentation Fray deliberately avoids. So the realistic schedule-stable options for resources are the
site/access-path proxies (§4b, access-path row) — which over-merge — or full EI, which is a cost-class
change. **Refusing to separate genuinely aliased resources (distinct objects vended by a factory, touched at
one line) is correct, not a defect** — any stable shared name there would be a false merge, exactly as the
thread doc argues for symmetric workers.

---

## 5. Recommendations

Treat the resource axis the way the thread doc treats the thread axis: **don't try to make the absolute id
stable — make the *feature* stable**, and scope the work to the one corrupted consumer
(`HandoffFeedbackSource`).

1. **Stop embedding the raw `identityHashCode`-derived int** in the cross-iteration handoff feature.
   (A weaker alternative — a dense within-iteration remap, as `ResourceOrderingCoverage.kt:67` does —
   removes address entropy but leaves contention-order dependence, so it was not chosen.)
2. **✅ DONE — the resource bucket is composed from `stackTraceHash`.** A handoff is now "an ordering
   realized on the resource *touched at this code site*." Zero new instrumentation, schedule-stable,
   composes cleanly with the spawn-path-keyed endpoints. Implemented as a single source of truth,
   `HandoffFeedbackSource.resourceBucket(op)` (= `op.stackTraceHash`), used by both the feedback and the
   `ResourceIdentityLogger` measurement; the handoff arm of `Configuration.registerFeedbackSpec` forces
   `resolveRacingOperationStackTraceHash = true`. Accepts the documented over-merge cost
   (symmetric/loop accesses) — the resource analog of rung 1's honest silence on symmetric workers.
3. **Document the boundary** (as here): truly aliased resources are genuinely unnameable across schedules
   without allocation-context EI, and a stable shared name there would be a false merge.
4. **Do not** build allocation instrumentation for feedback (cost-class change), and **do not** repurpose
   `hashCodeMapper` without first replacing its random/first-touch producer with a program-order one (which
   still can't be made schedule-stable for racy allocation — §4a).
5. **Still open — fix or ticket the NIO `resource = 0` FIXME** — all network racing ops alias to a single
   feature; since the bucket is now `stackTraceHash`, NIO ops bucket by their *access site* rather than all
   colliding on resource 0, but a precise network resource id remains an independent coverage gap.

---

## Key code references

- `core/.../concurrency/operations/RacingOperation.kt:7` — `RacingOperation(val resource: Int, type)`;
  `:12` `stackTraceHash`; `:16-38` the `resolveRacingOperationStackTraceHash` gate + access-site hash.
- `core/.../concurrency/operations/MemoryOperation.kt:8` — `isRacing` keys on `op.resource == resource`.
- `core/.../concurrency/operations/LockLockOperation.kt:6` — `RacingOperation(System.identityHashCode(obj), …)`.
- `core/.../RunContext.kt:1112-1114` (field), `:1118` (atomic), `:1125-1126` (array), `:1131-1132` (unsafe),
  `:1135` (`memoryOperation` funnel), `:616`/`:1147` (wait/latch), `:62` (`hashCodeMapper`), `:275-297`
  (`done()` clears all manager maps), `:1559-1566` (`hashCode(obj)` virtualization).
- `core/.../concurrency/ReferencedContextManager.kt:5-13` — identity-keyed lazy context interning;
  `core/.../map/WeakIdentityHashMap.java:160,171-177` — bucket-by-`identityHashCode`, equals-by-reference.
- `core/.../feedback/sources/HandoffFeedbackSource.kt:119,123,185` — the raw `op.resource` embedded in the
  cross-iteration feature (the one corrupted consumer).
- `core/.../observers/ResourceOrderingCoverage.kt:20,67,91-102` — dense `stableId` remap ⇒ immune.
- `core/.../scheduler/SURWScheduler.kt:138,182-187,102` — resource as within-trial key; exports
  `stackTraceHash`.
- `core/.../scheduler/ReplayScheduler.kt:20` + `observers/ScheduleRecording.kt:6` /
  `observers/ScheduleRecorder.kt:22-27` — replay keys on thread index + operation class name; no resource id.
- `core/.../utils/SynchronizationManager.kt:6,15` — int-keyed sync points (collision-exposed, within-run).

## References

- `feedback/thread-identity.md` — the thread-axis companion (replay vs schedule stability; spawn-path; the
  rung ladder; execution indexing). This note is its resource-axis mirror.
- Bin Xin, William N. Sumner, Xiangyu Zhang. **Efficient Program Execution Indexing.** PLDI 2008. (The EI
  technique referenced in §4 — for resources it would name an object by its allocation context.)
- `java.lang.Object#hashCode` Javadoc — the cross-run-instability allowance that makes resource ids
  unsafe to rely on across runs.
