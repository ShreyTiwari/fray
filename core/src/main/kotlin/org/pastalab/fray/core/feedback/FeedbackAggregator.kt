package org.pastalab.fray.core.feedback

/**
 * Owns the [FeedbackSource]s and global feature union; turns one iteration's per-source signals
 * into an [IterationFeedback]. Novelty is source-agnostic ("did the feature union grow?"), so new
 * sources need no scheduler change. Search state, not replay state: runtime-only, never serialised.
 */
class FeedbackAggregator(
    val sources: List<FeedbackSource>,
    // Per-source novelty valuation, keyed by source name; absent sources use
    // NoveltyValuation.IDENTITY, reproducing the old flat-linear objective.
    private val valuations: Map<String, NoveltyValuation> = emptyMap(),
    // Measurement-only source names: collected and reported (featuresSeenBySource) but excluded from
    // the objective vector, global union, and newFeatures/isNovel -- as if unregistered.
    private val measurementOnly: Set<String> = emptySet(),
) {
  // Growth of this union is the novelty signal driving selection. Features are namespaced by source
  // before entering, so two sources can never collide.
  private val globalCoverage = mutableSetOf<Long>()

  // Per-source union of raw (un-namespaced) features, for reporting only. Keyed by source name.
  private val perSourceCoverage = mutableMapOf<String, MutableSet<Long>>()

  // Source name -> namespace tag. The tag occupies the high 8 bits of every namespaced feature;
  // sources must keep their raw payload within the low bits.
  private val sourceTag: Map<String, Int> = sources.mapIndexed { i, s -> s.name to i }.toMap()

  fun beforeIteration(iteration: Int) {
    sources.forEach { runCatching { it.beforeIteration(iteration) } }
  }

  fun aggregate(result: IterationResult): IterationFeedback {
    // Guard each source independently: one throwing must not lose the others' signals.
    val signals =
        sources.map { source ->
          runCatching { source.collect(result) }
              .getOrDefault(CoverageSignal(source.name, emptySet()))
        }

    // One pass over the signals (already in registration order) builds the global namespaced union
    // `all` and the per-source objective vector, letting the GA Pareto-rank sources independently.
    val all = mutableSetOf<Long>()
    val objectives = LinkedHashMap<String, Double>()
    signals.forEach { signal ->
      val tag = sourceTag[signal.sourceName] ?: 0
      val perSource = perSourceCoverage.getOrPut(signal.sourceName) { mutableSetOf() }
      // Count novelty against perSource BEFORE folding raw features in below, else everything looks
      // already-seen. priorSize is captured pre-fold for saturation-decay valuations.
      val priorSize = perSource.size
      val newForSource = signal.features.count { it !in perSource }
      // Measurement-only: tracked for reporting but kept out of `all` and the objective vector, so
      // it can't flip isNovel (reset the GA restart counter) or be ranked by NSGA-II.
      val measureOnly = signal.sourceName in measurementOnly
      signal.features.forEach { raw ->
        if (!measureOnly) all += namespaced(tag, raw)
        perSource += raw
      }
      if (!measureOnly) {
        val valuation = valuations[signal.sourceName] ?: NoveltyValuation.IDENTITY
        objectives[signal.sourceName] = valuation.value(signal.score, newForSource, priorSize)
      }
    }
    val newFeatures = all - globalCoverage
    globalCoverage += all

    // Scalar summary for logs; the GA ranks on `objectives`, not this.
    val fitness = objectives.values.sum()
    return IterationFeedback(result, signals, newFeatures, fitness, objectives)
  }

  fun totalFeaturesSeen(): Int = globalCoverage.size

  /** Distinct raw features per source, for end-of-run reporting. */
  fun featuresSeenBySource(): Map<String, Int> = perSourceCoverage.mapValues { it.value.size }

  /** Pack the source tag into the high bits, keeping the source's payload in the low bits. */
  private fun namespaced(tag: Int, feature: Long): Long =
      (tag.toLong() shl 56) or (feature and 0x00FF_FFFF_FFFF_FFFFL)
}
