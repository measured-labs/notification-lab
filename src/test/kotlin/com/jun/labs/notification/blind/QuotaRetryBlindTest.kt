package com.jun.labs.notification.blind

import com.fasterxml.jackson.databind.JsonNode
import com.jun.labs.notification.blind.MockProviderLauncher.ChannelConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 실제 mock 발송사 쿼터(채널마다 분당 1건)와 앱 즉시 재시도 — ADR-000 §7 쿼터 · 429 · §3(429 즉시 재시도 · Retry-After 무시) · Q2.
 * 쿼터 창 경계(60초)를 우연히 넘을 수 있어 FAILED가 나올 때까지 최대 4번 보낸다.
 */
class QuotaRetryBlindTest : BlindTestSupport() {

    @Test
    fun `쿼터 소진 뒤 - 429 를 즉시 4번 시도하고 FAILED, Retry-After 를 기다리지 않는다`() {
        val results = mutableListOf<Pair<JsonNode, Long>>()
        while (results.none { it.first.textOrNull("status") == "FAILED" } && results.size < 4) {
            val started = System.nanoTime()
            val n = sendOk(channels = listOf("PUSH"))[0]
            results += n to (System.nanoTime() - started) / 1_000_000
        }

        val (failed, elapsedMs) = results.firstOrNull { it.first.textOrNull("status") == "FAILED" }
            ?: error("분당 1건 쿼터에서 4번 보내도 FAILED가 없다 — ${results.map { it.first }}")
        assertFailed(failed, 4)
        // 로컬 왕복 4번 — 백오프 · Retry-After(최소 1초) 대기가 끼면 넘긴다. 정확한 호출 간격은 ProviderRetryBlindTest(대역 · Retry-After 30)가 본다
        assertThat(elapsedMs).describedAs("429 네 번을 기다리지 않고 — ${elapsedMs}ms").isLessThan(400L)
        assertMatchesRow(failed)
        assertThat(results.first().first.textOrNull("status")).describedAs("첫 건은 쿼터 안").isEqualTo("SENT")
    }

    companion object {
        private val provider: ConfigurableApplicationContext by lazy {
            MockProviderLauncher.start(
                push = ChannelConfig(quotaPerMinute = 1),
                sms = ChannelConfig(quotaPerMinute = 1),
                email = ChannelConfig(quotaPerMinute = 1),
            )
        }

        @JvmStatic
        @DynamicPropertySource
        fun quotaProviderProperties(registry: DynamicPropertyRegistry) {
            registry.add("notification.provider.base-url") { MockProviderLauncher.baseUrl(provider) }
        }
    }
}
