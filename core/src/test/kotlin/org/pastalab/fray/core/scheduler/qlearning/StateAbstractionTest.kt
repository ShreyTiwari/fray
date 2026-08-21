package org.pastalab.fray.core.scheduler.qlearning

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.concurrency.operations.ThreadStartOperation
import org.pastalab.fray.runtime.MemoryOpType

/** Tests for the [StateAbstraction] SPI and its built-ins. */
class StateAbstractionTest {

  private val context = QlTestSupport.newContext()

  @AfterTest
  fun clearProps() {
    System.clearProperty("fray.feedback")
  }

  private fun candidates(vararg ops: org.pastalab.fray.core.concurrency.operations.Operation):
      List<ThreadContext> =
      ops.mapIndexed { i, op -> QlTestSupport.threadWith(context, i, op) }

  @Test
  fun candidateSetIsDeterministic() {
    val abs = StateAbstraction.candidateSet()
    val c = candidates(QlTestSupport.memOp(1), QlTestSupport.memOp(2))
    assertEquals(abs.stateKey(c), abs.stateKey(c), "same candidates => same key")
  }

  @Test
  fun candidateSetDistinguishesDifferentResources() {
    val abs = StateAbstraction.candidateSet()
    val a = abs.stateKey(candidates(QlTestSupport.memOp(1)))
    val b = abs.stateKey(candidates(QlTestSupport.memOp(2)))
    assertNotEquals(a, b, "different pending resources => different state")
  }

  @Test
  fun candidateSetDistinguishesOpType() {
    val abs = StateAbstraction.candidateSet()
    val read = abs.stateKey(candidates(QlTestSupport.memOp(1, MemoryOpType.MEMORY_READ)))
    val write = abs.stateKey(candidates(QlTestSupport.memOp(1, MemoryOpType.MEMORY_WRITE)))
    assertNotEquals(read, write, "read vs write on the same resource differ")
  }

  @Test
  fun threadStartOpIsHandledWithoutRacingFields() {
    // Non-RacingOperations (no stackTraceHash) still key stably: candidateSet uses the op class name.
    val abs = StateAbstraction.candidateSet()
    val c = candidates(ThreadStartOperation(0), ThreadStartOperation(0))
    assertEquals(abs.stateKey(c), abs.stateKey(c))
  }

  @Test
  fun observedStateReadsProvider() {
    var value = 42L
    val abs = StateAbstraction.observedState { value }
    val c = candidates(QlTestSupport.memOp(1))
    val k1 = abs.stateKey(c)
    value = 99L
    val k2 = abs.stateKey(c)
    assertNotEquals(k1, k2, "the abstract state tracks the observed value, not the candidates")
    value = 42L
    assertEquals(k1, abs.stateKey(c))
  }

  @Test
  fun forModeObservedStateUsesProvider() {
    var value = 7L
    val abs =
        StateAbstraction.forMode(
            QLearningConfig.MODE_OBSERVED_STATE, stateClass = "") { value }
    val c = candidates(QlTestSupport.memOp(1))
    val before = abs.stateKey(c)
    value = 8L
    assertNotEquals(before, abs.stateKey(c))
  }

  @Test
  fun forModeCustomLoadsByFqnAndFallsBackOnBadClass() {
    val good =
        StateAbstraction.forMode(
            QLearningConfig.MODE_CUSTOM,
            stateClass = FixedKeyAbstraction::class.java.name) { 0L }
    assertEquals(
        FixedKeyAbstraction.KEY,
        good.stateKey(candidates(QlTestSupport.memOp(1))),
        "custom class is loaded and used")

    val bad =
        StateAbstraction.forMode(
            QLearningConfig.MODE_CUSTOM, stateClass = "com.nope.DoesNotExist") { 0L }
    val expected = StateAbstraction.candidateSet().stateKey(candidates(QlTestSupport.memOp(5)))
    assertEquals(
        expected,
        bad.stateKey(candidates(QlTestSupport.memOp(5))),
        "unloadable custom class degrades to candidateSet")
  }

  @Test
  fun resolveModeAutoPicksObservedStateUnderStateFeedback() {
    System.setProperty("fray.feedback", "state")
    assertEquals(QLearningConfig.MODE_OBSERVED_STATE, StateAbstraction.resolveMode(QLearningConfig.MODE_AUTO))
  }

  @Test
  fun resolveModeAutoPicksCandidateSetOtherwise() {
    System.clearProperty("fray.feedback")
    assertEquals(QLearningConfig.MODE_CANDIDATE_SET, StateAbstraction.resolveMode(QLearningConfig.MODE_AUTO))
    System.setProperty("fray.feedback", "handoff,location")
    assertEquals(QLearningConfig.MODE_CANDIDATE_SET, StateAbstraction.resolveMode(QLearningConfig.MODE_AUTO))
  }

  @Test
  fun resolveModeLeavesKnownModesUntouched() {
    System.setProperty("fray.feedback", "state")
    assertEquals(
        QLearningConfig.MODE_CANDIDATE_SET,
        StateAbstraction.resolveMode(QLearningConfig.MODE_CANDIDATE_SET),
        "an explicit mode is never overridden by auto-select")
  }

  /** Trivial user abstraction loaded reflectively in the custom-mode test. */
  class FixedKeyAbstraction : StateAbstraction {
    override fun stateKey(candidates: List<ThreadContext>): Long = KEY

    companion object {
      const val KEY = 123456789L
    }
  }
}
