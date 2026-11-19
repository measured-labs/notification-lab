package com.jun.labs.notification.blind

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** 발송 이력 조회 — ADR-000 §6(GET /notifications/{id} · ?eventId=) · §6.1(eventId 규칙). */
class QueryBlindTest : HealthyProviderBlindTest() {

    private fun comparable(n: JsonNode): Map<String, Any?> = NOTIFICATION_FIELDS.associateWith { f ->
        when (f) {
            "requestedAt" -> n.instantOrNull(f)
            "sentAt" -> n.instantOrNull(f)
            else -> n.textOrNull(f)
        }
    }

    @Test
    fun `단건 조회 - 200, 발송 응답과 같은 알림`() {
        val posted = sendOk(channels = listOf("SMS"))[0]

        val res = getNotification(posted["notificationId"].asLong())

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(comparable(res.json)).isEqualTo(comparable(posted))
    }

    @Test
    fun `단건 조회 - 없는 id 는 404 NOTIFICATION_NOT_FOUND`() {
        assertError(getNotification(Long.MAX_VALUE), 404, "NOTIFICATION_NOT_FOUND")
    }

    @Test
    fun `eventId 목록 - 두 요청에 걸친 알림을 id 순으로`() {
        val eventId = newEventId()
        val a = sendOk(eventId = eventId, channels = listOf("SMS", "PUSH"))
        val other = sendOk(channels = listOf("PUSH"))
        val b = sendOk(eventId = eventId, channels = listOf("EMAIL"))

        val res = listByEvent(eventId)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.isArray).isTrue()
        val ids = res.json.map { it["notificationId"].asLong() }
        assertThat(ids).isEqualTo((a.toList() + b.toList()).map { it["notificationId"].asLong() })
        assertThat(ids).isSorted()
        assertThat(ids).doesNotContain(other[0]["notificationId"].asLong())
        assertThat(res.json.map { comparable(it) }).isEqualTo((a.toList() + b.toList()).map { comparable(it) })
    }

    @Test
    fun `eventId 목록 - 해당 알림이 없으면 200 빈 배열`() {
        val res = listByEvent(newEventId())

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.isArray).isTrue()
        assertThat(res.json.size()).isEqualTo(0)
    }

    @Test
    fun `eventId 목록 - 64자 eventId 는 받는다`() {
        val eventId = "q".repeat(54) + newEventId().takeLast(10)
        sendOk(eventId = eventId)

        val res = listByEvent(eventId)

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(res.json.size()).isEqualTo(1)
    }

    @ParameterizedTest(name = "eventId=[{0}]")
    @ValueSource(strings = ["", "   ", "65"])
    fun `eventId 목록 - 비었거나 공백이거나 65자면 400`(raw: String) {
        val eventId = if (raw == "65") "e".repeat(65) else raw
        assertError(listByEvent(eventId), 400, "INVALID_REQUEST")
    }

    @Test
    fun `eventId 목록 - eventId 파라미터가 없으면 400`() {
        assertError(app.get("/notifications"), 400, "INVALID_REQUEST")
    }

    @Test
    fun `eventId 목록 - 제어 문자가 든 eventId 는 400`() {
        assertError(listByEvent("evt\u0001x"), 400, "INVALID_REQUEST")
    }
}
