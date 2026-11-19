package com.jun.labs.notification.service

import com.jun.labs.notification.domain.Campaign
import com.jun.labs.notification.domain.CampaignStatus
import com.jun.labs.notification.domain.Channel
import com.jun.labs.notification.domain.Notification
import com.jun.labs.notification.domain.NotificationKind
import com.jun.labs.notification.domain.NotificationStatus
import java.time.Instant

/** 알림 응답(ADR-000 §6) */
data class NotificationResult(
    val notificationId: Long,
    val kind: NotificationKind,
    val eventId: String?,
    val eventType: String?,
    val campaignId: Long?,
    val userId: Long,
    val channel: Channel,
    val status: NotificationStatus,
    val attempts: Int,
    val providerMessageId: String?,
    val lastError: String?,
    val requestedAt: Instant,
    val sentAt: Instant?,
) {
    companion object {
        fun of(n: Notification) = NotificationResult(
            notificationId = checkNotNull(n.id),
            kind = n.kind,
            eventId = n.eventId,
            eventType = n.eventType,
            campaignId = n.campaignId,
            userId = n.userId,
            channel = n.channel,
            status = n.status,
            attempts = n.attempts,
            providerMessageId = n.providerMessageId,
            lastError = n.lastError,
            requestedAt = n.requestedAt,
            sentAt = n.sentAt,
        )
    }
}

/** 캠페인 응답(ADR-000 §6) — requested · sent · failed는 그 캠페인 알림 행을 상태별로 센 값 */
data class CampaignResult(
    val campaignId: Long,
    val name: String,
    val channel: Channel,
    val targetCount: Int,
    val status: CampaignStatus,
    val requested: Long,
    val sent: Long,
    val failed: Long,
    val createdAt: Instant,
    val completedAt: Instant?,
) {
    companion object {
        fun of(c: Campaign, counts: Map<NotificationStatus, Long>) = CampaignResult(
            campaignId = checkNotNull(c.id),
            name = c.name,
            channel = c.channel,
            targetCount = c.targetCount,
            status = c.status,
            requested = counts[NotificationStatus.REQUESTED] ?: 0,
            sent = counts[NotificationStatus.SENT] ?: 0,
            failed = counts[NotificationStatus.FAILED] ?: 0,
            createdAt = c.createdAt,
            completedAt = c.completedAt,
        )
    }
}
