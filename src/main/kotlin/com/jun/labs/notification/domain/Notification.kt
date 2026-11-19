package com.jun.labs.notification.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Notification 애그리거트 루트 — (이벤트 또는 캠페인) × 사용자 × 채널 하나의 알림 1건(ADR-000 §2.1).
 * 불변식: ① REQUESTED → SENT · REQUESTED → FAILED만 ② SENT면 providerMessageId가 있다
 * ③ 커밋된 행은 attempts ≥ 1(상한 1 + max-retries는 재시도 루프가 지킨다) ④ FAILED면 lastError가 있다.
 * INSERT 때는 attempts = 0 · REQUESTED이고, 같은 트랜잭션 안에서 결과를 기록한 뒤 커밋한다.
 */
@Entity
@Table(name = "notification")
class Notification protected constructor(
    kind: NotificationKind,
    eventId: String?,
    eventType: String?,
    campaignId: Long?,
    userId: Long,
    channel: Channel,
    title: String,
    body: String,
    now: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var kind: NotificationKind = kind
        protected set

    @Column(name = "event_id", length = NotificationRules.EVENT_ID_MAX)
    var eventId: String? = eventId
        protected set

    @Column(name = "event_type", length = NotificationRules.EVENT_TYPE_MAX)
    var eventType: String? = eventType
        protected set

    @Column(name = "campaign_id")
    var campaignId: Long? = campaignId
        protected set

    @Column(name = "user_id", nullable = false)
    var userId: Long = userId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var channel: Channel = channel
        protected set

    @Column(nullable = false, length = NotificationRules.TITLE_MAX)
    var title: String = title
        protected set

    @Column(nullable = false, length = NotificationRules.BODY_MAX)
    var body: String = body
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: NotificationStatus = NotificationStatus.REQUESTED
        protected set

    @Column(nullable = false)
    var attempts: Int = 0
        protected set

    @Column(name = "provider_message_id", length = NotificationRules.PROVIDER_MESSAGE_ID_MAX)
    var providerMessageId: String? = null
        protected set

    @Column(name = "last_error", length = NotificationRules.LAST_ERROR_MAX)
    var lastError: String? = null
        protected set

    @Column(name = "requested_at", nullable = false)
    var requestedAt: Instant = now
        protected set

    @Column(name = "sent_at")
    var sentAt: Instant? = null
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = now
        protected set

    /** 발송사가 접수했다 — attempts는 이번 발송에 쓴 시도 수(1 이상) */
    fun markSent(providerMessageId: String, attempts: Int, now: Instant) {
        checkRequested()
        require(attempts >= 1) { "attempts는 1 이상: $attempts" }
        require(NotificationRules.isValidProviderMessageId(providerMessageId)) {
            "providerMessageId는 1~${NotificationRules.PROVIDER_MESSAGE_ID_MAX}자 · 저장 가능한 문자"
        }
        val at = NotificationRules.normalize(now)
        this.status = NotificationStatus.SENT
        this.providerMessageId = providerMessageId
        this.attempts = attempts
        this.sentAt = at
        this.updatedAt = at
    }

    /** 재시도 소진 · 재시도하지 않는 오류 — 롤백하지 않고 FAILED로 커밋한다 */
    fun markFailed(lastError: String, attempts: Int, now: Instant) {
        checkRequested()
        require(attempts >= 1) { "attempts는 1 이상: $attempts" }
        require(NotificationRules.isValidLastError(lastError)) {
            "lastError는 1~${NotificationRules.LAST_ERROR_MAX}자 · 저장 가능한 문자"
        }
        this.status = NotificationStatus.FAILED
        this.lastError = lastError
        this.attempts = attempts
        this.updatedAt = NotificationRules.normalize(now)
    }

    private fun checkRequested() {
        check(status == NotificationStatus.REQUESTED) { "결과 기록은 REQUESTED에서만: $id($status)" }
    }

    companion object {
        /** 트랜잭셔널 요청의 채널 하나 — event_id는 저장만 하고 중복을 보지 않는다 */
        fun transactional(
            eventId: String,
            eventType: String,
            userId: Long,
            channel: Channel,
            title: String,
            body: String,
            now: Instant,
        ): Notification {
            NotificationRules.checkText(eventId, "eventId", NotificationRules.EVENT_ID_MAX)
            NotificationRules.checkText(eventType, "eventType", NotificationRules.EVENT_TYPE_MAX)
            checkMessage(userId, title, body)
            return Notification(
                NotificationKind.TRANSACTIONAL, eventId, eventType, null,
                userId, channel, title, body, NotificationRules.normalize(now),
            )
        }

        /** 캠페인 대상 1명 */
        fun forCampaign(
            campaignId: Long,
            userId: Long,
            channel: Channel,
            title: String,
            body: String,
            now: Instant,
        ): Notification {
            require(campaignId > 0) { "campaignId: $campaignId" }
            checkMessage(userId, title, body)
            return Notification(
                NotificationKind.CAMPAIGN, null, null, campaignId,
                userId, channel, title, body, NotificationRules.normalize(now),
            )
        }

        private fun checkMessage(userId: Long, title: String, body: String) {
            NotificationRules.checkUserId(userId)
            NotificationRules.checkText(title, "title", NotificationRules.TITLE_MAX)
            NotificationRules.checkText(body, "body", NotificationRules.BODY_MAX)
        }
    }
}
