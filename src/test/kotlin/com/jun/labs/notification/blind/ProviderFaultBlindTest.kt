package com.jun.labs.notification.blind

import com.jun.labs.notification.blind.MockProviderLauncher.ChannelConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 실제 mock 발송사의 장애 주입(비율 1.0 = 항상)과 앱 재시도의 결합 — ADR-000 §7 장애 주입 · §3 재시도 · §6(201 안의 FAILED).
 * push: error-rate 1.0(500) · sms: drop-rate 1.0(응답 유실, drop-hold 1초 · 앱 읽기 타임아웃 300ms) · email: 장애 없음.
 */
class ProviderFaultBlindTest : BlindTestSupport() {

    @Test
    fun `발송사 500(항상) - FAILED, attempts 4, lastError`() {
        val userId = newUserId()
        val res = send(userId = userId, channels = listOf("PUSH"))

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertFailed(res.json[0], 4)
        assertMatchesRow(res.json[0])
    }

    @Test
    fun `응답 유실(항상) - 읽기 타임아웃마다 즉시 재시도, FAILED, attempts 4`() {
        val userId = newUserId()
        val started = System.nanoTime()
        val res = send(userId = userId, channels = listOf("SMS"))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertFailed(res.json[0], 4)
        assertThat(elapsedMs).describedAs("4번 × 읽기 타임아웃 300ms 이상 기다렸다").isGreaterThanOrEqualTo(4 * READ_TIMEOUT_MS)
        assertMatchesRow(res.json[0])
    }

    @Test
    fun `채널 섞기 - PUSH 실패 · SMS 실패 · EMAIL 성공이 채널 순서대로 한 응답에`() {
        val eventId = newEventId()
        val res = sendOk(eventId = eventId, channels = listOf("PUSH", "SMS", "EMAIL"))

        assertFailed(res[0], 4)
        assertFailed(res[1], 4)
        assertSent(res[2], 1)
        assertThat(rowsByEvent(eventId).map { it.str("status") }).containsExactly("FAILED", "FAILED", "SENT")
    }

    @Test
    fun `캠페인 - 대상 모두 500 이면 COMPLETED, failed = targetCount, 행마다 attempts 4`() {
        val c = createCampaignOk(targetCount = 3, channel = "PUSH")
        val id = c["campaignId"].asLong()

        val done = awaitCompleted(id)

        assertThat(done["requested"].asLong()).isEqualTo(0L)
        assertThat(done["sent"].asLong()).isEqualTo(0L)
        assertThat(done["failed"].asLong()).isEqualTo(3L)
        val rows = rowsByCampaign(id)
        assertThat(rows).hasSize(3)
        rows.forEach { r ->
            assertThat(r.str("status")).isEqualTo("FAILED")
            assertThat(r.long("attempts")).isEqualTo(4L)
            assertThat(r.str("last_error")).isNotBlank()
            assertThat(r["provider_message_id"]).isNull()
        }
    }

    companion object {
        const val READ_TIMEOUT_MS = 300L

        private val provider: ConfigurableApplicationContext by lazy {
            MockProviderLauncher.start(
                push = ChannelConfig(quotaPerMinute = 600_000, errorRate = 1.0),
                sms = ChannelConfig(quotaPerMinute = 60_000, dropRate = 1.0, dropHoldMs = 1_000),
                email = MockProviderLauncher.healthy("email"),
            )
        }

        @JvmStatic
        @DynamicPropertySource
        fun faultProviderProperties(registry: DynamicPropertyRegistry) {
            registry.add("notification.provider.base-url") { MockProviderLauncher.baseUrl(provider) }
            registry.add("notification.provider.read-timeout") { "${READ_TIMEOUT_MS}ms" }
        }
    }
}
