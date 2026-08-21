package org.pastalab.fray.core.feedback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Unit tests for [HitBucket]. */
class HitBucketTest {

  @Test
  fun bucketBoundariesMatchLog2Scheme() {
    assertEquals(0, HitBucket.bucket(0), "unseen")
    assertEquals(0, HitBucket.bucket(-5), "non-positive => 0")
    assertEquals(1, HitBucket.bucket(1))
    assertEquals(2, HitBucket.bucket(2))
    assertEquals(2, HitBucket.bucket(3))
    assertEquals(3, HitBucket.bucket(4))
    assertEquals(3, HitBucket.bucket(7))
    assertEquals(4, HitBucket.bucket(8))
    assertEquals(4, HitBucket.bucket(15))
    assertEquals(5, HitBucket.bucket(16))
  }

  @Test
  fun bucketIsMonotonicNonDecreasing() {
    var prev = HitBucket.bucket(1)
    for (n in 2..100_000) {
      val b = HitBucket.bucket(n)
      assertTrue(b >= prev, "bucket must not decrease: bucket($n)=$b < $prev")
      prev = b
    }
  }

  @Test
  fun bucketSaturatesAtMaxBucket() {
    assertEquals(HitBucket.MAX_BUCKET, HitBucket.bucket(1 shl 14))
    assertEquals(HitBucket.MAX_BUCKET, HitBucket.bucket(Int.MAX_VALUE))
  }

  @Test
  fun coarseness_nearbyCountsShareABucketFarApartCountsDoNot() {
    assertEquals(HitBucket.bucket(47), HitBucket.bucket(48))
    assertNotEquals(HitBucket.bucket(2), HitBucket.bucket(200))
  }

  @Test
  fun encodeStaysWithin56BitPayload() {
    val payloadMask = 0x00FF_FFFF_FFFF_FFFFL
    val encoded = HitBucket.encode(rawFeature = -1L, count = Int.MAX_VALUE)
    assertEquals(encoded, encoded and payloadMask, "encoded payload must stay within 56 bits")
  }

  @Test
  fun sameRawDifferentBucketsAreDistinctFeatures() {
    val raw = 0x1234_5678L
    val once = HitBucket.encode(raw, 1)
    val many = HitBucket.encode(raw, 1000)
    assertNotEquals(once, many, "same site at different hit counts => distinct features")
  }

  @Test
  fun sameRawSameBucketCollapsesToOneFeature() {
    val raw = 0x1234_5678L
    // 2 and 3 share a bucket => must encode identically (no spurious novelty).
    assertEquals(HitBucket.encode(raw, 2), HitBucket.encode(raw, 3))
  }

  @Test
  fun encodePreservesLow52RawBitsAndBucketAbove() {
    val raw = 0x000F_FFFF_FFFF_FFFFL
    val encoded = HitBucket.encode(raw, 4)
    val rawMask = (1L shl HitBucket.RAW_BITS) - 1
    assertEquals(raw and rawMask, encoded and rawMask, "low 52 raw bits preserved")
    assertEquals(3L, encoded ushr HitBucket.RAW_BITS, "bucket sits above the raw window")
  }
}
