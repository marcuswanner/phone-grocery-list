package net.wanners.groceries

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RateLimitTest {

    @Test
    fun `loopback is always allowed regardless of rate`() {
        val rl = RateLimiter(capacity = 1.0, refillPerSecond = 0.0)
        repeat(50) { assertTrue(rl.allow("127.0.0.1")) }
        repeat(50) { assertTrue(rl.allow("::1")) }
        repeat(50) { assertTrue(rl.allow("localhost")) }
    }

    @Test
    fun `non-loopback gets up to capacity then is rejected`() {
        var t = 0L
        val rl = RateLimiter(capacity = 3.0, refillPerSecond = 0.0, now = { t })
        assertTrue(rl.allow("192.168.1.5"))
        assertTrue(rl.allow("192.168.1.5"))
        assertTrue(rl.allow("192.168.1.5"))
        assertFalse(rl.allow("192.168.1.5"))
    }

    @Test
    fun `tokens refill at the configured rate`() {
        var t = 0L
        val rl = RateLimiter(capacity = 2.0, refillPerSecond = 1.0, now = { t })
        assertTrue(rl.allow("10.0.0.1"))
        assertTrue(rl.allow("10.0.0.1"))
        assertFalse(rl.allow("10.0.0.1"))
        t += 1500 // 1.5 seconds → 1.5 tokens, capped to 2, one consumed leaves 0.5
        assertTrue(rl.allow("10.0.0.1"))
        assertFalse(rl.allow("10.0.0.1"))
    }

    @Test
    fun `buckets are per-host`() {
        var t = 0L
        val rl = RateLimiter(capacity = 1.0, refillPerSecond = 0.0, now = { t })
        assertTrue(rl.allow("10.0.0.1"))
        assertFalse(rl.allow("10.0.0.1"))
        // a different IP starts with its own full bucket
        assertTrue(rl.allow("10.0.0.2"))
    }
}
