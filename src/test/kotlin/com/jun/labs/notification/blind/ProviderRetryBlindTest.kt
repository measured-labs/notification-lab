package com.jun.labs.notification.blind

import com.jun.labs.notification.blind.ScriptedProvider.drop
import com.jun.labs.notification.blind.ScriptedProvider.ok
import com.jun.labs.notification.blind.ScriptedProvider.status
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 발송사 호출 · 즉시 재시도 — 시나리오 발송사 대역으로 실제 호출 수 · 순서 · 간격을 센다.
 * ADR-000 §3 재시도(429 · 5xx · 연결 실패 · 읽기 타임아웃 → 기다리지 않고 재시도, 최초 1 + 최대 3 = 최대 4회, Retry-After 무시,
 * 그 밖 4xx 재시도 없음 → FAILED) · §2.1 ①~④ · §6(발송사 실패는 201 안의 FAILED) · §7 요청 모양.
 */
class ProviderRetryBlindTest : ScriptedProviderBlindTest() {

    /** "기다리지 않고"의 허용 간격 — 응답을 받은 뒤 다음 호출까지(로컬 왕복 + 처리) 200ms 안. 백오프 · Retry-After 대기는 넘긴다. */
    private val immediateGapMs = 200L

    /** 응답이 바로 온 재시도(429 · 5xx) — 호출 간격 < 200ms. */
    private fun assertImmediate(calls: List<ScriptedProvider.Call>) = assertGaps(calls, immediateGapMs)

    /** 응답 유실 뒤 재시도 — 읽기 타임아웃(300ms)만큼은 기다린 뒤 200ms 안. */
    private fun assertImmediateAfterTimeout(calls: List<ScriptedProvider.Call>) =
        assertGaps(calls, ScriptedProviderBlindTest.READ_TIMEOUT_MS + immediateGapMs)

    private fun assertGaps(calls: List<ScriptedProvider.Call>, maxGapMs: Long) {
        calls.zipWithNext().forEach { (a, b) ->
            val gapMs = (b.atNanos - a.atNanos) / 1_000_000
            assertThat(gapMs).describedAs("재시도 간격 ${gapMs}ms — 즉시 재시도(백오프 없음) 상한 ${maxGapMs}ms").isLessThan(maxGapMs)
        }
    }

    @Test
    fun `발송사 요청 모양 - POST v1 채널 소문자 send, to = userId, title, body`() {
        val userId = newUserId()

        sendOk(userId = userId, channels = listOf("PUSH", "SMS", "EMAIL"), title = "제목 😀", body = "본문")

        val calls = provider.callsFor(userId)
        assertThat(calls.map { it.path }).containsExactly("/v1/push/send", "/v1/sms/send", "/v1/email/send")
        calls.forEach { c ->
            assertThat(c.method).isEqualTo("POST")
            assertThat(c.to).isEqualTo(userId)
            assertThat(c.toNode!!.isIntegralNumber).describedAs("to 는 JSON 정수").isTrue()
            assertThat(c.title).isEqualTo("제목 😀")
            assertThat(c.body).isEqualTo("본문")
        }
    }

    @Test
    fun `첫 시도 성공 - SENT, attempts 1, 호출 1번, providerMessageId = 발송사 messageId`() {
        val userId = newUserId()
        provider.script(userId, "sms", ok("msg-first"))

        val n = sendOk(userId = userId, channels = listOf("SMS"))[0]

        assertSent(n, 1)
        assertThat(n.textOrNull("providerMessageId")).isEqualTo("msg-first")
        assertThat(provider.callsFor(userId)).hasSize(1)
        assertMatchesRow(n)
    }

    @Test
    fun `500 두 번 뒤 성공 - SENT, attempts 3, 호출 3번, 마지막 접수 id`() {
        val userId = newUserId()
        provider.script(userId, "push", status(500), status(500), ok("msg-3"))

        val n = sendOk(userId = userId, channels = listOf("PUSH"))[0]

        assertSent(n, 3)
        assertThat(n.textOrNull("providerMessageId")).isEqualTo("msg-3")
        val calls = provider.callsFor(userId)
        assertThat(calls.map { it.status }).containsExactly(500, 500, 200)
        assertImmediate(calls)
        assertMatchesRow(n)
    }

    @Test
    fun `500 세 번 뒤 네 번째 성공 - SENT, attempts 4`() {
        val userId = newUserId()
        provider.script(userId, "email", status(500), status(503), status(502), ok("msg-4"))

        val n = sendOk(userId = userId, channels = listOf("EMAIL"))[0]

        assertSent(n, 4)
        assertThat(n.textOrNull("providerMessageId")).isEqualTo("msg-4")
        assertThat(provider.callsFor(userId)).hasSize(4)
        assertImmediate(provider.callsFor(userId))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = [500, 502, 503, 504])
    fun `5xx 네 번 - FAILED, attempts 4, 호출은 정확히 4번(다섯 번째 없음)`(code: Int) {
        val userId = newUserId()
        provider.script(userId, "push", status(code), status(code), status(code), status(code), ok("never"))

        val res = send(userId = userId, channels = listOf("PUSH"))

        assertThat(res.status).describedAs("발송사 실패는 HTTP 오류가 아니다 — ${res.body}").isEqualTo(201)
        val n = res.json[0]
        assertFailed(n, 4)
        val calls = provider.callsFor(userId)
        assertThat(calls).hasSize(4)
        assertImmediate(calls)
        assertMatchesRow(n)
    }

