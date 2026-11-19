package com.jun.labs.notification.service

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** ADR-000 §4 규칙 · 설정 — 기본값은 코드에 둔다 */
@ConfigurationProperties("notification")
data class NotificationProperties(
    val provider: Provider = Provider(),
    val retry: Retry = Retry(),
    val campaign: Campaign = Campaign(),
) {
    data class Provider(
        /** compose에서는 http://mock-provider:8116 */
        val baseUrl: String = "http://localhost:8116",
        val connectTimeout: Duration = Duration.ofSeconds(1),
        /** 발송사 지연 p99 2초(README)보다 길게 */
        val readTimeout: Duration = Duration.ofSeconds(5),
    ) {
        init {
            require(!connectTimeout.isNegative && !connectTimeout.isZero) { "notification.provider.connect-timeout은 0보다 커야: $connectTimeout" }
            require(!readTimeout.isNegative && !readTimeout.isZero) { "notification.provider.read-timeout은 0보다 커야: $readTimeout" }
        }
    }

    data class Retry(
        /** 즉시 재시도 횟수 — 최초 1회 + 재시도 maxRetries회 */
        val maxRetries: Int = 3,
    ) {
        init {
            require(maxRetries in 0..MAX_RETRIES_LIMIT) { "notification.retry.max-retries는 0~$MAX_RETRIES_LIMIT: $maxRetries" }
        }

        val maxAttempts: Int get() = 1 + maxRetries

        companion object {
            const val MAX_RETRIES_LIMIT = 100
        }
    }

    data class Campaign(
        /** 발송 풀 스레드 수 — 큐 길이는 poolSize × 2 */
        val poolSize: Int = 32,
    ) {
        init {
            require(poolSize in 1..POOL_SIZE_LIMIT) { "notification.campaign.pool-size는 1~$POOL_SIZE_LIMIT: $poolSize" }
        }

        companion object {
            const val POOL_SIZE_LIMIT = 1024
        }
    }
}
