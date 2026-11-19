package com.jun.labs.notification.blind

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.mockprovider.MockProviderApplication
import com.jun.labs.notification.support.MutableClock
import com.jun.labs.notification.support.Postgres
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

// ======================================================================
// JSON · HTTP
// ======================================================================

val BLIND_MAPPER = ObjectMapper()

/** JSON 본문에서 키 자체를 빼고 싶을 때 쓰는 표시. */
object Omit

/** JSON 조각을 그대로 넣는다(`1.0` · `9223372036854775808` · `"\uD800"` 같은 원시 토큰용). */
class Raw(val text: String) {
    override fun toString() = text
}

fun jsonValue(v: Any?): String = if (v is Raw) v.text else BLIND_MAPPER.writeValueAsString(v)

fun json(vararg pairs: Pair<String, Any?>): String =
    pairs.filter { it.second !== Omit }
        .joinToString(",", "{", "}") { (k, v) -> BLIND_MAPPER.writeValueAsString(k) + ":" + jsonValue(v) }

fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull || it.isMissingNode }?.asText()

fun JsonNode.instant(field: String): Instant = OffsetDateTime.parse(get(field).asText()).toInstant()

fun JsonNode.instantOrNull(field: String): Instant? = textOrNull(field)?.let { OffsetDateTime.parse(it).toInstant() }

fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

data class HttpResult(val status: Int, val body: String, val headers: Map<String, List<String>> = emptyMap()) {
    val json: JsonNode by lazy { BLIND_MAPPER.readTree(body.ifBlank { "{}" }) }
    val code: String? get() = json.textOrNull("code")

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.joinToString(",")
}

class JsonHttp(private val baseUrl: String, private val requestTimeout: Duration = Duration.ofSeconds(60)) {
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun get(path: String) = send("GET", path, null)

    fun post(path: String, body: String?, contentType: String = "application/json") = send("POST", path, body, contentType)

    fun send(method: String, path: String, body: String?, contentType: String = "application/json"): HttpResult {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
        if (body != null) builder.header("Content-Type", contentType)
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        builder.method(method, publisher)
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        return HttpResult(response.statusCode(), response.body() ?: "", response.headers().map())
    }
}

// ======================================================================
// mock 발송사 (test-contract §2) — 같은 JVM 랜덤 포트
// ======================================================================

object MockProviderLauncher {
    /** 앱 테스트 클래스패스에는 JPA · Flyway · PostgreSQL 드라이버가 있다 — DB 없는 mock 발송사가 그것을 자동 구성하지 않게 뺀다. */
    private val EXCLUDES = listOf(
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
    ).joinToString(",")

    /** ADR-000 §7 기본 쿼터. */
    val DEFAULT_QUOTA = mapOf("push" to 600_000L, "sms" to 60_000L, "email" to 120_000L)

    data class ChannelConfig(
        val quotaPerMinute: Long,
        val latencyMinMs: Long = 0,
        val latencyMaxMs: Long = 0,
        val errorRate: Double = 0.0,
        val dropRate: Double = 0.0,
        val dropHoldMs: Long = 1_000,
    )

    fun healthy(channel: String) = ChannelConfig(DEFAULT_QUOTA.getValue(channel))

    /** 설정 키는 ADR §7 그대로 — 기본값에 기대지 않고 채널마다 여섯 키를 모두 준다. */
    fun start(push: ChannelConfig = healthy("push"), sms: ChannelConfig = healthy("sms"), email: ChannelConfig = healthy("email")): ConfigurableApplicationContext {
        val props = mapOf("push" to push, "sms" to sms, "email" to email).flatMap { (ch, c) ->
            val p = "mockprovider.channels.$ch"
            listOf(
                "$p.quota-per-minute=${c.quotaPerMinute}",
                "$p.latency-min-ms=${c.latencyMinMs}",
                "$p.latency-max-ms=${c.latencyMaxMs}",
                "$p.error-rate=${c.errorRate}",
                "$p.drop-rate=${c.dropRate}",
                "$p.drop-hold-ms=${c.dropHoldMs}",
            )
        }
        return SpringApplicationBuilder(MockProviderApplication::class.java).run(
            "--server.port=0",
            "--spring.main.banner-mode=off",
            "--spring.autoconfigure.exclude=$EXCLUDES",
            *props.map { "--$it" }.toTypedArray(),
        )
    }

    fun baseUrl(context: ConfigurableApplicationContext): String =
        "http://localhost:" + context.environment.getProperty("local.server.port")
}

