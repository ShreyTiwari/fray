package org.pastalab.fray.core.scheduler.qlearning

import java.nio.file.Files
import org.pastalab.fray.core.RunContext
import org.pastalab.fray.core.ThreadContext
import org.pastalab.fray.core.command.Configuration
import org.pastalab.fray.core.command.ExecutionInfo
import org.pastalab.fray.core.command.LambdaExecutor
import org.pastalab.fray.core.command.NetworkDelegateType
import org.pastalab.fray.core.command.SystemTimeDelegateType
import org.pastalab.fray.core.concurrency.operations.MemoryOperation
import org.pastalab.fray.core.concurrency.operations.Operation
import org.pastalab.fray.core.randomness.ControlledRandomProvider
import org.pastalab.fray.core.scheduler.RandomScheduler
import org.pastalab.fray.runtime.MemoryOpType

/**
 * Constructing a [ThreadContext] needs a [RunContext] (its `localRandomProbe` draws from
 * `config.randomness`), hence the throwaway [Configuration]. Threads are never started -- only
 * identity and `pendingOperation` matter.
 */
object QlTestSupport {

  fun newContext(): RunContext =
      RunContext(
          Configuration(
              ExecutionInfo(
                  LambdaExecutor({}),
                  ignoreUnhandledExceptions = false,
                  interleaveMemoryOps = false,
                  maxScheduledStep = -1,
              ),
              Files.createTempDirectory("fray-qltest"),
              1,
              1000,
              RandomScheduler(),
              ControlledRandomProvider(),
              fullSchedule = false,
              exploreMode = false,
              noExitWhenBugFound = true,
              isReplay = false,
              noFray = false,
              dummyRun = false,
              networkDelegateType = NetworkDelegateType.NONE,
              systemTimeDelegateType = SystemTimeDelegateType.NONE,
              100_000L,
              ignoreTimedBlock = true,
              sleepAsYield = false,
              resetClassLoader = true,
              redirectStdout = false,
              abortThreadExecutionAfterMainExit = false,
              resolveRacingOperationStackTraceHash = false,
              timelineCoverageType = null,
          ))

  fun threadWith(context: RunContext, index: Int, op: Operation): ThreadContext {
    val tc = ThreadContext(Thread({}, "ql-test-$index"), index, context, parentId = 0)
    tc.pendingOperation = op
    return tc
  }

  /** `stackTraceHash` stays 0 unless resolution is enabled. */
  fun memOp(resource: Int, type: MemoryOpType = MemoryOpType.MEMORY_WRITE): MemoryOperation =
      MemoryOperation(resource, type)
}
