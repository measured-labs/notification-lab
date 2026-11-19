package com.jun.labs.mockprovider

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 채널별 분당 쿼터(ADR-000 §7). 창은 기동 시각 t0부터 60초씩 — 정각과 맞추지 않는다.
 * 창 k = [t0 + 60k초, t0 + 60(k+1)초) — 경계 시각은 다음 창이다. 한도를 넘으면 접수하지 않는다.
 */
@Component
class QuotaWindows(
    properties: MockProviderProperties,
    private val clock: Clock,
) {
    private val t0: Instant = clock.instant()

    private val counters: Map<ProviderChannel, Counter> = ProviderChannel.entries.associateWith { channel ->
        Counter(properties.channels.of(channel).quotaPerMinute ?: channel.defaultQuotaPerMinute)
    }

    /** 접수하면 null, 한도를 넘으면 Retry-After 초(현재 창 끝까지 남은 시간을 올림 · 최소 1) */
    fun tryAcquire(channel: ProviderChannel): Long? = counters.getValue(channel).tryAcquire(clock.instant())

    private inner class Counter(private val limit: Long) {
        private var window = Long.MIN_VALUE
        private var used = 0L

        @Synchronized
        fun tryAcquire(now: Instant): Long? {
            val elapsedNanos = Duration.between(t0, now).toNanos()
            val k = Math.floorDiv(elapsedNanos, WINDOW_NANOS)
            if (k != window) {
                window = k
                used = 0
            }
            if (used >= limit) {
                val windowEndNanos = (k + 1) * WINDOW_NANOS
                val remainingNanos = windowEndNanos - elapsedNanos
                return maxOf(1L, Math.ceilDiv(remainingNanos, NANOS_PER_SECOND))
            }
            used++
            return null
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val WINDOW_NANOS = 60 * NANOS_PER_SECOND
    }
}
