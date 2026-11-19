package com.jun.labs.mockprovider

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 쿼터 창 — 기동 시각 t0 기준 60초 · 경계는 다음 창 · Retry-After = 남은 초 올림(최소 1) */
class QuotaWindowsTest {
    private class TestClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    /** t0가 정각이 아니게 — 창이 정각과 맞춰지지 않는지도 본다 */
    private val t0 = Instant.parse("2026-11-19T00:00:17.250Z")
    private val clock = TestClock(t0)

    private fun windows(smsQuota: Long) = QuotaWindows(
        MockProviderProperties(MockProviderProperties.Channels(sms = MockProviderProperties.ChannelSettings(quotaPerMinute = smsQuota))),
        clock,
    )

    private fun at(offset: Duration) { clock.now = t0.plus(offset) }

    @Test
    fun `한도까지 접수하고 넘으면 Retry-After = 창 끝까지 남은 초 올림`() {
        val q = windows(2)
        assertNull(q.tryAcquire(ProviderChannel.SMS))
        assertNull(q.tryAcquire(ProviderChannel.SMS))
        assertEquals(60, q.tryAcquire(ProviderChannel.SMS))
        at(Duration.ofMillis(500))
        assertEquals(60, q.tryAcquire(ProviderChannel.SMS)) // 59.5 → 60
        at(Duration.ofSeconds(30))
        assertEquals(30, q.tryAcquire(ProviderChannel.SMS)) // 딱 30
        at(Duration.ofMillis(58_100))
        assertEquals(2, q.tryAcquire(ProviderChannel.SMS)) // 1.9 → 2
        at(Duration.ofSeconds(60).minusNanos(1))
        assertEquals(1, q.tryAcquire(ProviderChannel.SMS)) // 1ns → 1
    }

    @Test
    fun `경계 시각 t0 + 60초는 다음 창 — 다시 한도만큼 받는다`() {
        val q = windows(1)
        assertNull(q.tryAcquire(ProviderChannel.SMS))
        at(Duration.ofSeconds(60).minusNanos(1))
        assertEquals(1, q.tryAcquire(ProviderChannel.SMS))
        at(Duration.ofSeconds(60))
        assertNull(q.tryAcquire(ProviderChannel.SMS))
        assertEquals(60, q.tryAcquire(ProviderChannel.SMS))
        at(Duration.ofSeconds(150)) // 창 2 = [120, 180)
        assertNull(q.tryAcquire(ProviderChannel.SMS))
        assertEquals(30, q.tryAcquire(ProviderChannel.SMS))
    }

    @Test
    fun `채널마다 따로 센다 · 쿼터 생략은 채널 기본값 · 0은 항상 429`() {
        val q = windows(0)
        assertEquals(60, q.tryAcquire(ProviderChannel.SMS))
        repeat(1000) { assertNull(q.tryAcquire(ProviderChannel.PUSH)) }
        assertNull(q.tryAcquire(ProviderChannel.EMAIL))
        assertEquals(600_000, ProviderChannel.PUSH.defaultQuotaPerMinute)
        assertEquals(60_000, ProviderChannel.SMS.defaultQuotaPerMinute)
        assertEquals(120_000, ProviderChannel.EMAIL.defaultQuotaPerMinute)
    }
}
