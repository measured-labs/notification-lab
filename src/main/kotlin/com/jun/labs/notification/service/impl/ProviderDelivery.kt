package com.jun.labs.notification.service.impl

import com.jun.labs.notification.domain.Notification
import com.jun.labs.notification.infra.provider.ProviderCallInterruptedException
import com.jun.labs.notification.infra.provider.ProviderClient
import com.jun.labs.notification.infra.provider.ProviderResult
import com.jun.labs.notification.service.NotificationProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * 발송 + 즉시 재시도(ADR-000 §3) — 429 · 5xx · 연결 실패 · 읽기 타임아웃이면 기다리지 않고 바로 다시 보낸다.
 * 최초 1회 + 재시도 max-retries회. Retry-After는 보지 않는다. 그 밖의 4xx는 재시도 없이 FAILED.
 * 결과는 알림 행에 기록한다(호출하는 쪽 트랜잭션 안). 종료 중 인터럽트는 재시도하지 않고 예외로 올린다(롤백).
 */
@Component
class ProviderDelivery(
    private val providerClient: ProviderClient,
    properties: NotificationProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val maxAttempts = properties.retry.maxAttempts

    fun deliver(notification: Notification) {
        var attempts = 0
        var lastReason = ""
        while (attempts < maxAttempts) {
            if (Thread.currentThread().isInterrupted) throw ProviderCallInterruptedException()
            attempts++
            when (val result = providerClient.send(notification.channel, notification.userId, notification.title, notification.body)) {
                is ProviderResult.Accepted -> {
                    notification.markSent(result.messageId, attempts, clock.instant())
                    return
                }
                is ProviderResult.Rejected -> return fail(notification, result.reason, attempts)
                is ProviderResult.Retryable -> lastReason = result.reason
            }
        }
        fail(notification, lastReason, attempts)
    }

    private fun fail(notification: Notification, reason: String, attempts: Int) {
        notification.markFailed(reason, attempts, clock.instant())
        log.warn("발송 실패 notificationId={} channel={} attempts={} lastError={}", notification.id, notification.channel, attempts, reason)
    }
}
