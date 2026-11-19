package com.jun.labs.notification.impl

import com.jun.labs.notification.domain.Channel
import com.jun.labs.notification.domain.Notification
import com.jun.labs.notification.domain.NotificationStatus
import com.jun.labs.notification.infra.provider.ProviderCallInterruptedException
import com.jun.labs.notification.infra.provider.RestProviderClient
import com.jun.labs.notification.service.NotificationProperties
import com.jun.labs.notification.service.impl.ProviderDelivery
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 재시도 분류 — 발송사 자리에 응답을 정해 둔 스텁을 세우고 호출 수를 센다 */
class ProviderDeliveryTest {
    private var server: HttpServer? = null
    private val calls = AtomicInteger()

    /** n번째 호출(1부터)마다 handler가 응답을 쓴다 */
    private fun stub(handler: (Int, HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex ->
            ex.requestBody.readAllBytes()
            runCatching { handler(calls.incrementAndGet(), ex) }
            ex.close()
        }
        s.executor = Executors.newCachedThreadPool()
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun reply(status: Int, body: String, sleepMs: Long = 0): (Int, HttpExchange) -> Unit = { _, ex -> write(ex, status, body, sleepMs) }

    private fun write(ex: HttpExchange, status: Int, body: String, sleepMs: Long = 0) {
        if (sleepMs > 0) Thread.sleep(sleepMs)
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun delivery(baseUrl: String, readTimeout: Duration = Duration.ofSeconds(5), maxRetries: Int = 3): ProviderDelivery {
        val props = NotificationProperties(
            provider = NotificationProperties.Provider(baseUrl = baseUrl, readTimeout = readTimeout),
            retry = NotificationProperties.Retry(maxRetries = maxRetries),
        )
        return ProviderDelivery(RestProviderClient(props, RestClient.builder()), props, Clock.systemUTC())
    }

    private fun newNotification() = Notification.transactional("ev", "T", 5, Channel.SMS, "t", "b", Clock.systemUTC().instant())

    private fun deliver(d: ProviderDelivery): Notification = newNotification().also(d::deliver)

    private val ok = reply(200, """{"messageId":"msg_abc"}""")

    @Test
    fun `200 = 한 번에 SENT`() {
        val n = deliver(delivery(stub(ok)))
        assertEquals(NotificationStatus.SENT, n.status)
        assertEquals("msg_abc", n.providerMessageId)
        assertEquals(1, n.attempts)
        assertEquals(1, calls.get())
    }

    @Test
    fun `이모지 40자 messageId(UTF-16 80단위)도 SENT — 길이는 코드 포인트로 센다`() {
        val id = "😀".repeat(40)
        val n = deliver(delivery(stub(reply(200, """{"messageId":"$id"}"""))))
        assertEquals(NotificationStatus.SENT, n.status)
        assertEquals(id, n.providerMessageId)
    }

    @Test
    fun `그 밖의 4xx는 재시도하지 않고 FAILED`() {
        val n = deliver(delivery(stub(reply(400, """{"code":"INVALID_REQUEST","message":"x"}"""))))
        assertEquals(NotificationStatus.FAILED, n.status)
        assertEquals("HTTP_400 INVALID_REQUEST", n.lastError)
        assertEquals(1, n.attempts)
        assertEquals(1, calls.get())
    }

    @Test
    fun `429는 기다리지 않고 바로 재시도 — 최초 1 + 재시도 3 = 4회 뒤 FAILED`() {
        val started = System.nanoTime()
        val n = deliver(delivery(stub(reply(429, """{"code":"QUOTA_EXCEEDED","message":"x"}"""))))
        assertEquals(NotificationStatus.FAILED, n.status)
        assertEquals("HTTP_429 QUOTA_EXCEEDED", n.lastError)
        assertEquals(4, n.attempts)
        assertEquals(4, calls.get())
        // Retry-After · 백오프 없음
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(2))
    }

    @Test
    fun `5xx도 4회 — 본문을 해석하지 못하면 상태만 남긴다`() {
        val n = deliver(delivery(stub(reply(503, "not json"))))
        assertEquals("HTTP_503", n.lastError)
        assertEquals(4, n.attempts)
        assertEquals(4, calls.get())
    }

    @Test
    fun `재시도 중 성공하면 그 시도 수로 SENT`() {
        val n = deliver(delivery(stub { i, ex -> if (i < 3) write(ex, 500, "{}") else write(ex, 200, """{"messageId":"m"}""") }))
        assertEquals(NotificationStatus.SENT, n.status)
        assertEquals(3, n.attempts)
        assertNull(n.lastError)
    }

    @Test
    fun `읽기 타임아웃도 재시도 — 4회 READ_TIMEOUT`() {
        val n = deliver(delivery(stub(reply(200, """{"messageId":"m"}""", sleepMs = 800)), readTimeout = Duration.ofMillis(200)))
        assertEquals(NotificationStatus.FAILED, n.status)
        assertEquals("READ_TIMEOUT", n.lastError)
        assertEquals(4, n.attempts)
        assertEquals(4, calls.get())
    }

    @Test
    fun `연결 실패도 재시도 — 4회 CONNECT_FAILED`() {
        // 묶어 두기만 하고 listen하지 않는 소켓 — 그 포트로의 연결은 확실히 거부된다(다른 프로세스가 가져갈 수도 없다)
        Socket().use { reserved ->
            reserved.bind(InetSocketAddress("127.0.0.1", 0))
            val n = deliver(delivery("http://127.0.0.1:${reserved.localPort}"))
            assertEquals(NotificationStatus.FAILED, n.status)
            assertEquals("CONNECT_FAILED", n.lastError)
            assertEquals(4, n.attempts)
        }
    }

    @Test
    fun `본문을 읽다 끊기면 IO_ERROR로 재시도 — 2xx여도 삼키지 않는다`() {
        // Content-Length 100을 약속하고 10바이트만 쓴 채 닫는다
        val n = deliver(delivery(stub { _, ex ->
            ex.sendResponseHeaders(200, 100)
            ex.responseBody.write("{\"message".toByteArray())
            ex.responseBody.flush()
        }))
        assertEquals(NotificationStatus.FAILED, n.status)
        assertEquals("IO_ERROR", n.lastError)
        assertEquals(4, n.attempts)
        assertEquals(4, calls.get())
    }

    @Test
    fun `2xx인데 JSON이 아니거나 messageId가 없거나 저장할 수 없으면 재시도 없이 INVALID_RESPONSE`() {
        for (body in listOf("{}", "not json", "", """{"messageId":"${"m".repeat(65)}"}""", """{"messageId":"a\u0000b"}""")) {
            calls.set(0)
            val n = deliver(delivery(stub(reply(200, body))))
            assertEquals(NotificationStatus.FAILED, n.status, body)
            assertEquals("INVALID_RESPONSE", n.lastError, body)
            assertEquals(1, calls.get(), body)
            stop()
        }
    }

    @Test
    fun `max-retries 0이면 한 번만`() {
        val n = deliver(delivery(stub(reply(429, "{}")), maxRetries = 0))
        assertEquals(1, n.attempts)
        assertEquals(1, calls.get())
    }

    @Test
    fun `호출 중 인터럽트면 재시도하지 않고 예외 · 인터럽트 상태 보존 · 결과 기록 없음`() {
        val d = delivery(stub(reply(200, """{"messageId":"m"}""", sleepMs = 3000)))
        val n = newNotification()
        val thrown = AtomicReference<Throwable?>()
        val stillInterrupted = AtomicReference<Boolean>()
        val worker = Thread {
            try {
                d.deliver(n)
            } catch (e: Throwable) {
                thrown.set(e)
            }
            stillInterrupted.set(Thread.currentThread().isInterrupted)
        }
        worker.start()
        Thread.sleep(300)
        worker.interrupt()
        worker.join(5000)
        assertIs<ProviderCallInterruptedException>(thrown.get())
        assertEquals(true, stillInterrupted.get())
        assertEquals(1, calls.get())
        assertEquals(NotificationStatus.REQUESTED, n.status)
        assertEquals(0, n.attempts)
    }
}