    @Test
    fun `429 뒤 성공 - Retry-After 30초를 기다리지 않고 바로 재시도, SENT attempts 2`() {
        val userId = newUserId()
        provider.script(userId, "sms", status(429, "Retry-After" to "30"), ok("msg-after-429"))

        val n = sendOk(userId = userId, channels = listOf("SMS"))[0]

        assertSent(n, 2)
        assertThat(n.textOrNull("providerMessageId")).isEqualTo("msg-after-429")
        val calls = provider.callsFor(userId)
        assertThat(calls.map { it.status }).containsExactly(429, 200)
        assertImmediate(calls)
    }

    @Test
    fun `429 네 번 - FAILED, attempts 4, 호출 4번`() {
        val userId = newUserId()
        val q = status(429, "Retry-After" to "30")
        provider.script(userId, "push", q, q, q, q, ok("never"))

        val n = sendOk(userId = userId, channels = listOf("PUSH"))[0]

        assertFailed(n, 4)
        val calls = provider.callsFor(userId)
        assertThat(calls).hasSize(4)
        assertImmediate(calls)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = [400, 401, 403, 404, 409, 422])
    fun `그 밖의 4xx - 재시도 없이 FAILED, attempts 1, 호출 1번`(code: Int) {
        val userId = newUserId()
        provider.script(userId, "email", status(code), ok("never"))

        val res = send(userId = userId, channels = listOf("EMAIL"))

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        val n = res.json[0]
        assertFailed(n, 1)
        assertThat(provider.callsFor(userId)).hasSize(1)
        assertMatchesRow(n)
    }

    @Test
    fun `응답 유실(읽기 타임아웃) 뒤 즉시 재시도 - 발송사는 두 번 접수(Q1), SENT attempts 2, 두 번째 접수 id`() {
        val userId = newUserId()
        provider.script(userId, "push", drop(holdMs = 1_500, messageId = "msg-lost"), ok("msg-retry"))

        val n = sendOk(userId = userId, channels = listOf("PUSH"))[0]

        assertSent(n, 2)
        assertThat(n.textOrNull("providerMessageId")).isEqualTo("msg-retry")
        val calls = provider.callsFor(userId)
        assertThat(calls.map { it.messageId }).describedAs("같은 메시지가 두 번 접수됐다").containsExactly("msg-lost", "msg-retry")
        assertThat(calls.map { it.title }.distinct()).hasSize(1)
        assertImmediateAfterTimeout(calls)
    }

    @Test
    fun `응답 유실 네 번 - FAILED, attempts 4, 발송사는 네 번 접수`() {
        val userId = newUserId()
        provider.script(userId, "sms", drop(), drop(), drop(), drop(), ok("never"))

        val n = sendOk(userId = userId, channels = listOf("SMS"))[0]

        assertFailed(n, 4)
        assertThat(provider.callsFor(userId)).hasSize(4)
        assertImmediateAfterTimeout(provider.callsFor(userId))
    }

    @Test
    fun `채널마다 따로 - 앞 채널 실패가 뒤 채널을 막지 않고, 실패도 롤백 없이 커밋`() {
        val userId = newUserId()
        val eventId = newEventId()
        provider.script(userId, "push", status(400))
        provider.script(userId, "sms", status(500), status(500), status(500), status(500))
        provider.script(userId, "email", ok("msg-email"))

        val res = sendOk(eventId = eventId, userId = userId, channels = listOf("PUSH", "SMS", "EMAIL"))

        assertFailed(res[0], 1)
        assertFailed(res[1], 4)
        assertSent(res[2], 1)
        assertThat(res[2].textOrNull("providerMessageId")).isEqualTo("msg-email")
        assertThat(provider.callsFor(userId).map { it.channel }).describedAs("채널 순서대로 · 재시도는 그 채널 안에서")
            .containsExactly("push", "sms", "sms", "sms", "sms", "email")
        val rows = rowsByEvent(eventId)
        assertThat(rows.map { it.str("status") }).containsExactly("FAILED", "FAILED", "SENT")
        res.forEach { assertMatchesRow(it) }
    }

    @Test
    fun `같은 eventId 재요청 - 발송사 호출도 두 번(Q1, 막지 않음)`() {
        val userId = newUserId()
        val eventId = newEventId()

        sendOk(eventId = eventId, userId = userId, channels = listOf("EMAIL"))
        sendOk(eventId = eventId, userId = userId, channels = listOf("EMAIL"))

        assertThat(provider.callsFor(userId)).hasSize(2)
        assertThat(rowsByEvent(eventId)).hasSize(2)
        assertThat(rowsByEvent(eventId).map { it.str("status") }).containsExactly("SENT", "SENT")
    }
}