/**
 * 시나리오대로 답하는 발송사 대역 — 실제 mock 발송사는 무작위 · 꺼내 보기 API가 없어(ADR §7) 정확한 호출 횟수 · 순서 · 간격을
 * 확인할 수 없다. 재시도 계약(시도 수 = 실제 호출 수 · 즉시 · 어떤 상태에서 멈추나)과 "발송하지 않음"은 이 대역으로 확인한다.
 * 경로 · 요청 · 응답 모양은 ADR §7(`POST /v1/{channel}/send` · `{to, title, body}` · `200 {messageId}`)을 따른다.
 * 시나리오는 (to, 채널)마다 응답 줄 — 다 쓰면 200.
 */
object ScriptedProvider {
    data class Reply(val status: Int, val delayMs: Long = 0, val headers: Map<String, String> = emptyMap(), val messageId: String? = null)

    data class Call(
        val method: String,
        val path: String,
        val channel: String,
        val toNode: JsonNode?,
        val to: Long?,
        val title: String?,
        val body: String?,
        val atNanos: Long,
        val status: Int,
        val messageId: String?,
    )

    fun ok(messageId: String? = null) = Reply(200, messageId = messageId)

    fun status(code: Int, vararg headers: Pair<String, String>) = Reply(code, headers = mapOf(*headers))

    /** 접수(호출 기록 · messageId 발급)한 뒤 holdMs 동안 응답하지 않는다 — 응답 유실. */
    fun drop(holdMs: Long = 1_000, messageId: String? = null) = Reply(200, delayMs = holdMs, messageId = messageId)

    private val PATH = Regex("^/v1/([^/]+)/send$")
    private val calls = CopyOnWriteArrayList<Call>()
    private val scripts = ConcurrentHashMap<String, ConcurrentLinkedQueue<Reply>>()

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "scripted-provider").apply { isDaemon = true } }
            createContext("/") { ex -> handle(ex) }
            start()
        }
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun script(to: Long, channel: String, vararg replies: Reply) {
        scripts.computeIfAbsent("$to/${channel.lowercase()}") { ConcurrentLinkedQueue() }.addAll(replies)
    }

    fun callsFor(to: Long): List<Call> = calls.filter { it.to == to }

    fun totalCalls(): Int = calls.size

    private fun codeFor(status: Int) = when (status) {
        400 -> "INVALID_REQUEST"
        404 -> "NOT_FOUND"
        429 -> "QUOTA_EXCEEDED"
        in 500..599 -> "INTERNAL_ERROR"
        else -> "ERROR_$status"
    }

    private fun handle(ex: HttpExchange) {
        try {
            val raw = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val path = ex.requestURI.path
            val match = PATH.matchEntire(path)
            if (match == null || ex.requestMethod != "POST") {
                respond(ex, 404, json("code" to "NOT_FOUND", "message" to "no route"), emptyMap())
                return
            }
            val channel = match.groupValues[1]
            val node = runCatching { BLIND_MAPPER.readTree(raw) }.getOrNull()
            val toNode = node?.get("to")
            val to = toNode?.asText()?.toLongOrNull()
            val reply = scripts["$to/${channel.lowercase()}"]?.poll() ?: ok()
            val messageId = if (reply.status == 200) reply.messageId ?: ("stub-" + UUID.randomUUID()) else null
            calls += Call(
                method = ex.requestMethod,
                path = path,
                channel = channel,
                toNode = toNode,
                to = to,
                title = node?.textOrNull("title"),
                body = node?.textOrNull("body"),
                atNanos = System.nanoTime(),
                status = reply.status,
                messageId = messageId,
            )
            if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
            val body = if (reply.status == 200) json("messageId" to messageId)
            else json("code" to codeFor(reply.status), "message" to "scripted ${reply.status}")
            respond(ex, reply.status, body, reply.headers)
        } catch (_: IOException) {
            // 앱이 읽기 타임아웃으로 연결을 끊었다 — 응답 유실 시나리오의 정상 결과
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            ex.close()
        }
    }

    private fun respond(ex: HttpExchange, status: Int, body: String, headers: Map<String, String>) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}

// ======================================================================
// 앱 테스트 기반
// ======================================================================

@TestConfiguration(proxyBeanMethods = false)
class BlindClockConfig {
    /** 빈 이름 `clock` — 앱 쪽 Clock 빈(`systemClock`)과 이름이 겹치지 않고 @Primary로 이긴다(test-contract §1). */
    @Bean
    @Primary
    fun clock(): Clock = BLIND_CLOCK
}

/** 블라인드 스위트 전체가 공유하는 고정 시계 — 시각 필드가 이 값인지 본다. */
val BLIND_CLOCK = MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS))

