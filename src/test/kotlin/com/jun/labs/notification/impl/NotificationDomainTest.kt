package com.jun.labs.notification.impl

import com.jun.labs.notification.domain.Campaign
import com.jun.labs.notification.domain.CampaignStatus
import com.jun.labs.notification.domain.Channel
import com.jun.labs.notification.domain.ErrorCode
import com.jun.labs.notification.domain.Notification
import com.jun.labs.notification.domain.NotificationException
import com.jun.labs.notification.domain.NotificationKind
import com.jun.labs.notification.domain.NotificationStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationDomainTest {
    private val now = Instant.parse("2026-11-19T00:00:00.123456789Z")

    private fun transactional() = Notification.transactional("ev-1", "ORDER_PAID", 7, Channel.PUSH, "제목", "본문", now)

    @Test
    fun `트랜잭셔널 알림은 REQUESTED · attempts 0으로 만들어지고 시각은 마이크로초로 맞춘다`() {
        val n = transactional()
        assertEquals(NotificationStatus.REQUESTED, n.status)
        assertEquals(NotificationKind.TRANSACTIONAL, n.kind)
        assertEquals(0, n.attempts)
        assertEquals(Instant.parse("2026-11-19T00:00:00.123456Z"), n.requestedAt)
        assertNull(n.campaignId)
    }

    @Test
    fun `SENT는 providerMessageId · attempts 1 이상 · sentAt을 가진다`() {
        val n = transactional()
        n.markSent("msg_1", 2, now)
        assertEquals(NotificationStatus.SENT, n.status)
        assertEquals("msg_1", n.providerMessageId)
        assertEquals(2, n.attempts)
        assertEquals(n.updatedAt, n.sentAt)
    }

    @Test
    fun `FAILED는 lastError를 가진다 · sentAt 없음`() {
        val n = transactional()
        n.markFailed("HTTP_429 QUOTA_EXCEEDED", 4, now)
        assertEquals(NotificationStatus.FAILED, n.status)
        assertEquals("HTTP_429 QUOTA_EXCEEDED", n.lastError)
        assertNull(n.sentAt)
    }

    @Test
    fun `전이는 REQUESTED에서만 — SENT · FAILED 뒤 다시 기록할 수 없다`() {
        val sent = transactional().apply { markSent("m", 1, now) }
        assertThrows<IllegalStateException> { sent.markFailed("x", 1, now) }
        assertThrows<IllegalStateException> { sent.markSent("m2", 1, now) }
        val failed = transactional().apply { markFailed("x", 1, now) }
        assertThrows<IllegalStateException> { failed.markSent("m", 1, now) }
    }

    @Test
    fun `결과 기록의 attempts는 1 이상 · messageId · lastError는 비지 않고 길이 안`() {
        assertThrows<IllegalArgumentException> { transactional().markSent("m", 0, now) }
        assertThrows<IllegalArgumentException> { transactional().markSent(" ", 1, now) }
        assertThrows<IllegalArgumentException> { transactional().markSent("m".repeat(65), 1, now) }
        assertThrows<IllegalArgumentException> { transactional().markFailed("", 1, now) }
        assertThrows<IllegalArgumentException> { transactional().markFailed("e".repeat(201), 1, now) }
    }

    @Test
    fun `팩토리도 입력 규칙을 검사한다`() {
        fun invalid(block: () -> Unit) = assertEquals(ErrorCode.INVALID_REQUEST, assertThrows<NotificationException> { block() }.errorCode)
        invalid { Notification.transactional(" ", "T", 1, Channel.SMS, "t", "b", now) }
        invalid { Notification.transactional("e".repeat(65), "T", 1, Channel.SMS, "t", "b", now) }
        invalid { Notification.transactional("e", "T".repeat(51), 1, Channel.SMS, "t", "b", now) }
        invalid { Notification.transactional("e", "T", 0, Channel.SMS, "t", "b", now) }
        invalid { Notification.transactional("e", "T", 1, Channel.SMS, "t".repeat(101), "b", now) }
        invalid { Notification.transactional("e", "T", 1, Channel.SMS, "t", "b".repeat(1001), now) }
        invalid { Notification.transactional("e", "T", 1, Channel.SMS, "t\u0000", "b", now) }
        invalid { Notification.transactional("e", "T", 1, Channel.SMS, "t\uD800", "b", now) }
        invalid { Notification.forCampaign(1, -1, Channel.SMS, "t", "b", now) }
        // 짝이 맞는 서로게이트(이모지)는 1자로 센다
        Notification.transactional("e", "T", 1, Channel.SMS, "😀".repeat(100), "b", now)
    }

    @Test
    fun `캠페인은 RUNNING → COMPLETED만 — 두 번 완료할 수 없다`() {
        val c = Campaign.create("가을 세일", Channel.EMAIL, 1, 10, "t", "b", now)
        assertEquals(CampaignStatus.RUNNING, c.status)
        assertNull(c.completedAt)
        c.complete(now)
        assertEquals(CampaignStatus.COMPLETED, c.status)
        assertEquals(Instant.parse("2026-11-19T00:00:00.123456Z"), c.completedAt)
        assertThrows<IllegalStateException> { c.complete(now) }
    }

    @Test
    fun `캠페인 대상 범위 — targetCount 1~1,000,000 · 마지막 id가 Long 범위 안`() {
        fun invalid(block: () -> Unit) = assertEquals(ErrorCode.INVALID_REQUEST, assertThrows<NotificationException> { block() }.errorCode)
        invalid { Campaign.create("n", Channel.PUSH, 1, 0, "t", "b", now) }
        invalid { Campaign.create("n", Channel.PUSH, 1, 1_000_001, "t", "b", now) }
        invalid { Campaign.create("n", Channel.PUSH, 0, 1, "t", "b", now) }
        invalid { Campaign.create("n", Channel.PUSH, Long.MAX_VALUE - 1, 3, "t", "b", now) }
        invalid { Campaign.create(" ", Channel.PUSH, 1, 1, "t", "b", now) }
        Campaign.create("n", Channel.PUSH, Long.MAX_VALUE - 1, 2, "t", "b", now)
        Campaign.create("n", Channel.PUSH, Long.MAX_VALUE, 1, "t", "b", now)
    }
}
