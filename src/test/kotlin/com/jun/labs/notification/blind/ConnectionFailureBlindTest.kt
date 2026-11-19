package com.jun.labs.notification.blind

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 발송사 연결 실패 — 재시도 대상(ADR-000 §3) · 재시도 소진 → 201 안의 FAILED(§6).
 * 포트를 listen 없이 bind만 해서 JVM 끝까지 쥔다 — 그 포트로의 연결은 거부되고(RST), 다른 소켓이 그 포트에서 listen할 수 없다.
 */
class ConnectionFailureBlindTest : BlindTestSupport() {

    @Test
    fun `연결 실패 - FAILED, attempts 4, lastError`() {
        val userId = newUserId()
        val res = send(userId = userId, channels = listOf("EMAIL", "PUSH"))

        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertFailed(res.json[0], 4)
        assertFailed(res.json[1], 4)
        res.json.forEach { assertMatchesRow(it) }
        assertThat(rowsByUser(userId).map { it.str("status") }).containsExactly("FAILED", "FAILED")
    }

    companion object {
        private val boundNotListening: Socket by lazy {
            Socket().apply {
                reuseAddress = false
                bind(InetSocketAddress("127.0.0.1", 0))
            }
        }

        private val closedPort: Int get() = boundNotListening.localPort

        @JvmStatic
        @DynamicPropertySource
        fun refusedProviderProperties(registry: DynamicPropertyRegistry) {
            registry.add("notification.provider.base-url") { "http://127.0.0.1:$closedPort" }
        }
    }
}
