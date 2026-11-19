package com.jun.labs.notification.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 트랜잭셔널 발송 — 실제 mock 발송사(장애 없음). ADR-000 §2 · §2.1 ①② · §3(트랜잭셔널 발송) · §5 · §6.
 */
class TransactionalSendBlindTest : HealthyProviderBlindTest() {

    @Test
    fun `채널 하나 - 201, 알림 응답 1개, SENT, attempts 1, providerMessageId`() {
        val eventId = newEventId()
        val userId = newUserId()

        val res = sendOk(eventId = eventId, userId = userId, channels = listOf("SMS"), eventType = "ORDER_PAID")

        assertSentFirstTry(res[0], eventId, "ORDER_PAID", userId, "SMS")
        assertMatchesRow(res[0])
    }

    @Test
    fun `세 채널 - 요청한 채널 순서대로 응답, 채널당 행 1개, 행 id 도 그 순서`() {
        val eventId = newEventId()
        val userId = newUserId()
        val channels = listOf("EMAIL", "PUSH", "SMS")

        val res = sendOk(eventId = eventId, userId = userId, channels = channels)

        res.forEachIndexed { i, n -> assertSentFirstTry(n, eventId, "ORDER_PAID", userId, channels[i]) }
        val ids = res.map { it["notificationId"].asLong() }
        assertThat(ids).describedAs("발송 순서 = 채널 순서 = INSERT 순서").isSorted().doesNotHaveDuplicates()
        val rows = rowsByEvent(eventId)
        assertThat(rows.map { it.long("id") }).isEqualTo(ids)
        assertThat(rows.map { it.str("channel") }).isEqualTo(channels)
        assertThat(res.map { it.textOrNull("providerMessageId") }).describedAs("채널마다 따로 접수").doesNotHaveDuplicates()
        res.forEach { assertMatchesRow(it) }
    }

    @Test
    fun `DB 행 - kind TRANSACTIONAL, event 필드, 제목 · 본문 그대로, campaign_id 없음`() {
        val eventId = newEventId()
        val userId = newUserId()
        val title = "배송 시작 🚚"
        val body = "상품이 출발했습니다. 곧 도착합니다."

        sendOk(eventId = eventId, userId = userId, channels = listOf("PUSH", "EMAIL"), eventType = "SHIPPED", title = title, body = body)

        val rows = rowsByEvent(eventId)
        assertThat(rows).hasSize(2)
        rows.forEach { r ->
            assertThat(r.str("kind")).isEqualTo("TRANSACTIONAL")
            assertThat(r.str("event_id")).isEqualTo(eventId)
            assertThat(r.str("event_type")).isEqualTo("SHIPPED")
            assertThat(r["campaign_id"]).isNull()
            assertThat(r.long("user_id")).isEqualTo(userId)
            assertThat(r.str("title")).isEqualTo(title)
            assertThat(r.str("body")).isEqualTo(body)
            assertThat(r.str("status")).isEqualTo("SENT")
            assertThat(r.long("attempts")).isEqualTo(1L)
            assertThat(r.str("provider_message_id")).isNotBlank()
            assertThat(r.time("requested_at")).isEqualTo(now())
            assertThat(r.time("sent_at")).isEqualTo(now())
            assertThat(r.time("updated_at")).isNotNull()
        }
        assertThat(rowsByUser(userId)).hasSize(2)
    }

    @Test
    fun `같은 eventId 재요청 - 막지 않고 다시 발송한다(Q1, 멱등 없음)`() {
        val eventId = newEventId()
        val userId = newUserId()

        val first = sendOk(eventId = eventId, userId = userId, channels = listOf("PUSH", "SMS"))
        val second = sendOk(eventId = eventId, userId = userId, channels = listOf("PUSH", "SMS"))

        second.forEachIndexed { i, n -> assertSentFirstTry(n, eventId, "ORDER_PAID", userId, listOf("PUSH", "SMS")[i]) }
        val firstIds = first.map { it["notificationId"].asLong() }
        val secondIds = second.map { it["notificationId"].asLong() }
        assertThat(secondIds).describedAs("새 행").doesNotContainAnyElementsOf(firstIds)
        assertThat(rowsByEvent(eventId)).hasSize(4)
        assertThat(rowsByEvent(eventId).count { it.str("channel") == "PUSH" }).isEqualTo(2)
        val messageIds = (first + second).map { it.textOrNull("providerMessageId") }
        assertThat(messageIds).describedAs("발송사가 네 번 접수").doesNotHaveDuplicates()
    }

    @Test
    fun `같은 eventId 다른 사용자 - 각각 발송`() {
        val eventId = newEventId()
        val a = newUserId()
        val b = newUserId()

        sendOk(eventId = eventId, userId = a, channels = listOf("EMAIL"))
        sendOk(eventId = eventId, userId = b, channels = listOf("EMAIL"))

        assertThat(rowsByEvent(eventId).map { it.long("user_id") }).containsExactly(a, b)
    }
}
