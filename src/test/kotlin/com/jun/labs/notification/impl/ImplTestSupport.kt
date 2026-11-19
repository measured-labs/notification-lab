package com.jun.labs.notification.impl

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.jun.labs.mockprovider.MockProviderApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/** 흉내 발송사를 같은 JVM 랜덤 포트로 띄운다. 속성은 명령행 인자(앱 application.yml보다 우선) */
object MockProviderLauncher {
    fun start(vararg properties: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(MockProviderApplication::class.java)
            .run("--server.port=0", "--spring.main.banner-mode=off", *properties.map { "--$it" }.toTypedArray())

    fun baseUrl(context: ConfigurableApplicationContext): String =
        "http://localhost:" + context.environment.getProperty("local.server.port")
}

/** 테스트마다 겹치지 않는 값 — DB 컨테이너는 JVM당 하나(클래스끼리 공유) */
object Ids {
    private val seq = AtomicLong(System.nanoTime() % 1_000_000_000L * 1000)
    fun userId(): Long = seq.incrementAndGet()
    fun eventId(): String = "ev-" + seq.incrementAndGet()
}

class HttpResult(val status: Int, val body: JsonNode, val headers: Map<String, List<String>>)

/** 원시 JSON 문자열을 그대로 보낸다(1.5 · "1" 같은 형식 검증용) */
class Http(private val baseUrl: String) {
    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val mapper = ObjectMapper()

    fun post(path: String, json: String?): HttpResult {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30))
        val request = if (json == null) {
            builder.POST(HttpRequest.BodyPublishers.noBody()).build()
        } else {
            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build()
        }
        return send(request)
    }

    fun get(path: String): HttpResult =
        send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30)).GET().build())

    private fun send(request: HttpRequest): HttpResult {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val body = response.body().takeIf { it.isNotEmpty() }?.let { mapper.readTree(it) } ?: mapper.nullNode()
        return HttpResult(response.statusCode(), body, response.headers().map())
    }
}

/** 조건이 참이 될 때까지 기다린다 — 시간 안에 안 되면 실패 */
fun <T> awaitUntil(timeout: Duration, interval: Duration = Duration.ofMillis(50), probe: () -> T, done: (T) -> Boolean): T {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (true) {
        val value = probe()
        if (done(value)) return value
        if (System.nanoTime() > deadline) throw AssertionError("시간 안에 조건을 만족하지 못함: $value")
        Thread.sleep(interval.toMillis())
    }
}
