package org.pastalab.fray.core.scheduler.qlearning

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins [QLearningConfig] defaults, that [QLearningConfig.sanitized] clamps every field, and that
 * the `fray.qlearning.*` system-property channel reads and clamps correctly. */
class QLearningConfigTest {

  private val keys =
      listOf(
          "fray.qlearning.alpha",
          "fray.qlearning.gamma",
          "fray.qlearning.eps0",
          "fray.qlearning.epsDecay",
          "fray.qlearning.epsMin",
          "fray.qlearning.optimisticInit",
          "fray.qlearning.tableCap",
          "fray.qlearning.nStep",
          "fray.qlearning.stateMode",
          "fray.qlearning.stateClass",
          "fray.qlearning.rewardBug",
          "fray.qlearning.rewardNovelty",
          "fray.qlearning.rewardFitness",
          "fray.qlearning.rewardMax",
      )

  @AfterTest
  fun clearProps() {
    keys.forEach { System.clearProperty(it) }
  }

  @Test
  fun defaultMatchesConstants() {
    val d = QLearningConfig.DEFAULT
    assertEquals(QLearningConfig.DEFAULT_ALPHA, d.alpha)
    assertEquals(QLearningConfig.DEFAULT_GAMMA, d.gamma)
    assertEquals(QLearningConfig.DEFAULT_EPS0, d.eps0)
    assertEquals(QLearningConfig.DEFAULT_TABLE_CAP, d.tableCap)
    assertEquals(QLearningConfig.DEFAULT_N_STEP, d.nStep)
    assertEquals(QLearningConfig.MODE_AUTO, d.stateMode, "default state mode is auto-select")
  }

  @Test
  fun defaultIsAlreadySanitized() {
    assertEquals(QLearningConfig.DEFAULT, QLearningConfig.DEFAULT.sanitized())
  }

  @Test
  fun sanitizedClampsProbabilitiesAndCaps() {
    val bad =
        QLearningConfig(
            alpha = 5.0,
            gamma = -1.0,
            eps0 = 9.0,
            epsDecay = -0.5,
            epsMin = 2.0,
            tableCap = 0,
            nStep = -3,
            rewardMax = -10.0,
        )
    val s = bad.sanitized()
    assertEquals(1.0, s.alpha, "alpha clamps to [0,1]")
    assertEquals(0.0, s.gamma, "gamma clamps to [0,1]")
    assertEquals(1.0, s.eps0, "eps0 clamps to [0,1]")
    assertEquals(0.0, s.epsDecay, "epsDecay clamps to [0,1]")
    assertEquals(1.0, s.epsMin, "epsMin clamps to [0,1]")
    assertEquals(1, s.tableCap, "tableCap floored at 1")
    assertEquals(0, s.nStep, "nStep floored at 0")
    assertTrue(s.rewardMax > 0.0, "rewardMax floored above 0")
  }

  @Test
  fun sanitizedKeepsKnownModesAndAutoButRejectsGarbage() {
    assertEquals(
        QLearningConfig.MODE_OBSERVED_STATE,
        QLearningConfig(stateMode = QLearningConfig.MODE_OBSERVED_STATE).sanitized().stateMode)
    assertEquals(
        QLearningConfig.MODE_LOCATION,
        QLearningConfig(stateMode = QLearningConfig.MODE_LOCATION).sanitized().stateMode)
    assertEquals(
        QLearningConfig.MODE_AUTO,
        QLearningConfig(stateMode = QLearningConfig.MODE_AUTO).sanitized().stateMode,
        "auto (empty) is preserved for the wiring to resolve")
    assertEquals(
        QLearningConfig.MODE_CANDIDATE_SET,
        QLearningConfig(stateMode = "nonsense").sanitized().stateMode,
        "unknown mode degrades to candidateSet")
  }

  @Test
  fun fromSystemPropertiesWithNoneSetIsDefault() {
    assertEquals(QLearningConfig.DEFAULT, QLearningConfig.fromSystemProperties())
  }

  @Test
  fun fromSystemPropertiesReadsAndClamps() {
    System.setProperty("fray.qlearning.alpha", "0.5")
    System.setProperty("fray.qlearning.tableCap", "0")
    System.setProperty("fray.qlearning.stateMode", "observedState")
    System.setProperty("fray.qlearning.stateClass", "com.example.MyAbstraction")
    val c = QLearningConfig.fromSystemProperties()
    assertEquals(0.5, c.alpha)
    assertEquals(1, c.tableCap, "tableCap=0 clamps to 1")
    assertEquals(QLearningConfig.MODE_OBSERVED_STATE, c.stateMode)
    assertEquals("com.example.MyAbstraction", c.stateClass)
    assertEquals(QLearningConfig.DEFAULT_GAMMA, c.gamma, "unset props keep defaults")
  }

  @Test
  fun fromSystemPropertiesIgnoresUnparseableValues() {
    System.setProperty("fray.qlearning.alpha", "not-a-number")
    System.setProperty("fray.qlearning.tableCap", "")
    val c = QLearningConfig.fromSystemProperties()
    assertEquals(QLearningConfig.DEFAULT_ALPHA, c.alpha, "garbage double falls back to default")
    assertEquals(QLearningConfig.DEFAULT_TABLE_CAP, c.tableCap, "empty int falls back to default")
  }
}
