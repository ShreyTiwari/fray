package org.pastalab.fray.core.feedback

/**
 * AFL-style hit-count bucketing for [CoverageSignal]. A plain `Set<Long>` feature is binary and
 * discards frequency, but "hit twice vs. 200 times" is real race signal. A source counts occurrences
 * per raw feature, then packs `(bucket, rawFeature)`: the same feature in a new frequency bucket
 * becomes a new distinct `Long`, growing the union as AFL does -- downstream consumers see only more
 * `Long`s. Bucketing is logarithmic, coarse on purpose (47 vs 48 hits stays put; 2 vs 200 crosses).
 *
 * Bit layout: the aggregator's top 8 bits are a namespace tag, leaving a 56-bit payload split as
 * `bucket : bits 52..55 | rawHash : bits 0..51`. Sources that used the full 56-bit payload keep only
 * 52 raw bits here, raising collision odds (still far below one-in-a-billion at their feature counts)
 * -- hence opt-in. `location`'s 32-bit hash loses nothing.
 *
 * Both functions are pure, so replay stays bit-identical; never serialised.
 */
object HitBucket {
  const val RAW_BITS = 52
  const val MAX_BUCKET = 15
  private const val RAW_MASK = (1L shl RAW_BITS) - 1

  /** `count<=0 -> 0`, else `floor(log2(count)) + 1`, saturated at [MAX_BUCKET]. */
  fun bucket(count: Int): Int {
    if (count <= 0) return 0
    // bit-length == floor(log2(count)) + 1
    val bits = Int.SIZE_BITS - Integer.numberOfLeadingZeros(count)
    return bits.coerceAtMost(MAX_BUCKET)
  }

  /** Pack `bucket(count)` (bits 52..55) with `rawFeature` truncated to [RAW_BITS]. */
  fun encode(rawFeature: Long, count: Int): Long {
    return (bucket(count).toLong() shl RAW_BITS) or (rawFeature and RAW_MASK)
  }
}
