package org.pastalab.fray.core.feedback.sources

import java.nio.file.Files
import java.nio.file.Path
import org.pastalab.fray.core.feedback.CoverageSignal
import org.pastalab.fray.core.feedback.FeedbackSource
import org.pastalab.fray.core.feedback.IterationResult

/**
 * Pull-at-end feedback source: reads an artifact the target wrote during the run (coverage dump,
 * or an on-disk state file for an out-of-process target) and hashes it into coverage features.
 * Unlike [StateFeedbackSource] (in-process, push-during-run), this handles state readable only
 * after the worker finishes and possibly outside the JVM.
 *
 * [collect] must never throw: any failure (missing/partial/corrupt) degrades to an empty signal.
 * [beforeIteration] deletes any stale artifact so a target that fails to write is not scored on
 * old data.
 */
class ArtifactFeedbackSource
@JvmOverloads
constructor(
    override val name: String = "artifact",
    private val artifactPath: Path,
    private val systemPropertyKey: String = "fray.artifact.path",
    private val chunkBytes: Int = 64,
) : FeedbackSource {

  // Masks each feature to the low bits so the aggregator can reserve the high 8 as a source tag.
  private val payloadMask = 0x00FF_FFFF_FFFF_FFFFL

  override fun beforeIteration(iteration: Int) {
    System.setProperty(systemPropertyKey, artifactPath.toString())
    runCatching { Files.deleteIfExists(artifactPath) }
  }

  override fun collect(result: IterationResult): CoverageSignal {
    return runCatching {
          val bytes = Files.readAllBytes(artifactPath)
          val features = mutableSetOf<Long>()
          var i = 0
          while (i < bytes.size) {
            val end = minOf(i + chunkBytes, bytes.size)
            // Position-tag the hash so reordering identical chunks is still distinguished.
            var h = -0x340d631b7bdddcdbL xor (i.toLong() / chunkBytes)
            for (j in i until end) {
              h = h xor (bytes[j].toLong() and 0xFF)
              h *= 0x100000001b3L
            }
            features.add(h and payloadMask)
            i = end
          }
          CoverageSignal(name, features)
        }
        .getOrDefault(CoverageSignal(name, emptySet()))
  }
}
