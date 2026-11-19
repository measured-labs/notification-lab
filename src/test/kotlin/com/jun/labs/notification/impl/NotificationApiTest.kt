package com.jun.labs.notification.impl

import com.jun.labs.notification.domain.Channel
import com.jun.labs.notification.domain.ErrorCode
import com.jun.labs.notification.domain.NotificationException
import com.jun.labs.notification.service.SendNotificationCommand
import com.jun.labs.notification.service.SendNotificationUseCase
import com.jun.labs.notification.service.impl.CampaignDispatcher
import com.jun.labs.notification.support.Postgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 정상 경로 · 조회 · 입력 검증 · 캠페인 디스패처(풀 2 · 큐 4) */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationApiTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var dispatcher: CampaignDispatcher

    @Autowired
    private lateinit var sendUseCase: SendNotificationUseCase

    private val http by lazy { Http("http://localhost:$port") }

    private fun sendJson(eventId: String, userId: Long, channels: String = """["PUSH","SMS","EMAIL"]""") =
        """{"eventId":"$eventId","eventType":"ORDER_PAID","userId":$userId,"channels":$channels,"title":"주문 완료","body":"결제가 끝났습니다"}"""

    @Test
    fun `트랜잭셔널 발송 — 채널 순서대로 1행씩 SENT · 201`() {
        val eventId = Ids.eventId()
        val userId = Ids.userId()
        val r = http.post("/notifications", sendJson(eventId, userId, """["EMAIL","PUSH","SMS"]"""))
        assertEquals(201, r.status)
        assertEquals(listOf("EMAIL", "PUSH", "SMS"), r.body.map { it["channel"].asText() })
        r.body.forEach {
            assertEquals("SENT", it["status"].asText())
            assertEquals("TRANSACTIONAL", it["kind"].asText())
            assertEquals(1, it["attempts"].asInt())
            assertTrue(it["providerMessageId"].asText().startsWith("msg_"))
            assertTrue(it["lastError"].isNull)
            assertTrue(it["campaignId"].isNull)
            assertEquals(eventId, it["eventId"].asText())
            assertEquals(userId, it["userId"].asLong())
            assertTrue(it["sentAt"].isTextual)
        }
        val ids = r.body.map { it["notificationId"].asLong() }
        assertEquals(ids.sorted(), ids)

        val rows = jdbc.queryForList("select * from notification where event_id = ? order by id", eventId)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it["status"] == "SENT" && it["attempts"] == 1 && it["provider_message_id"] != null })

        val one = http.get("/notifications/${ids[1]}")
        assertEquals(200, one.status)
        assertEquals(r.body[1], one.body)

        val byEvent = http.get("/notifications?eventId=$eventId")
        assertEquals(200, byEvent.status)
        assertEquals(r.body, byEvent.body)
    }

    @Test
    fun `같은 eventId를 두 번 보내면 두 번 발송한다(중복 검사 없음)`() {
        val eventId = Ids.eventId()
        val userId = Ids.userId()
        assertEquals(201, http.post("/notifications", sendJson(eventId, userId, """["PUSH"]""")).status)
        assertEquals(201, http.post("/notifications", sendJson(eventId, userId, """["PUSH"]""")).status)
        assertEquals(2, http.get("/notifications?eventId=$eventId").body.size())
    }

    @Test
    fun `없는 id는 404 · 경로 id 형식은 400`() {
        assertEquals("NOTIFICATION_NOT_FOUND", http.get("/notifications/${Long.MAX_VALUE}").body["code"].asText())
        assertEquals(404, http.get("/notifications/${Long.MAX_VALUE}").status)
        assertEquals("CAMPAIGN_NOT_FOUND", http.get("/campaigns/${Long.MAX_VALUE}").body["code"].asText())
        for (bad in listOf("0", "-1", "+5", "abc", "9223372036854775808")) {
            assertEquals(400, http.get("/notifications/$bad").status, bad)
            assertEquals(400, http.get("/campaigns/$bad").status, bad)
        }
        assertEquals(400, http.get("/notifications").status)
        assertEquals(400, http.get("/notifications?eventId=%20").status)
        assertEquals(200, http.get("/notifications?eventId=${Ids.eventId()}").status)
    }

    @Test
    fun `입력 검증 위반은 400 INVALID_REQUEST · 아무것도 저장하지 않는다`() {
        val eventId = Ids.eventId()
        val base = mapOf(
            "eventId" to "\"$eventId\"", "eventType" to "\"T\"", "userId" to "1",
            "channels" to """["PUSH"]""", "title" to "\"t\"", "body" to "\"b\"",
        )
        fun json(override: Map<String, String?>): String =
            (base + override).filterValues { it != null }.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }

        val cases = listOf(
            mapOf("eventId" to null), mapOf("eventId" to "\" \""), mapOf("eventId" to "\"${"e".repeat(65)}\""), mapOf("eventId" to "1"),
            mapOf("eventType" to "\"${"t".repeat(51)}\""),
            mapOf("userId" to "0"), mapOf("userId" to "1.5"), mapOf("userId" to "\"1\""), mapOf("userId" to "true"),
            mapOf("userId" to "9223372036854775808"),
            mapOf("channels" to "[]"), mapOf("channels" to """["PUSH","PUSH"]"""), mapOf("channels" to """["push"]"""),
            mapOf("channels" to """["PUSH","SMS","EMAIL","PUSH"]"""), mapOf("channels" to "[null]"), mapOf("channels" to "[1]"),
            mapOf("channels" to "\"PUSH\""),
            mapOf("title" to "\"${"t".repeat(101)}\""), mapOf("title" to "\"a\\u0000b\""), mapOf("title" to "\"\\ud800\""),
            mapOf("body" to "\"${"b".repeat(1001)}\""), mapOf("body" to "\"\""),
            mapOf("extra" to "1"),
        )
        for (c in cases) {
            val r = http.post("/notifications", json(c))
            assertEquals(400, r.status, c.toString())
            assertEquals("INVALID_REQUEST", r.body["code"].asText(), c.toString())
            assertEquals(setOf("code", "message"), r.body.fieldNames().asSequence().toSet())
        }
        val valid = json(emptyMap())
        for (raw in listOf("{", "null", "[]", "", "$valid {}", "$valid x", valid.replace("{\"eventId\"", "{\"title\":\"x\",\"eventId\""))) {
            assertEquals(400, http.post("/notifications", raw).status, raw)
        }
        assertEquals(400, http.post("/notifications", null).status)
        assertEquals(0, jdbc.queryForObject("select count(*) from notification where event_id = ?", Int::class.java, eventId))
        // 경계값 — 100자 제목 · 이모지(1자) 1,000자 본문
        val ok = json(mapOf("title" to "\"${"t".repeat(100)}\"", "body" to "\"${"\uD83D\uDE00".repeat(1000)}\""))
        assertEquals(201, http.post("/notifications", ok).status)
    }

    @Test
    fun `채널 개수 · 중복은 API 밖 명령으로 서비스를 불러도 400 · 저장 없음`() {
        val eventId = Ids.eventId()
        for (channels in listOf(emptyList(), listOf(Channel.PUSH, Channel.PUSH), listOf(Channel.PUSH, Channel.SMS, Channel.EMAIL, Channel.SMS))) {
            val e = assertThrows<NotificationException> {
                sendUseCase.send(SendNotificationCommand(eventId, "T", Ids.userId(), channels, "t", "b"))
            }
            assertEquals(ErrorCode.INVALID_REQUEST, e.errorCode)
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from notification where event_id = ?", Int::class.java, eventId))
    }

    @Test
    fun `캠페인 입력 검증`() {
        fun json(userIdStart: String = "1", targetCount: String = "1", channel: String = "\"SMS\"", name: String = "\"n\"") =
            """{"name":$name,"channel":$channel,"userIdStart":$userIdStart,"targetCount":$targetCount,"title":"t","body":"b"}"""
        val before = jdbc.queryForObject("select count(*) from campaign", Long::class.java)
        for (bad in listOf(
            json(targetCount = "0"), json(targetCount = "1000001"), json(targetCount = "1.0"), json(targetCount = "\"1\""),
            json(userIdStart = "0"), json(userIdStart = "9223372036854775807", targetCount = "2"),
            json(channel = "\"FAX\""), json(channel = "[\"SMS\"]"), json(name = "\" \""), json(name = "\"${"n".repeat(101)}\""),
        )) {
            val r = http.post("/campaigns", bad)
            assertEquals(400, r.status, bad)
            assertEquals("INVALID_REQUEST", r.body["code"].asText())
        }
        assertEquals(before, jdbc.queryForObject("select count(*) from campaign", Long::class.java))
    }

    @Test
    fun `표준 오류도 code · message — 405 Allow 유지 · 없는 경로 404`() {
        val r = http.get("/campaigns")
        assertEquals(405, r.status)
        assertEquals("HTTP_405", r.body["code"].asText())
        assertTrue(r.headers.keys.any { it.equals("Allow", ignoreCase = true) })
        assertEquals("HTTP_404", http.get("/nope").body["code"].asText())
    }

    @Test
    fun `캠페인 — 202 접수 스냅샷 → 대상 수만큼 SENT → COMPLETED · 발송 풀 큐는 pool-size × 2를 넘지 않는다`() {
        val start = Ids.userId() * 1000
        val target = 40
        val maxQueued = AtomicInteger()
        val maxActive = AtomicInteger()
        val sampling = Thread.ofPlatform().daemon(true).start {
            while (!Thread.currentThread().isInterrupted) {
                maxQueued.accumulateAndGet(dispatcher.queuedTasks(), ::maxOf)
                maxActive.accumulateAndGet(dispatcher.activeTasks(), ::maxOf)
                try { Thread.sleep(1) } catch (e: InterruptedException) { return@start }
            }
        }

        val accepted = http.post(
            "/campaigns",
            """{"name":"가을 세일","channel":"EMAIL","userIdStart":$start,"targetCount":$target,"title":"t","body":"b"}""",
        )
        assertEquals(202, accepted.status)
        assertEquals("RUNNING", accepted.body["status"].asText())
        assertEquals(listOf(0, 0, 0), listOf("requested", "sent", "failed").map { accepted.body[it].asInt() })
        assertTrue(accepted.body["completedAt"].isNull)
        val id = accepted.body["campaignId"].asLong()

        val done = awaitUntil(Duration.ofSeconds(30), probe = { http.get("/campaigns/$id").body }) { it["status"].asText() == "COMPLETED" }
        sampling.interrupt()
        assertEquals(target, done["sent"].asInt())
        assertEquals(0, done["failed"].asInt())
        assertEquals(0, done["requested"].asInt())
        assertEquals(target, done["targetCount"].asInt())
        assertTrue(done["completedAt"].isTextual)

        val rows = jdbc.queryForList("select user_id, kind, status, attempts from notification where campaign_id = ? order by user_id", id)
        assertEquals((start until start + target).toList(), rows.map { (it["user_id"] as Number).toLong() })
        assertTrue(rows.all { it["kind"] == "CAMPAIGN" && it["status"] == "SENT" && it["attempts"] == 1 })

        // 풀 2 · 큐 4 — 지연 20~60ms × 40건이라 큐가 찬 채로 블로킹 제출이 일어난다
        assertEquals(4, maxQueued.get(), "큐가 상한까지 찼다가 넘지 않는다")
        assertTrue(maxActive.get() <= 2, "실행 중 ${maxActive.get()}")
    }

    companion object {
        private val provider by lazy { MockProviderLauncher.start("mockprovider.channels.email.latency-min-ms=20", "mockprovider.channels.email.latency-max-ms=60") }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
            registry.add("notification.provider.base-url") { MockProviderLauncher.baseUrl(provider) }
            registry.add("notification.campaign.pool-size") { "2" }
        }

        @JvmStatic
        @AfterAll
        fun stopProvider() = provider.close()
    }
}
