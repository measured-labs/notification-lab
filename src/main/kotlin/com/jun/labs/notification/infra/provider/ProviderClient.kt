package com.jun.labs.notification.infra.provider

import com.jun.labs.notification.domain.Channel

/** 발송사 호출 한 번의 결과 — 재시도 여부는 이 분류로만 정한다(ADR-000 §3) */
sealed interface ProviderResult {
    /** 2xx · messageId */
    data class Accepted(val messageId: String) : ProviderResult

    /** 429 · 5xx · 연결 실패 · 읽기 타임아웃 — 즉시 재시도 대상 */
    data class Retryable(val reason: String) : ProviderResult

    /** 그 밖의 4xx · 읽을 수 없는 응답 — 재시도하지 않는다 */
    data class Rejected(val reason: String) : ProviderResult
}

/** 바깥 발송사 — 한 번 보낸다(재시도는 호출하는 쪽) */
interface ProviderClient {
    fun send(channel: Channel, to: Long, title: String, body: String): ProviderResult
}
