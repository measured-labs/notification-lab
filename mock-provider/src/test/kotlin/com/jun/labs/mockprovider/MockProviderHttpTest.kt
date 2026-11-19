package com.jun.labs.mockprovider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 쿼터 창이 벽시계에 따라 흔들리지 않게 시계를 고정한다(앱 기본 `mockProviderClock`보다 우선) */
@Configuration(proxyBeanMethods = false)
class FixedClockConfig {
    @Bean
    @Primary
    fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-11-19T00:00:17.250Z"), ZoneOffset.UTC)
}

/** HTTP로만 — push 정상 · sms 쿼터 2 · email 항상 500 */
class MockProviderHttpTest {
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val mapper = ObjectMapper()

    private fun post(path: String, json: String, base: String = baseUrl): Triple<Int, JsonNode, HttpResponse<String>> {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Triple(response.statusCode(), mapper.readTree(response.body().ifEmpty { "null" }), response)
    }

    private val message = """{"to":1,"title":"t","body":"b"}"""

    @Test
    fun `접수하면 200 messageId`() {
        val (status, body) = post("/v1/push/send", message)
        assertEquals(200, status)
        assertTrue(body["messageId"].asText().startsWith("msg_"))
        val (_, again) = post("/v1/push/send", message)
        assertTrue(body["messageId"] != again["messageId"])
    }

    @Test
    fun `모르는 채널 404 · 형식 400 — 깨진 JSON은 채널 검사 전에 400`() {
        assertEquals(404, post("/v1/fax/send", message).first)
        assertEquals(404, post("/v1/PUSH/send", message).first)
        assertEquals(404, post("/v1/fax/send", "").first)
        assertEquals(400, post("/v1/fax/send", "{").first)
        for (bad in listOf("""{"to":0,"title":"t","body":"b"}""", """{"title":"t","body":"b"}""", """{"to":1,"title":" ","body":"b"}""", """{"to":1,"title":"t"}""", "{")) {
            val (status, body) = post("/v1/push/send", bad)
            assertEquals(400, status, bad)
            assertEquals("INVALID_REQUEST", body["code"].asText())
        }
    }

    @Test
    fun `쿼터를 넘으면 429 QUOTA_EXCEEDED · Retry-After = 창 끝까지 남은 초(시계 고정 — 창 경계에 걸리지 않는다)`() {
        val results = (1..4).map { post("/v1/sms/send", message) }
        assertEquals(listOf(200, 200, 429, 429), results.map { it.first })
        assertEquals("QUOTA_EXCEEDED", results[2].second["code"].asText())
        // 고정 시계: 기동 시각 t0 그대로 — 남은 시간 60초
        assertEquals(listOf("60", "60"), results.drop(2).map { it.third.headers().firstValue("Retry-After").orElseThrow() })
    }

    @Test
    fun `error-rate 1이면 항상 500 · 처리하지 않으므로 쿼터를 쓰지 않는다`() {
        repeat(3) {
            val (status, body) = post("/v1/email/send", message)
            assertEquals(500, status)
            assertEquals("INTERNAL_ERROR", body["code"].asText())
        }
    }

    @Test
    fun `drop-rate 1이면 접수한 뒤 drop-hold-ms 동안 응답을 쥔다 · 지연 주입`() {
        val ctx = launch(
            "mockprovider.channels.push.drop-rate=1.0", "mockprovider.channels.push.drop-hold-ms=400",
            "mockprovider.channels.sms.latency-min-ms=150", "mockprovider.channels.sms.latency-max-ms=200",
        )
        try {
            val base = "http://localhost:" + ctx.environment.getProperty("local.server.port")
            val t1 = System.nanoTime()
            assertEquals(200, post("/v1/push/send", message, base).first)
            assertTrue(Duration.ofNanos(System.nanoTime() - t1) >= Duration.ofMillis(400))
            val t2 = System.nanoTime()
            assertEquals(200, post("/v1/sms/send", message, base).first)
            assertTrue(Duration.ofNanos(System.nanoTime() - t2) >= Duration.ofMillis(150))
        } finally {
            ctx.close()
        }
    }

    companion object {
        fun launch(vararg properties: String): ConfigurableApplicationContext =
            SpringApplicationBuilder(MockProviderApplication::class.java, FixedClockConfig::class.java)
                .run("--server.port=0", "--spring.main.banner-mode=off", *properties.map { "--$it" }.toTypedArray())

        private val context = launch("mockprovider.channels.sms.quota-per-minute=2", "mockprovider.channels.email.error-rate=1.0")
        private val baseUrl = "http://localhost:" + context.environment.getProperty("local.server.port")

        @JvmStatic
        @AfterAll
        fun stop() = context.close()
    }
}
