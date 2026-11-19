package com.jun.labs.notification.impl

import com.jun.labs.notification.support.Postgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 흉내 발송사 장애 — push 항상 500 · sms 쿼터 0(항상 429) · email 항상 응답 유실 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderFailureApiTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private val http by lazy { Http("http://localhost:$port") }

    @Test
    fun `발송사 실패는 201 안의 FAILED — 500 · 429 · 읽기 타임아웃 모두 즉시 재시도로 attempts 4`() {
        val eventId = Ids.eventId()
        val r = http.post(
            "/notifications",
            """{"eventId":"$eventId","eventType":"T","userId":${Ids.userId()},"channels":["PUSH","SMS","EMAIL"],"title":"t","body":"b"}""",
        )
        assertEquals(201, r.status)
        assertEquals(listOf("FAILED", "FAILED", "FAILED"), r.body.map { it["status"].asText() })
        assertEquals(listOf(4, 4, 4), r.body.map { it["attempts"].asInt() })
        assertEquals(listOf("HTTP_500 INTERNAL_ERROR", "HTTP_429 QUOTA_EXCEEDED", "READ_TIMEOUT"), r.body.map { it["lastError"].asText() })
        assertTrue(r.body.all { it["providerMessageId"].isNull && it["sentAt"].isNull })

        val rows = jdbc.queryForList("select status, attempts, last_error from notification where event_id = ? order by id", eventId)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it["status"] == "FAILED" && it["attempts"] == 4 && it["last_error"] != null })
    }

    @Test
    fun `캠페인 대상이 모두 실패해도 COMPLETED · failed 수`() {
        val accepted = http.post(
            "/campaigns",
            """{"name":"n","channel":"SMS","userIdStart":${Ids.userId() * 1000},"targetCount":5,"title":"t","body":"b"}""",
        )
        assertEquals(202, accepted.status)
        val id = accepted.body["campaignId"].asLong()
        val done = awaitUntil(Duration.ofSeconds(30), probe = { http.get("/campaigns/$id").body }) { it["status"].asText() == "COMPLETED" }
        assertEquals(5, done["failed"].asInt())
        assertEquals(0, done["sent"].asInt())
        assertEquals(
            5,
            jdbc.queryForObject("select count(*) from notification where campaign_id = ? and attempts = 4 and last_error = 'HTTP_429 QUOTA_EXCEEDED'", Int::class.java, id),
        )
    }

    companion object {
        private val provider by lazy {
            MockProviderLauncher.start(
                "mockprovider.channels.push.error-rate=1.0",
                "mockprovider.channels.sms.quota-per-minute=0",
                "mockprovider.channels.email.drop-rate=1.0",
                "mockprovider.channels.email.drop-hold-ms=1000",
            )
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
            registry.add("notification.provider.base-url") { MockProviderLauncher.baseUrl(provider) }
            registry.add("notification.provider.read-timeout") { "300ms" }
            registry.add("notification.campaign.pool-size") { "2" }
        }

        @JvmStatic
        @AfterAll
        fun stopProvider() = provider.close()
    }
}
