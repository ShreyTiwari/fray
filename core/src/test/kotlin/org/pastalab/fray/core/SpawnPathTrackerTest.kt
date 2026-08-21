package org.pastalab.fray.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SpawnPathTracker], driven through its raw [SpawnPathTracker.recordParent] entry
 * point so no RunContext/ThreadContext machinery is needed; the recorded `index -> parentId` links
 * mirror what the tracker observes off each thread's [ThreadStartOperation] in production.
 */
class SpawnPathTrackerTest {

  @Test
  fun rootIsR() {
    val t = SpawnPathTracker()
    t.recordParent(0, -1)
    assertEquals("r", t.pathOf(0))
  }

  @Test
  fun childrenGetSiblingOrdinalsByIndexRank() {
    val t = SpawnPathTracker()
    t.recordParent(0, -1)
    t.recordParent(1, 0)
    t.recordParent(2, 0)
    t.recordParent(3, 0)
    assertEquals("r.0", t.pathOf(1))
    assertEquals("r.1", t.pathOf(2))
    assertEquals("r.2", t.pathOf(3))
  }

  @Test
  fun siblingOrdinalIsRankNotAbsoluteIndex() {
    // Ordinal is rank among siblings, not the absolute index (which cousins may have consumed).
    val t = SpawnPathTracker()
    t.recordParent(0, -1)
    t.recordParent(5, 0)
    t.recordParent(9, 0)
    assertEquals("r.0", t.pathOf(5))
    assertEquals("r.1", t.pathOf(9))
  }

  @Test
  fun deepChainResolvesParentBeforeChild() {
    // Recorded child-first to prove resolution is by ascending index, not input order.
    val t = SpawnPathTracker()
    t.recordParent(2, 1)
    t.recordParent(1, 0)
    t.recordParent(0, -1)
    assertEquals("r", t.pathOf(0))
    assertEquals("r.0", t.pathOf(1))
    assertEquals("r.0.0", t.pathOf(2))
  }

  /**
   * When two parents each spawn one child, swapping the children's birth order across schedules
   * swaps their global `index` (3<->4) — which would make an index-keyed handoff alias the two
   * mirror interleavings. Spawn-path stays stable because the ordinal is rank-among-siblings
   * (each parent's lone child is ordinal 0), independent of the cross-parent birth race.
   */
  @Test
  fun cousinPathsAreStableWhenBirthOrderSwaps() {
    // Schedule A: r.0's child born first (index 3), r.1's second (index 4).
    val a = SpawnPathTracker()
    a.recordParent(0, -1)
    a.recordParent(1, 0)
    a.recordParent(2, 0)
    a.recordParent(3, 1)
    a.recordParent(4, 2)

    // Schedule B: grandchild birth order reversed.
    val b = SpawnPathTracker()
    b.recordParent(0, -1)
    b.recordParent(1, 0)
    b.recordParent(2, 0)
    b.recordParent(4, 1)
    b.recordParent(3, 2)

    val aChildOfR0 = a.pathOf(3)
    val aChildOfR1 = a.pathOf(4)
    val bChildOfR0 = b.pathOf(4)
    val bChildOfR1 = b.pathOf(3)

    assertEquals("r.0.0", aChildOfR0)
    assertEquals("r.1.0", aChildOfR1)
    assertEquals(aChildOfR0, bChildOfR0, "child of r.0 must have the same path despite index swap")
    assertEquals(aChildOfR1, bChildOfR1, "child of r.1 must have the same path despite index swap")
    assertNotEquals(aChildOfR0, aChildOfR1)
  }

  @Test
  fun resetClearsState() {
    val t = SpawnPathTracker()
    t.recordParent(0, -1)
    t.recordParent(1, 0)
    assertEquals("r.0", t.pathOf(1))
    t.reset()
    t.recordParent(1, -1)
    assertEquals("r", t.pathOf(1))
  }

  @Test
  fun unknownIndexFallsBackStably() {
    val t = SpawnPathTracker()
    t.recordParent(0, -1)
    // Unrecorded index: stable non-throwing sentinel, distinct from any real path.
    assertEquals("?7", t.pathOf(7))
    assertTrue(t.pathOf(7).startsWith("?"))
  }

  @Test
  fun firstParentLinkWinsAndIsNotOverwritten() {
    val t = SpawnPathTracker()
    t.recordParent(0, -1)
    t.recordParent(1, 0)
    t.recordParent(1, 99) // first link wins; stale/duplicate observation ignored
    assertEquals("r.0", t.pathOf(1))
  }

  @Test
  fun parentReferencedButNeverRecordedIsTreatedAsRoot() {
    // A parent seen only as a parentId (its own ThreadStartOperation missed) is nameable as a root,
    // so the child resolves to a real path rather than the "?" fallback.
    val t = SpawnPathTracker()
    t.recordParent(1, 0)
    assertEquals("r", t.pathOf(0))
    assertEquals("r.0", t.pathOf(1))
  }
}