val CHANNELS = listOf("PUSH", "SMS", "EMAIL")

/** 알림 응답 필드(ADR §6). */
val NOTIFICATION_FIELDS = listOf(
    "notificationId", "kind", "eventId", "eventType", "campaignId", "userId", "channel",
    "status", "attempts", "providerMessageId", "lastError", "requestedAt", "sentAt",
)

/** 캠페인 응답 필드(ADR §6). */
val CAMPAIGN_FIELDS = listOf(
    "campaignId", "name", "channel", "targetCount", "status", "requested", "sent", "failed", "createdAt", "completedAt",
)

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BlindClockConfig::class)
abstract class BlindTestSupport {

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    protected val app: JsonHttp get() = JsonHttp("http://localhost:$port")

    protected fun now(): Instant = BLIND_CLOCK.instant()

    /** 테스트마다 새 사용자 id — 다른 클래스 · 구현 테스트와 섞이지 않게 큰 난수 구간에서 증가. */
    protected fun newUserId(): Long = USER_SEQ.incrementAndGet()

    /** 캠페인 대상 범위 — 연속 n개를 통째로 잡는다. 반환 값 = 시작 id. */
    protected fun newUserRange(n: Int): Long = USER_SEQ.getAndAdd(n.toLong() + 1) + 1

    protected fun newEventId(): String = "evt-" + UUID.randomUUID()

    protected fun newCampaignName(): String = "camp-" + UUID.randomUUID()

    // ---- HTTP ----

    protected fun send(
        eventId: Any? = newEventId(),
        eventType: Any? = "ORDER_PAID",
        userId: Any? = newUserId(),
        channels: Any? = listOf("PUSH"),
        title: Any? = "주문 완료",
        body: Any? = "주문이 결제되었습니다.",
    ): HttpResult = app.post(
        "/notifications",
        json("eventId" to eventId, "eventType" to eventType, "userId" to userId, "channels" to channels, "title" to title, "body" to body),
    )

    /** 201 · 배열 · 요청 채널 수만큼 · 채널 순서대로인지 확인하고 배열을 돌려준다. */
    protected fun sendOk(
        eventId: String = newEventId(),
        userId: Long = newUserId(),
        channels: List<String> = listOf("PUSH"),
        eventType: String = "ORDER_PAID",
        title: String = "주문 완료",
        body: String = "주문이 결제되었습니다.",
    ): JsonNode {
        val res = send(eventId, eventType, userId, channels, title, body)
        assertThat(res.status).describedAs(res.body).isEqualTo(201)
        assertThat(res.json.isArray).describedAs("201 본문은 알림 응답 배열 — ${res.body}").isTrue()
        assertThat(res.json.size()).describedAs("채널당 응답 1개 — ${res.body}").isEqualTo(channels.size)
        assertThat(res.json.map { it.textOrNull("channel") }).describedAs("채널 순서대로").isEqualTo(channels)
        return res.json
    }

    protected fun getNotification(id: Any): HttpResult = app.get("/notifications/$id")

    protected fun listByEvent(eventId: String): HttpResult = app.get("/notifications?eventId=${enc(eventId)}")

    protected fun createCampaign(
        name: Any? = newCampaignName(),
        channel: Any? = "PUSH",
        userIdStart: Any? = newUserId(),
        targetCount: Any? = 1,
        title: Any? = "할인 안내",
        body: Any? = "오늘만 할인합니다.",
    ): HttpResult = app.post(
        "/campaigns",
        json("name" to name, "channel" to channel, "userIdStart" to userIdStart, "targetCount" to targetCount, "title" to title, "body" to body),
    )

    /** 202 · 접수 시점 스냅샷(RUNNING · 수 0)인지 확인하고 본문을 돌려준다(ADR §6). */
    protected fun createCampaignOk(
        targetCount: Int,
        channel: String = "PUSH",
        userIdStart: Long = newUserRange(targetCount),
        name: String = newCampaignName(),
        title: String = "할인 안내",
        body: String = "오늘만 할인합니다.",
    ): JsonNode {
        val res = createCampaign(name, channel, userIdStart, targetCount, title, body)
        assertThat(res.status).describedAs(res.body).isEqualTo(202)
        val c = res.json
        assertThat(c["campaignId"]?.isIntegralNumber).describedAs(res.body).isTrue()
        assertThat(c.textOrNull("name")).isEqualTo(name)
        assertThat(c.textOrNull("channel")).isEqualTo(channel)
        assertThat(c["targetCount"].asLong()).isEqualTo(targetCount.toLong())
        assertThat(c.textOrNull("status")).describedAs("접수 시점 스냅샷 — ${res.body}").isEqualTo("RUNNING")
        assertThat(c["requested"].asLong()).describedAs("스냅샷 requested — ${res.body}").isEqualTo(0L)
        assertThat(c["sent"].asLong()).describedAs("스냅샷 sent — ${res.body}").isEqualTo(0L)
        assertThat(c["failed"].asLong()).describedAs("스냅샷 failed — ${res.body}").isEqualTo(0L)
        assertThat(c.instant("createdAt")).isEqualTo(now())
        assertThat(c.textOrNull("completedAt")).describedAs("스냅샷 completedAt").isNull()
        return c
    }

