package com.jun.labs.notification.blind

import com.jun.labs.notification.blind.MockProviderLauncher.ChannelConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.ConfigurableApplicationContext
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * mock 발송사 단독(HTTP로만) — ADR-000 §7: `POST /v1/{channel}/send` `{to, title, body}` → `200 {messageId}` ·
 * 400 INVALID_REQUEST(to ≤ 0 · 빈 제목 · 본문) · 404(모르는 채널) · 429 QUOTA_EXCEEDED + Retry-After(초, 최소 1) · 장애 주입.
 * 앱 컨텍스트 · DB 없이 돈다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MockProviderBlindTest {

    /** push · email 기본 쿼터, sms 분당 1건. */
    private val normal: ConfigurableApplicationContext by lazy {
        MockProviderLauncher.start(sms = ChannelConfig(quotaPerMinute = 1))
    }

    /** push 항상 500 · sms 항상 응답 유실(1.5초) · email 지연 400ms 고정. */
    private val faulty: ConfigurableApplicationContext by lazy {
        MockProviderLauncher.start(
            push = ChannelConfig(quotaPerMinute = 600_000, errorRate = 1.0),
            sms = ChannelConfig(quotaPerMinute = 60_000, dropRate = 1.0, dropHoldMs = 1_500),
            email = ChannelConfig(quotaPerMinute = 120_000, latencyMinMs = 400, latencyMaxMs = 400),
        )
    }

    private val started = mutableListOf<ConfigurableApplicationContext>()

    private fun http(ctx: ConfigurableApplicationContext, timeout: Duration = Duration.ofSeconds(30)): JsonHttp {
        if (ctx !in started) started += ctx
        return JsonHttp(MockProviderLauncher.baseUrl(ctx), timeout)
    }

    private fun message(to: Any?, title: Any? = "제목", body: Any? = "본문") = json("to" to to, "title" to title, "body" to body)

    @AfterAll
    fun stop() {
        started.forEach { runCatching { it.close() } }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["push", "email"])
    fun `채널마다 접수 - 200 messageId, 보낼 때마다 다른 id`(channel: String) {
        val h = http(normal)
        val a = h.post("/v1/$channel/send", message(1001))
        val b = h.post("/v1/$channel/send", message(1001))

        assertThat(a.status).describedAs(a.body).isEqualTo(200)
        assertThat(b.status).describedAs(b.body).isEqualTo(200)
        assertThat(a.json.textOrNull("messageId")).isNotBlank()
        assertThat(b.json.textOrNull("messageId")).isNotBlank().isNotEqualTo(a.json.textOrNull("messageId"))
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["fax", "kakao", "web"])
    fun `모르는 채널 - 404`(channel: String) {
        val res = http(normal).post("/v1/$channel/send", message(1001))
        assertThat(res.status).describedAs(res.body).isEqualTo(404)
    }

    @Test
    fun `입력 위반 - to 0 · 음수, 빈 제목, 빈 본문은 400 INVALID_REQUEST`() {
        val h = http(normal)
        listOf(
            message(0),
            message(-1),
            message(1001, title = ""),
            message(1001, body = ""),
        ).forEach { body ->
            val res = h.post("/v1/push/send", body)
            assertThat(res.status).describedAs("$body → ${res.body}").isEqualTo(400)
            assertThat(res.code).describedAs("$body → ${res.body}").isEqualTo("INVALID_REQUEST")
        }
    }

    @Test
    fun `쿼터 초과 - 429 QUOTA_EXCEEDED, Retry-After 는 1 이상 60 이하 정수 초, 다른 채널은 영향 없음`() {
        val h = http(normal)
        // 분당 1건 — 창 경계를 우연히 넘어도 3번 중 429가 하나는 나온다
        val results = (1..3).map { h.post("/v1/sms/send", message(2002)) }

        val rejected = results.firstOrNull { it.status == 429 } ?: error("분당 1건 쿼터에서 3번 보내도 429가 없다 — ${results.map { it.status }}")
        assertThat(results.count { it.status == 200 }).isLessThanOrEqualTo(2)
        assertThat(rejected.code).describedAs(rejected.body).isEqualTo("QUOTA_EXCEEDED")
        val retryAfter = rejected.header("Retry-After")
        assertThat(retryAfter).describedAs("Retry-After 헤더").isNotNull()
        assertThat(retryAfter!!.trim().toLongOrNull()).describedAs("Retry-After = 초 정수 — $retryAfter").isNotNull()
        assertThat(retryAfter.trim().toLong()).isBetween(1L, 60L)

        val push = h.post("/v1/push/send", message(2002))
        assertThat(push.status).describedAs("push 쿼터는 따로 — ${push.body}").isEqualTo(200)
    }

    @Test
    fun `장애 주입 error-rate 1 - 항상 500`() {
        val h = http(faulty)
        repeat(3) {
            val res = h.post("/v1/push/send", message(3003))
            assertThat(res.status).describedAs(res.body).isEqualTo(500)
        }
    }

    @Test
    fun `장애 주입 drop-rate 1 - drop-hold 동안 응답하지 않는다`() {
        val h = http(faulty, Duration.ofMillis(500))
        assertThatThrownBy { h.post("/v1/sms/send", message(3003)) }.isInstanceOf(HttpTimeoutException::class.java)
    }

    @Test
    fun `장애 주입 latency 400ms 고정 - 응답까지 400ms 이상, 그 뒤 200`() {
        val h = http(faulty)
        val started = System.nanoTime()
        val res = h.post("/v1/email/send", message(3003))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertThat(res.status).describedAs(res.body).isEqualTo(200)
        assertThat(elapsedMs).isGreaterThanOrEqualTo(400L)
    }
}
