package net.wanners.groceries

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-IP token bucket. Loopback is exempt (the phone hitting itself shouldn't trip).
 * Lightweight: a map keyed by remote host, no expiry — at one entry per unique
 * client IP on a home LAN, the map will never grow beyond a handful of entries.
 */
class RateLimiter(
    // Calibrated for "anyone on your LAN" trust, not adversarial DoS. A human
    // pasting a 50-item shopping list bursts ~50 POSTs at once and we don't
    // want that to trip; 300 capacity / 100 refill leaves clear headroom.
    private val capacity: Double = 300.0,
    private val refillPerSecond: Double = 100.0,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Bucket(var tokens: Double, var lastRefillMs: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Returns true if the call is allowed and consumes a token; false if the bucket is dry. */
    fun allow(remoteHost: String?): Boolean {
        if (remoteHost.isNullOrEmpty()) return true
        if (isLoopbackHost(remoteHost)) return true
        val nowMs = now()
        val bucket = buckets.computeIfAbsent(remoteHost) { Bucket(capacity, nowMs) }
        synchronized(bucket) {
            val elapsedSec = (nowMs - bucket.lastRefillMs).coerceAtLeast(0) / 1000.0
            bucket.tokens = (bucket.tokens + elapsedSec * refillPerSecond).coerceAtMost(capacity)
            bucket.lastRefillMs = nowMs
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                return true
            }
            return false
        }
    }
}
