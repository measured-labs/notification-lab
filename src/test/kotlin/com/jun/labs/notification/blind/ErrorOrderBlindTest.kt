package com.jun.labs.notification.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 판정 순서 · 오류 본문 — 입력 400이 존재 404보다 먼저(ADR-000 §6.1 · §6), 오류 본문은 정확히 {code, message},
 * Spring 표준 예외 = HTTP_<상태> · 405는 Allow 헤더 유지(test-contract §4).
 */
class ErrorOrderBlindTest : HealthyProviderBlindTest() {

    @Test
    fun `잘못된 경로 id 는 존재 확인보다 먼저 400`() {
        assertError(getNotification("abc"), 400, "INVALID_REQUEST")
        assertError(getNotification("0"), 400, "INVALID_REQUEST")
        assertError(getCampaign("-1"), 400, "INVALID_REQUEST")
        assertError(getCampaign("9223372036854775808"), 400, "INVALID_REQUEST")
    }

    @Test
    fun `올바른 모양의 없는 id 는 404`() {
        assertError(getNotification(Long.MAX_VALUE), 404, "NOTIFICATION_NOT_FOUND")
        assertError(getCampaign(Long.MAX_VALUE), 404, "CAMPAIGN_NOT_FOUND")
    }

    @Test
    fun `여러 필드가 함께 틀려도 400 하나 - 저장 · 발송 없음`() {
        val userId = newUserId()
        val eventId = newEventId()
        val res = send(eventId = eventId, eventType = "", userId = userId, channels = listOf("FAX"), title = "", body = 1)

        assertError(res, 400, "INVALID_REQUEST")
        assertThat(rowsByUser(userId)).isEmpty()
        assertThat(rowsByEvent(eventId)).isEmpty()
    }

    @Test
    fun `없는 경로 - 404 HTTP_404`() {
        assertError(app.get("/nowhere"), 404, "HTTP_404")
        assertError(app.post("/v1/push/send", "{}"), 404, "HTTP_404")
        assertError(app.get("/notifications/1/extra"), 404, "HTTP_404")
    }

    @Test
    fun `허용되지 않는 메서드 - 405 HTTP_405, Allow 헤더 유지`() {
        val getCampaigns = app.get("/campaigns")
        assertError(getCampaigns, 405, "HTTP_405")
        assertThat(getCampaigns.header("Allow")).describedAs("Allow").isNotNull().contains("POST")

        val deleteNotification = app.send("DELETE", "/notifications/1", null)
        assertError(deleteNotification, 405, "HTTP_405")
        assertThat(deleteNotification.header("Allow")).describedAs("Allow").isNotNull().contains("GET")

        val putNotifications = app.send("PUT", "/notifications", "{}")
        assertError(putNotifications, 405, "HTTP_405")
        assertThat(putNotifications.header("Allow")).describedAs("Allow").isNotNull().contains("POST")

        val postCampaign = app.post("/campaigns/1", "{}")
        assertError(postCampaign, 405, "HTTP_405")
        assertThat(postCampaign.header("Allow")).describedAs("Allow").isNotNull().contains("GET")
    }

    @Test
    fun `JSON 이 아닌 Content-Type - 415 HTTP_415, 저장 없음`() {
        val userId = newUserId()
        val eventId = newEventId()
        val body = json(
            "eventId" to eventId, "eventType" to "ORDER_PAID", "userId" to userId,
            "channels" to listOf("PUSH"), "title" to "t", "body" to "b",
        )

        assertError(app.post("/notifications", body, "text/plain"), 415, "HTTP_415")
        assertThat(rowsByUser(userId)).isEmpty()

        val before = count("campaign")
        val campaign = json(
            "name" to newCampaignName(), "channel" to "PUSH", "userIdStart" to newUserId(), "targetCount" to 1,
            "title" to "t", "body" to "b",
        )
        assertError(app.post("/campaigns", campaign, "text/plain"), 415, "HTTP_415")
        assertThat(count("campaign")).isEqualTo(before)
    }
}
