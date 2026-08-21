package org.pastalab.fray.core.observers

import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.rmi.ScheduleObserver
import org.pastalab.fray.rmi.TestStatusObserver
import java.nio.file.Path

/**
 * Tracks interleaving coverage metrics across Fray iterations.
 *
 * Per iteration, records:
 * - Total scheduling decisions
 * - Context switches (actual thread changes)
 * - "Choice points" where 2+ threads were enabled (real interleaving decisions)
 * - The schedule signature (sequence of thread indices) for deduplication
 *
 * Across iterations, tracks:
 * - Distinct schedule signatures seen
 * - Coverage growth (new signatures per iteration)
 * - Where decisions and switches land, attributed to `Class.method`
 *
 * The two location tables answer different questions and rank very differently (~100x): decisions
 * say where the scheduling budget goes, which is what a `fray-skip.conf` entry removes; switches say
 * how often a method flips the thread, which is where interleaving diversity comes from. Rank
 * skip-primitive candidates from decisions.
 *
 * The end-of-run summary is written to `<reportFolder>/interleaving-coverage.txt`
 * by `writeSummaryToReportFolder()`, which the JUnit extension calls once after
 * all iterations complete (bug or not).
 */
class InterleavingCoverageObserver(
    private val reportFolder: Path? = null,
) : ScheduleObserver<ThreadContext>, TestStatusObserver {

  companion object {
    // The `@1824259578` identity hash in an operation's toString().
    private val ANONYMOUS_OPERATION_ID = Regex("@-?\\d+")

    private val FRAY_INTERNAL_PACKAGES = listOf(
        "org.pastalab.fray.core.",
        "org.pastalab.fray.runtime.",
        "org.pastalab.fray.junit.",
        "org.pastalab.fray.instrumentation.",
        "org.pastalab.fray.rmi.",
    )

    // Decisions outnumber switches ~1000:1, so sample rather than walk every one.
    private const val DECISION_SAMPLE_INTERVAL = 100

    private val stackWalker = StackWalker.getInstance(setOf(), 32)
  }

  // Per-iteration state
  private var decisions = 0
  private var contextSwitches = 0
  private var choicePoints = 0
  private var maxConcurrentEnabled = 0
  private var seenMultipleThreads = false
  private var decisionsBeforeConcurrency = 0
  private val currentSchedule = mutableListOf<Int>()

  // Cross-iteration state
  private val iterationStats = mutableListOf<IterationStats>()
  private val distinctSchedules = mutableSetOf<List<Int>>()
  private val operationCounts = mutableMapOf<String, Long>()
  private val contextSwitchFromTypes = mutableMapOf<String, Long>()
  // Switches keyed by `Class.method`. Line-level keys fragmented one hot method across many small
  // rows, and fray-skip.conf takes a method, not a line.
  private val contextSwitchMethods = mutableMapOf<String, Long>()
  private val contextSwitchMethodOps = mutableMapOf<String, MutableMap<String, Long>>()
  // Sampled scheduling-decision locations, weighted back up to estimated decisions. Switch counts
  // rank how often a method flips the thread; these rank where the scheduling budget actually goes,
  // which is what a skip-primitive entry removes. The two orders differ by ~100x per operation type.
  private val decisionMethods = mutableMapOf<String, Long>()
  private val decisionMethodOps = mutableMapOf<String, MutableMap<String, Long>>()
  private var decisionSamples = 0L

  data class IterationStats(
      val decisions: Int,
      val contextSwitches: Int,
      val choicePoints: Int,
      val threadCount: Int,
      val isNewSchedule: Boolean,
      val maxConcurrentEnabled: Int,
      val decisionsBeforeConcurrency: Int,
      val schedule: List<Int>,
  )

  override fun onExecutionStart() {
    decisions = 0
    contextSwitches = 0
    choicePoints = 0
    maxConcurrentEnabled = 0
    seenMultipleThreads = false
    decisionsBeforeConcurrency = 0
    currentSchedule.clear()
  }

  override fun onNewSchedule(allThreads: Collection<ThreadContext>, scheduled: ThreadContext) {
    decisions++
    currentSchedule.add(scheduled.index)

    // Track what type of operation triggered this scheduling decision
    val opName = scheduled.pendingOperation.javaClass.simpleName
    operationCounts.merge(opName, 1L) { a, b -> a + b }

    // Count how many threads were actually schedulable (real choice vs forced)
    val enabledCount = allThreads.count { it.schedulable() }
    if (enabledCount > 1) {
      choicePoints++
      if (!seenMultipleThreads) {
        seenMultipleThreads = true
        decisionsBeforeConcurrency = decisions - 1
      }
    }
    if (enabledCount > maxConcurrentEnabled) {
      maxConcurrentEnabled = enabledCount
    }

    // Uniform 1-in-N over all decisions -- deliberately not a first-N prefix, which is what made the
    // switch table rank setup. Runs on the thread that hit the primitive, so this is a self-walk.
    decisionSamples++
    if (decisionSamples % DECISION_SAMPLE_INTERVAL == 0L) {
      val method = currentApplicationMethod()
      if (method != null) {
        decisionMethods.merge(method, DECISION_SAMPLE_INTERVAL.toLong()) { a, b -> a + b }
        decisionMethodOps.getOrPut(method) { mutableMapOf() }
            .merge(opName, DECISION_SAMPLE_INTERVAL.toLong()) { a, b -> a + b }
      }
    }
  }

  // First frame that is neither Fray nor JDK -- the same predicate the switch table uses.
  private fun currentApplicationMethod(): String? =
      stackWalker.walk { frames ->
        frames
            .filter { frame ->
              !FRAY_INTERNAL_PACKAGES.any { frame.className.startsWith(it) } &&
                  !frame.className.startsWith("java.") &&
                  !frame.className.startsWith("jdk.") &&
                  !frame.className.startsWith("sun.")
            }
            .findFirst()
            .map { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            .orElse(null)
      }

  override fun onContextSwitch(current: ThreadContext, next: ThreadContext) {
    contextSwitches++
    val switchToOp = next.pendingOperation.javaClass.simpleName
    val switchFromOp = current.pendingOperation.javaClass.simpleName
    contextSwitchFromTypes.merge(switchFromOp, 1L) { a, b -> a + b }
    // Every switch gets a location. This used to stop after the first 500 of the run, a prefix that
    // ended partway through iteration 1 and so ranked setup over the concurrent phase.
    val location = next.thread.stackTrace.firstOrNull { frame ->
      !FRAY_INTERNAL_PACKAGES.any { frame.className.startsWith(it) } &&
      !frame.className.startsWith("java.") &&
      !frame.className.startsWith("jdk.") &&
      !frame.className.startsWith("sun.")
    }
    // No application frame: name it by the operation, with the identity hash stripped from
    // toString() so these aggregate into one row per operation kind instead of one per instance.
    val key =
        if (location != null) {
          "${location.className.substringAfterLast('.')}.${location.methodName}"
        } else {
          ANONYMOUS_OPERATION_ID.replace(next.pendingOperation.toString().take(80), "@")
        }
    contextSwitchMethods.merge(key, 1L) { a, b -> a + b }
    contextSwitchMethodOps.getOrPut(key) { mutableMapOf() }.merge(switchToOp, 1L) { a, b -> a + b }
  }

  override fun onExecutionDone(bugFound: Throwable?) {
    // If concurrency never began, every decision was single-threaded setup.
    // Without this, `avgConcurrent = avgDecisions - avgSetup` would mislabel
    // all decisions as concurrent for fully single-threaded iterations.
    if (!seenMultipleThreads) {
      decisionsBeforeConcurrency = decisions
    }
    val signature = currentSchedule.toList()
    val isNew = distinctSchedules.add(signature)
    val threadCount = currentSchedule.toSet().size
    iterationStats.add(
        IterationStats(decisions, contextSwitches, choicePoints, threadCount, isNew, maxConcurrentEnabled, decisionsBeforeConcurrency, signature))
  }

  override fun onReportError(throwable: Throwable) {}

  // Per-iteration recording hook -- only fires when a bug is saved, so it's
  // not the right place for the cumulative summary. End-of-run flush happens
  // in writeSummaryToReportFolder(), called once by the JUnit extension.
  override fun saveToReportFolder(path: Path) {}

  /**
   * Write the cumulative summary to `<reportFolder>/interleaving-coverage.txt`
   * and the per-iteration schedule signatures to `<reportFolder>/schedules.txt`.
   * Called once at end-of-run by the JUnit extension's afterAll, so it fires
   * regardless of whether any iteration found a bug. No-op if no iterations
   * were observed or no report folder was configured.
   */
  fun writeSummaryToReportFolder() {
    if (reportFolder == null || iterationStats.isEmpty()) return
    val text = getSummaryText()
    if (text.isEmpty()) return
    try {
      java.nio.file.Files.createDirectories(reportFolder)
      reportFolder.resolve("interleaving-coverage.txt").toFile().writeText(text)
      reportFolder.resolve("schedules.txt").toFile().writeText(buildSchedulesText())
    } catch (e: Exception) { /* ignore */ }
  }

  private fun buildSchedulesText(): String {
    val sb = StringBuilder()
    sb.append("# iter  status  schedule\n")
    sb.append("# status: NEW = first time this signature appeared, DUP = repeat\n")
    iterationStats.forEachIndexed { i, stats ->
      val status = if (stats.isNewSchedule) "NEW" else "DUP"
      sb.append("%5d  %s  %s\n".format(i + 1, status, stats.schedule.joinToString(",")))
    }
    return sb.toString()
  }

  fun getSummaryText(): String {
    if (iterationStats.isEmpty()) return ""

    val total = iterationStats.size
    val avgDecisions = iterationStats.map { it.decisions }.average()
    val avgSwitches = iterationStats.map { it.contextSwitches }.average()
    val avgChoicePoints = iterationStats.map { it.choicePoints }.average()
    val avgThreads = iterationStats.map { it.threadCount }.average()
    val maxSwitches = iterationStats.maxOf { it.contextSwitches }
    val minSwitches = iterationStats.minOf { it.contextSwitches }
    val uniqueSchedules = distinctSchedules.size
    val zeroSwitchCount = iterationStats.count { it.contextSwitches == 0 }
    val zeroChoiceCount = iterationStats.count { it.choicePoints == 0 }
    val avgMaxConcurrent = iterationStats.map { it.maxConcurrentEnabled }.average()
    val choiceRatio = if (avgDecisions > 0) avgChoicePoints / avgDecisions * 100 else 0.0
    val avgSetup = iterationStats.map { it.decisionsBeforeConcurrency }.average()
    val avgConcurrent = avgDecisions - avgSetup

    val sb = StringBuilder()
    val contentWidth = 61
    val rule = "  " + "═".repeat(contentWidth)
    val subRule = "  " + "─".repeat(contentWidth)

    fun row(label: String, value: String) {
      sb.appendLine("  %-42s %18s".format(label, value))
    }
    fun heading(title: String) {
      sb.appendLine()
      sb.appendLine("  $title")
      sb.appendLine(subRule)
    }

    sb.appendLine()
    sb.appendLine(rule)
    sb.appendLine("  INTERLEAVING COVERAGE SUMMARY")
    sb.appendLine(rule)

    heading("Schedules")
    row("Iterations", "%,d".format(total))
    row("Unique schedules", "%,d (%.1f%%)".format(uniqueSchedules, uniqueSchedules * 100.0 / total))

    heading("Per-iteration averages")
    row("Context switches (min / avg / max)", "%,d / %.1f / %,d".format(minSwitches, avgSwitches, maxSwitches))
    row("Scheduling decisions", "%,.1f".format(avgDecisions))
    row("Choice points (2+ enabled)", "%,.1f (%.1f%% of decisions)".format(avgChoicePoints, choiceRatio))
    row("Distinct threads scheduled", "%.1f".format(avgThreads))
    row("Max concurrent enabled", "%.1f".format(avgMaxConcurrent))

    // Only when it fires: on a healthy workload both are 0 and the section is noise.
    if (zeroSwitchCount > 0 || zeroChoiceCount > 0) {
      heading("Iterations without concurrency")
      row("0 context switches", "%,d (%.1f%%)".format(zeroSwitchCount, zeroSwitchCount * 100.0 / total))
      row("0 choice points", "%,d (%.1f%%)".format(zeroChoiceCount, zeroChoiceCount * 100.0 / total))
    }

    heading("Setup vs concurrent decisions (avg)")
    row("In setup (1 thread)", "%,.0f".format(avgSetup))
    row("Concurrent (2+ threads)", "%,.0f".format(avgConcurrent))

    sb.appendLine()
    sb.appendLine(rule)

    if (operationCounts.isNotEmpty()) {
      heading("Scheduling decisions by operation type (top 10)")
      val totalOps = operationCounts.values.sum()
      operationCounts.entries.sortedByDescending { it.value }.take(10).forEach { (op, count) ->
        sb.appendLine("    %-44s %,10d  (%5.1f%%)".format(op, count, count * 100.0 / totalOps))
      }
    }

    // No "switched TO" table: the operation a switch lands on is near-tautological (thread resumes
    // resume threads) and the switch-method table below already carries a dominant-op column.
    if (contextSwitchFromTypes.isNotEmpty()) {
      heading("Context switches by source operation (switched FROM)")
      val totalSwitches = contextSwitchFromTypes.values.sum()
      contextSwitchFromTypes.entries.sortedByDescending { it.value }.forEach { (op, count) ->
        sb.appendLine("    %-44s %,10d  (%5.1f%%)".format(op, count, count * 100.0 / totalSwitches))
      }
    }

    if (decisionMethods.isNotEmpty()) {
      heading("Top scheduling decision methods (estimated, top 20)")
      val totalDecisions = decisionMethods.values.sum()
      val top = decisionMethods.entries.sortedByDescending { it.value }.take(20)
      val methodWidth = top.maxOf { it.key.length }
      top.forEach { (method, count) ->
        val dominantOp = decisionMethodOps[method]?.maxByOrNull { it.value }?.key ?: ""
        sb.appendLine(
            "    %-${methodWidth}s %,12d  (%5.1f%%)  %s".format(
                method, count, count * 100.0 / totalDecisions, dominantOp))
      }
      sb.appendLine()
      sb.appendLine(
          "    Sampled 1-in-%,d decisions, weighted back up. This is where the scheduling".format(
              DECISION_SAMPLE_INTERVAL))
      sb.appendLine("    budget goes -- rank skip-primitive candidates from here, not the switch tables.")
    }

    if (contextSwitchMethods.isNotEmpty()) {
      heading("Top context switch methods (top 20)")
      val totalSwitches = contextSwitchMethods.values.sum()
      val top = contextSwitchMethods.entries.sortedByDescending { it.value }.take(20)
      val methodWidth = top.maxOf { it.key.length }
      top.forEach { (method, count) ->
        val dominantOp =
            contextSwitchMethodOps[method]?.maxByOrNull { it.value }?.key ?: ""
        sb.appendLine(
            "    %-${methodWidth}s %,8d  (%5.1f%%)  %s".format(
                method, count, count * 100.0 / totalSwitches, dominantOp))
      }
      sb.appendLine()
      sb.appendLine("    Percentages are of all %,d located switches.".format(totalSwitches))
    }

    // No per-line switch table. Half its rows were `:-1` (no line info), i.e. duplicates of the
    // method table, and where lines did resolve they split one hot method across several small rows.
    // Nothing downstream consumes a line number -- fray-skip.conf takes a method.

    // Growth only says something once the milestones outpace the "unique schedules" line above.
    val milestones = listOf(25, 50, 100, 200, 500, 1000).filter { it <= total }
    if (milestones.size > 1) {
      heading("Coverage growth")
      var nextMilestone = 0
      for (idx in 1..total) {
        if (nextMilestone < milestones.size && idx == milestones[nextMilestone]) {
          val cumNew = iterationStats.take(idx).count { it.isNewSchedule }
          sb.appendLine("    After %,7d iterations: %,7d distinct schedules".format(idx, cumNew))
          nextMilestone++
        }
      }
    }

    sb.appendLine()
    sb.appendLine(rule)

    return sb.toString()
  }

  fun printSummary() {
    val text = getSummaryText()
    if (text.isNotEmpty()) {
      System.out.println(text)
      System.out.flush()
    }
  }
}
