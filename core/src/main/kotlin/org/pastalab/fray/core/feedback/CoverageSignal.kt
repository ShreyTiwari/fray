package org.pastalab.fray.core.feedback

/**
 * Normalised feedback from one source for one iteration, so the search never branches on signal
 * origin; new sources drop in as [FeedbackSource] impls.
 *
 * [features] are hashed ids (covered edge, context-switch location, happens-before edge,
 * file-content key); novelty is growth of their global union across iterations (AFL coverage-map).
 * [HitBucket] packs (bucket, featureId) so an edge hit in a new frequency bucket counts as a new
 * feature; on by default for during-run sources, opt out with `--feedback <kind>:hits=false`.
 * [score] is an optional scalar for sources that rank rather than discover.
 */
data class CoverageSignal(
    val sourceName: String,
    val features: Set<Long>,
    val score: Double = 0.0,
)
