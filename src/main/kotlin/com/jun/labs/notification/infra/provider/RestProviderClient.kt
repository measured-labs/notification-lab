package com.jun.labs.notification.infra.provider

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.jun.labs.notification.domain.Channel
import com.jun.labs.notification.domain.NotificationRules
import com.jun.labs.notification.service.NotificationProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException

/** 발송사 HTTP 어댑터 — `POST /v1/{channel}/send {to, title, body}` → `200 {messageId}` · 4xx · 5xx {code, message} */
@Component
class RestProviderClient(
    properties: NotificationProperties,
    builder: RestClient.Builder,
) : ProviderClient {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 응답 본문은 직접 읽고 직접 해석한다 — 읽다 난 I/O 오류(재시도)와 해석 실패(INVALID_RESPONSE)를 가르기 위해 */
    private val mapper: ObjectMapper = jacksonObjectMapper()

    private val restClient: RestClient = builder
        .baseUrl(properties.provider.baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(properties.provider.connectTimeout)
                    .build(),
            ).apply { setReadTimeout(properties.provider.readTimeout) },
        )
        .build()

    override fun send(channel: Channel, to: Long, title: String, body: String): ProviderResult = try {
        restClient.post()
            .uri("/v1/{channel}/send", channel.path)
            .contentType(MediaType.APPLICATION_JSON)
            .body(SendBody(to, title, body))
            .exchange<ProviderResult> { _, response ->
                val status = response.statusCode.value()
                // IOException은 RestClient가 ResourceAccessException으로 감싸 아래 classify로 간다
                val bytes = response.body.readAllBytes()
                when {
                    status in 200..299 -> accepted(parse(bytes, AcceptedBody::class.java)?.messageId)
                    status == 429 || status >= 500 -> ProviderResult.Retryable(errorReason(status, bytes))
                    else -> ProviderResult.Rejected(errorReason(status, bytes))
                }
            } ?: ProviderResult.Rejected("INVALID_RESPONSE")
    } catch (e: ResourceAccessException) {
        throwIfInterrupted(e)
        log.debug("발송사 I/O 오류 {}", channel, e)
        ProviderResult.Retryable(classify(e))
    } catch (e: RestClientException) {
        throwIfInterrupted(e)
        log.warn("발송사 응답을 처리하지 못함 {}", channel, e)
        ProviderResult.Rejected("INVALID_RESPONSE")
    }

    /** 2xx인데 JSON이 아니거나 messageId가 없거나 저장할 수 없으면 재시도 없이 실패로 둔다 — 2xx는 재시도 대상이 아니다 */
    private fun accepted(messageId: String?): ProviderResult =
        if (messageId != null && NotificationRules.isValidProviderMessageId(messageId)) {
            ProviderResult.Accepted(messageId)
        } else {
            ProviderResult.Rejected("INVALID_RESPONSE")
        }

    /** `HTTP_<상태> <발송사 코드>` — 발송사 코드는 저장할 수 있는 짧은 값만 붙인다(last_error 200자) */
    private fun errorReason(status: Int, bytes: ByteArray): String {
        val code = parse(bytes, ErrorBody::class.java)?.code
            ?.takeIf { it.isNotBlank() && it.length <= PROVIDER_CODE_MAX && NotificationRules.isStorable(it) }
        return if (code == null) "HTTP_$status" else "HTTP_$status $code"
    }

    /** 본문 해석 실패(JSON 아님 · 모양이 다름)는 null */
    private fun <T> parse(bytes: ByteArray, type: Class<T>): T? =
        if (bytes.isEmpty()) null else runCatching { mapper.readValue(bytes, type) }.getOrNull()

    /** 종료 중 인터럽트면 재시도 대상이 아니다 — 인터럽트 상태를 보존하고 올린다 */
    private fun throwIfInterrupted(e: Exception) {
        val interrupted = generateSequence<Throwable>(e) { it.cause }.take(10).any { it is InterruptedException }
        if (interrupted || Thread.currentThread().isInterrupted) {
            Thread.currentThread().interrupt()
            throw ProviderCallInterruptedException(e)
        }
    }

    /**
     * 읽기 타임아웃(응답 헤더까지) → READ_TIMEOUT · 연결 실패(연결 타임아웃 포함) → CONNECT_FAILED ·
     * 그 밖의 I/O(연결 끊김 · 본문을 읽다 끊김 — 본문 읽기 중 타임아웃은 스트림이 닫혀 여기로 온다) → IO_ERROR
     */
    private fun classify(e: ResourceAccessException): String {
        val causes = generateSequence<Throwable>(e) { it.cause }.take(10).toList()
        return when {
            causes.any { it is HttpConnectTimeoutException || it is ConnectException } -> "CONNECT_FAILED"
            causes.any { it is HttpTimeoutException || it is SocketTimeoutException } -> "READ_TIMEOUT"
            else -> "IO_ERROR"
        }
    }

    internal data class SendBody(val to: Long, val title: String, val body: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class AcceptedBody(val messageId: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class ErrorBody(val code: String? = null, val message: String? = null)

    private companion object {
        const val PROVIDER_CODE_MAX = 50
    }
}