    protected fun getCampaign(id: Any): HttpResult = app.get("/campaigns/$id")

    /** COMPLETED가 될 때까지 폴링(test-contract §4). */
    protected fun awaitCompleted(id: Long, timeout: Duration = Duration.ofSeconds(60)): JsonNode {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            val res = getCampaign(id)
            assertThat(res.status).describedAs(res.body).isEqualTo(200)
            if (res.json.textOrNull("status") == "COMPLETED") return res.json
            check(System.nanoTime() < deadline) { "캠페인 $id 가 ${timeout.seconds}초 안에 COMPLETED가 되지 않았다 — ${res.body}" }
            Thread.sleep(100)
        }
    }

    // ---- DB (ADR §5 열 이름) ----

    protected fun rowsByEvent(eventId: String): List<Map<String, Any?>> =
        jdbc.queryForList("select * from notification where event_id = ? order by id", eventId)

    protected fun rowsByUser(userId: Long): List<Map<String, Any?>> =
        jdbc.queryForList("select * from notification where user_id = ? order by id", userId)

    protected fun rowsByCampaign(campaignId: Long): List<Map<String, Any?>> =
        jdbc.queryForList("select * from notification where campaign_id = ? order by id", campaignId)

    protected fun notificationRow(id: Long): Map<String, Any?> = jdbc.queryForMap("select * from notification where id = ?", id)

    protected fun campaignRow(id: Long): Map<String, Any?> = jdbc.queryForMap("select * from campaign where id = ?", id)

    protected fun count(table: String): Long = jdbc.queryForObject("select count(*) from $table", Long::class.java)!!

    protected fun countWhere(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    protected fun Map<String, Any?>.long(col: String): Long = (this[col] as Number).toLong()

    protected fun Map<String, Any?>.longOrNull(col: String): Long? = (this[col] as Number?)?.toLong()

    protected fun Map<String, Any?>.str(col: String): String? = this[col]?.toString()

    protected fun Map<String, Any?>.time(col: String): Instant? = when (val v = this[col]) {
        null -> null
        is Timestamp -> v.toInstant()
        is OffsetDateTime -> v.toInstant()
        is Instant -> v
        else -> error("$col 시각 형식 알 수 없음: $v")
    }

    // ---- 단언 ----

    /** 오류 본문은 언제나 정확히 {code, message}(test-contract §4). */
    protected fun assertError(res: HttpResult, status: Int, code: String) {
        assertThat(res.status).describedAs(res.body).isEqualTo(status)
        assertThat(res.code).describedAs(res.body).isEqualTo(code)
        assertThat(res.json.textOrNull("message")).describedAs("message 필드 — ${res.body}").isNotNull()
        assertThat(res.json.fieldNames().asSequence().toSet()).describedAs("오류 본문 필드는 정확히 {code, message} — ${res.body}")
            .isEqualTo(setOf("code", "message"))
    }

    /** 응답 알림이 DB 행과 같다(ADR §5 · §6). */
    protected fun assertMatchesRow(n: JsonNode) {
        val row = notificationRow(n["notificationId"].asLong())
        assertThat(n.textOrNull("kind")).isEqualTo(row.str("kind"))
        assertThat(n.textOrNull("eventId")).isEqualTo(row.str("event_id"))
        assertThat(n.textOrNull("eventType")).isEqualTo(row.str("event_type"))
        assertThat(n.textOrNull("campaignId")?.toLong()).isEqualTo(row.longOrNull("campaign_id"))
        assertThat(n["userId"].asLong()).isEqualTo(row.long("user_id"))
        assertThat(n.textOrNull("channel")).isEqualTo(row.str("channel"))
        assertThat(n.textOrNull("status")).isEqualTo(row.str("status"))
        assertThat(n["attempts"].asLong()).isEqualTo(row.long("attempts"))
        assertThat(n.textOrNull("providerMessageId")).isEqualTo(row.str("provider_message_id"))
        assertThat(n.textOrNull("lastError")).isEqualTo(row.str("last_error"))
        assertThat(n.instant("requestedAt")).isEqualTo(row.time("requested_at"))
        assertThat(n.instantOrNull("sentAt")).isEqualTo(row.time("sent_at"))
        assertThat(row.time("updated_at")).describedAs("updated_at NOT NULL").isNotNull()
    }

    /** 첫 시도에 SENT된 트랜잭셔널 알림 응답(ADR §2.1 ② · §6). */
    protected fun assertSentFirstTry(n: JsonNode, eventId: String, eventType: String, userId: Long, channel: String) {
        assertThat(n["notificationId"]?.isIntegralNumber).describedAs(n.toString()).isTrue()
        assertThat(n.textOrNull("kind")).isEqualTo("TRANSACTIONAL")
        assertThat(n.textOrNull("eventId")).isEqualTo(eventId)
        assertThat(n.textOrNull("eventType")).isEqualTo(eventType)
        assertThat(n.textOrNull("campaignId")).isNull()
        assertThat(n["userId"].asLong()).isEqualTo(userId)
        assertThat(n.textOrNull("channel")).isEqualTo(channel)
        assertThat(n.textOrNull("status")).describedAs(n.toString()).isEqualTo("SENT")
        assertThat(n["attempts"].asInt()).describedAs(n.toString()).isEqualTo(1)
        assertThat(n.textOrNull("providerMessageId")).describedAs("SENT면 providerMessageId — $n").isNotBlank()
        assertThat(n.instant("requestedAt")).isEqualTo(now())
        assertThat(n.instant("sentAt")).isEqualTo(now())
    }

    /** FAILED(ADR §2.1 ③ ④): 시도 수 · lastError 있음(≤ 200자) · providerMessageId 없음. */
    protected fun assertFailed(n: JsonNode, attempts: Int) {
        assertThat(n.textOrNull("status")).describedAs(n.toString()).isEqualTo("FAILED")
        assertThat(n["attempts"].asInt()).describedAs("attempts — $n").isEqualTo(attempts)
        assertThat(n.textOrNull("lastError")).describedAs("FAILED면 lastError — $n").isNotBlank()
        assertThat(n.textOrNull("lastError")!!.codePointCount(0, n.textOrNull("lastError")!!.length)).isLessThanOrEqualTo(200)
        assertThat(n.textOrNull("providerMessageId")).describedAs("FAILED는 접수 id 없음 — $n").isNull()
    }

    protected fun assertSent(n: JsonNode, attempts: Int) {
        assertThat(n.textOrNull("status")).describedAs(n.toString()).isEqualTo("SENT")
        assertThat(n["attempts"].asInt()).describedAs("attempts — $n").isEqualTo(attempts)
        assertThat(n.textOrNull("providerMessageId")).describedAs("SENT면 providerMessageId — $n").isNotBlank()
    }

    companion object {
        private val USER_SEQ = AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000_000L) * 1_000L + 5_000_000_000_000L)

        @JvmStatic
        @DynamicPropertySource
        fun blindBaseProperties(registry: DynamicPropertyRegistry) {
            Postgres.register(registry)
            registry.add("notification.campaign.pool-size") { "4" }
        }
    }
}

/** 실제 mock 발송사 · 장애 없음 · ADR 기본 쿼터. 이 클래스를 상속한 테스트 클래스는 컨텍스트 하나를 공유한다. */
abstract class HealthyProviderBlindTest : BlindTestSupport() {
    companion object {
        private val provider: ConfigurableApplicationContext by lazy { MockProviderLauncher.start() }

        @JvmStatic
        @DynamicPropertySource
        fun healthyProviderProperties(registry: DynamicPropertyRegistry) {
            registry.add("notification.provider.base-url") { MockProviderLauncher.baseUrl(provider) }
        }
    }
}

/** 시나리오 발송사 대역 · 읽기 타임아웃 300ms(test-contract §3 허용). */
abstract class ScriptedProviderBlindTest : BlindTestSupport() {
    protected val provider = ScriptedProvider

    companion object {
        const val READ_TIMEOUT_MS = 300L

        @JvmStatic
        @DynamicPropertySource
        fun scriptedProviderProperties(registry: DynamicPropertyRegistry) {
            registry.add("notification.provider.base-url") { ScriptedProvider.baseUrl }
            registry.add("notification.provider.read-timeout") { "${READ_TIMEOUT_MS}ms" }
        }
    }
}
