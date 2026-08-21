package org.pastalab.fray.core.observers

import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.pastalab.fray.core.SpawnPathTracker
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.rmi.ScheduleObserver
import org.pastalab.fray.rmi.TestStatusObserver

/**
 * Per iteration, records each thread name -> both its Fray birth-order id ([ThreadContext.index])
 * and its [SpawnPathTracker] spawn-path, to measure cross-schedule id stability: `index` is
 * replay-stable but not schedule-stable (birth order is scheduler-controlled), whereas the
 * spawn-path stays put across schedules wherever a thread is nameable.
 *
 * Pure measurement observer: never influences scheduling. Gated behind `-Dfray.threadIdLog=<path>`;
 * inert otherwise. Output is JSONL appended as each iteration finishes, so a campaign that crashes
 * midway still leaves the completed iterations on disk (`ids` is name->index, `paths` name->path).
 */
class ThreadIdentityLogger(private val logPath: Path) : ScheduleObserver<ThreadContext>,
    TestStatusObserver {

  private val currentIteration = linkedMapOf<String, Int>()
  // Kept so onExecutionDone can resolve each name's spawn-path from [spawnPaths].
  private val indexByName = linkedMapOf<String, Int>()
  private val spawnPaths = SpawnPathTracker()
  private var iteration = 0

  init {
    // Truncate any prior run's log; subsequent iterations append.
    logPath.parent?.createDirectories()
    logPath.writeText("")
  }

  override fun onExecutionStart() {
    currentIteration.clear()
    indexByName.clear()
    spawnPaths.reset()
  }

  override fun onNewSchedule(allThreads: Collection<ThreadContext>, scheduled: ThreadContext) {
    spawnPaths.observe(allThreads)
    // Capture every registered thread, not just the scheduled one, so threads that are never
    // individually scheduled still appear with their index.
    for (t in allThreads) {
      currentIteration[t.thread.name] = t.index
      indexByName[t.thread.name] = t.index
    }
  }

  override fun onContextSwitch(current: ThreadContext, next: ThreadContext) {}

  override fun onReportError(throwable: Throwable) {}

  override fun onExecutionDone(bugFound: Throwable?) {
    val ids =
        currentIteration.entries.joinToString(",") { (name, index) ->
          "${jsonString(name)}:$index"
        }
    val paths =
        indexByName.entries.joinToString(",") { (name, index) ->
          "${jsonString(name)}:${jsonString(spawnPaths.pathOf(index))}"
        }
    logPath.appendText("{\"iter\":$iteration,\"ids\":{$ids},\"paths\":{$paths}}\n")
    iteration++
  }

  override fun saveToReportFolder(path: Path) {}

  // Minimal JSON escaping: thread names are test-controlled, so quotes/backslashes must not
  // corrupt the parsed line.
  private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> sb.append(c)
      }
    }
    sb.append('"')
    return sb.toString()
  }
}
