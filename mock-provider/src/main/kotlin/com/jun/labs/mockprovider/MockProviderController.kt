package com.jun.labs.mockprovider

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class SendRequest(val to: Long? = null, val title: String? = null, val body: String? = null)

data class SendAccepted(val messageId: String)

data class ProviderError(val code: String, val message: String)

/** 접수한 메시지는 쿼터용으로 세기만 한다 — 꺼내 보는 API는 없다(ADR-000 §7) */
@RestController
class MockProviderController(
    private val quota: QuotaWindows,
    private val faults: FaultInjector,
) {
    /**
     * 판정 순서: 본문 역직렬화(메서드 진입 전 — 깨진 JSON이면 채널과 무관하게 400) → 모르는 채널 404 → 지연 →
     * (error) 처리하지 않고 500 → 필드 형식 400 → 쿼터 429 → 접수 → (drop) 응답을 쥔다 → 200
     */
    @PostMapping("/v1/{channel}/send")
    fun send(@PathVariable channel: String, @RequestBody(required = false) request: SendRequest?): ResponseEntity<Any> {
        val ch = ProviderChannel.ofPath(channel)
            ?: return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "모르는 채널입니다.")
        faults.delay(ch)
        if (faults.shouldError(ch)) return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "일시적인 오류입니다.")
        if (request == null || request.to == null || request.to <= 0 || request.title.isNullOrBlank() || request.body.isNullOrBlank()) {
            return invalidRequest()
        }
        val retryAfter = quota.tryAcquire(ch)
        if (retryAfter != null) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, retryAfter.toString())
                .body(ProviderError("QUOTA_EXCEEDED", "분당 한도를 넘었습니다."))
        }
        val accepted = SendAccepted("msg_" + UUID.randomUUID().toString().replace("-", ""))
        faults.holdIfDropped(ch)
        return ResponseEntity.ok(accepted)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(): ResponseEntity<Any> = invalidRequest()

    private fun invalidRequest() = error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값이 올바르지 않습니다.")

    private fun error(status: HttpStatus, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).body(ProviderError(code, message))
}
