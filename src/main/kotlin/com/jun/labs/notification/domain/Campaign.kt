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
 * Campaign 애그리거트 루트 — 대상 N명(userIdStart부터 targetCount명)에게 같은 내용을 한 채널로(ADR-000 §2.1).
 * 불변식: ⑤ RUNNING → COMPLETED만 ⑥ COMPLETED면 알림 targetCount개가 모두 SENT · FAILED(정상 경로 — 디스패처가 지킨다).
 * 진행 수(보냄 · 실패 · 대기)는 저장하지 않고 조회 때 알림 행을 센다.
 */
@Entity
@Table(name = "campaign")
class Campaign protected constructor(
    name: String,
    channel: Channel,
    userIdStart: Long,
    targetCount: Int,
    title: String,
    body: String,
    now: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false, length = NotificationRules.NAME_MAX)
    var name: String = name
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    var channel: Channel = channel
        protected set

    @Column(name = "user_id_start", nullable = false)
    var userIdStart: Long = userIdStart
        protected set

    @Column(name = "target_count", nullable = false)
    var targetCount: Int = targetCount
        protected set

    @Column(nullable = false, length = NotificationRules.TITLE_MAX)
    var title: String = title
        protected set

    @Column(nullable = false, length = NotificationRules.BODY_MAX)
    var body: String = body
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: CampaignStatus = CampaignStatus.RUNNING
        protected set

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = now
        protected set

    @Column(name = "completed_at")
    var completedAt: Instant? = null
        protected set

    /** 디스패처가 제출한 targetCount개 작업이 모두 끝났다 */
    fun complete(now: Instant) {
        check(status == CampaignStatus.RUNNING) { "완료는 RUNNING에서만: $id($status)" }
        status = CampaignStatus.COMPLETED
        completedAt = NotificationRules.normalize(now)
    }

    companion object {
        fun create(
            name: String,
            channel: Channel,
            userIdStart: Long,
            targetCount: Int,
            title: String,
            body: String,
            now: Instant,
        ): Campaign {
            NotificationRules.checkText(name, "name", NotificationRules.NAME_MAX)
            NotificationRules.checkTargets(userIdStart, targetCount)
            NotificationRules.checkText(title, "title", NotificationRules.TITLE_MAX)
            NotificationRules.checkText(body, "body", NotificationRules.BODY_MAX)
            return Campaign(name, channel, userIdStart, targetCount, title, body, NotificationRules.normalize(now))
        }
    }
}
