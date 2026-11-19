package com.jun.labs.notification.service.impl

import com.jun.labs.notification.domain.Channel
import com.jun.labs.notification.domain.ErrorCode
import com.jun.labs.notification.domain.Notification
import com.jun.labs.notification.domain.NotificationException
import com.jun.labs.notification.domain.NotificationRules
import com.jun.labs.notification.domain.repository.NotificationRepository
import com.jun.labs.notification.service.GetNotificationUseCase
import com.jun.labs.notification.service.NotificationResult
import com.jun.labs.notification.service.SendNotificationCommand
import com.jun.labs.notification.service.SendNotificationUseCase
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class SendNotificationService(
    private val notificationRepository: NotificationRepository,
    private val providerDelivery: ProviderDelivery,
    private val clock: Clock,
) : SendNotificationUseCase, GetNotificationUseCase {

    /**
     * 한 트랜잭션: 채널마다 INSERT(REQUESTED) → 발송사 호출(즉시 재시도) → SENT · FAILED → 다음 채널 → 커밋.
     * 발송사를 기다리는 동안 DB 커넥션을 쥔다 · event_id 중복은 보지 않는다(베이스라인).
     */
    @Transactional
    override fun send(command: SendNotificationCommand): List<NotificationResult> {
        // 채널 개수 · 중복은 엔티티 하나로 볼 수 없는 규칙이라 여기서 다시 본다(API 밖 진입점도 같은 규칙)
        NotificationRules.checkChannels(command.channels)
        return command.channels.map { channel -> sendOne(command, channel) }
    }

    private fun sendOne(command: SendNotificationCommand, channel: Channel): NotificationResult {
        val notification = notificationRepository.save(
            Notification.transactional(
                command.eventId, command.eventType, command.userId, channel, command.title, command.body, clock.instant(),
            ),
        )
        providerDelivery.deliver(notification)
        return NotificationResult.of(notification)
    }

    @Transactional(readOnly = true)
    override fun get(notificationId: Long): NotificationResult =
        NotificationResult.of(
            notificationRepository.findById(notificationId) ?: throw NotificationException(ErrorCode.NOTIFICATION_NOT_FOUND),
        )

    @Transactional(readOnly = true)
    override fun findByEventId(eventId: String): List<NotificationResult> =
        notificationRepository.findByEventIdOrderByIdAsc(eventId).map(NotificationResult::of)
}
