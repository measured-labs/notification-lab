package com.jun.labs.notification.impl

import com.jun.labs.notification.domain.Channel
import com.jun.labs.notification.infra.provider.ProviderClient
import com.jun.labs.notification.infra.provider.ProviderResult
import com.jun.labs.notification.support.Postgres
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 발송사 자리에 조종 가능한 ProviderClient — 막아 두기 · 실행 스레드 기록 · 특정 대상 예외 */
class ControllableProvider : ProviderClient {
    @Volatile var gate: CountDownLatch? = null
    @Volatile var throwFor: Set<Long> = emptySet()
    val threads = ConcurrentLinkedQueue<String>()

    override fun send(channel: Channel, to: Long, title: String, body: String): ProviderResult {
        threads += Thread.currentThread().name
        gate?.await()
        if (to in throwFor) throw IllegalStateException("주입한 대상 예외: $to")
        return ProviderResult.Accepted("m-$to")
    }
}

/** 디스패처 — 블로킹 제출(풀 2 · 큐 4) · 발송은 발송 풀 스레드에서만 · 대상 처리 예외 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CampaignDispatcherTest {
    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun controllableProvider() = ControllableProvider()
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var provider: ControllableProvider

    @Autowired
    private lateinit var dispatcher: com.jun.labs.notification.service.impl.CampaignDispatcher

    private val http by lazy { Http("http://localhost:$port") }

    @BeforeEach
    fun reset() {
        provider.gate = null
        provider.throwFor = emptySet()
        provider.threads.clear()
    }

    private fun start(userIdStart: Long, targetCount: Int): Long {
        val r = http.post(
            "/campaigns",
            """{"name":"n","channel":"PUSH","userIdStart":$userIdStart,"targetCount":$targetCount,"title":"t","body":"b"}""",
        )
        assertEquals(202, r.status)
        return r.body["campaignId"].asLong()
    }

    private fun awaitCompleted(id: Long) =
        awaitUntil(Duration.ofSeconds(30), probe = { http.get("/campaigns/$id").body }) { it["status"].asText() == "COMPLETED" }

    @Test
    fun `풀이 막히면 디스패처는 제출(큐 put) 앞에서 기다리고 · 발송은 모두 발송 풀 스레드에서 실행된다`() {
        val gate = CountDownLatch(1)
        provider.gate = gate
        val target = 20
        val id = start(Ids.userId() * 1000, target)

        // 풀 2개 실행 중(막힘) · 큐 4 가득
        awaitUntil(Duration.ofSeconds(10), probe = { dispatcher.activeTasks() to dispatcher.queuedTasks() }) { it == (2 to 4) }
        val dispatcherThread = awaitUntil(Duration.ofSeconds(10), probe = {
            Thread.getAllStackTraces().keys.firstOrNull { it.name == "campaign-dispatcher-$id" }
        }) { it != null }!!
        awaitUntil(Duration.ofSeconds(10), probe = { dispatcherThread.state to dispatcherThread.stackTrace.toList() }) { (state, stack) ->
            (state == Thread.State.WAITING || state == Thread.State.BLOCKED) &&
                stack.any { it.className.endsWith("ArrayBlockingQueue") && it.methodName == "put" }
        }
        // 막힌 동안 더 쌓이지 않는다 — 실행 2 + 큐 4
        Thread.sleep(200)
        assertEquals(2, provider.threads.size)
        assertEquals(4, dispatcher.queuedTasks())

        gate.countDown()
        val done = awaitCompleted(id)
        assertEquals(target, done["sent"].asInt())
        assertEquals(target, provider.threads.size)
        assertTrue(provider.threads.all { it.startsWith("campaign-send-") }, provider.threads.toString())
        assertFalse(provider.threads.any { it.startsWith("campaign-dispatcher-") })
    }

    @Test
    fun `대상 처리 예외는 그 대상만 롤백 — 캠페인은 COMPLETED · 행은 targetCount보다 적다`() {
        val startId = Ids.userId() * 1000
        provider.throwFor = setOf(startId + 3, startId + 7)
        val id = start(startId, 10)
        val done = awaitCompleted(id)
        assertEquals(10, done["targetCount"].asInt())
        assertEquals(8, done["sent"].asInt())
        assertEquals(0, done["failed"].asInt())
        val users = jdbc.queryForList("select user_id from notification where campaign_id = ?", Long::class.java, id).toSet()
        assertEquals(8, users.size)
        assertFalse(startId + 3 in users || startId + 7 in users)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
            registry.add("notification.campaign.pool-size") { "2" }
        }
    }
}
